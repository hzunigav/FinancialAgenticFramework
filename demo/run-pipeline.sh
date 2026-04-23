#!/usr/bin/env bash
#
# End-to-end pipeline: real AutoPlanilla capture → mock INS-style view.
#
# Step 1 (real BPMN "Capture" Service Task) — runs the AutoPlanilla agent
#        against app.autoplanilla.com for the period you pass in, emits a
#        payroll-capture-result.v1 envelope.
#
# Step 2 (dev glue) — posts the captured employees[] to the harness's
#        /employees/seed endpoint so the INS-style view shows the real
#        NeoProc roster instead of the hardcoded demo 15.
#
# Step 3 (real BPMN "Submit" Service Task) — the mock-payroll agent
#        consumes the capture envelope, fuzzy-matches against the now
#        seeded harness, fills salaries, submits, emits a
#        payroll-submit-result.v1 envelope with roster_diff.
#
# Prerequisites:
#   - Harness running on port 3000:  mvn -pl testing-harness spring-boot:run
#   - Credentials in ~/.financeagent/secrets.properties — one username and
#     one password key for each of portals.autoplanilla and
#     portals.mock-payroll, under the .credentials. prefix. See
#     LocalFileCredentialsProvider for the exact key format.
#   - Playwright Chromium installed: npx playwright install chromium
#
# Usage:
#   ./demo/run-pipeline.sh <from-date> <to-date> "<planilla-name>"
#   ./demo/run-pipeline.sh 2026-02-01 2026-02-28 "NeoProc Quincenal USD"

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

FROM="${1:-2026-02-01}"
TO="${2:-2026-02-28}"
PLANILLA="${3:-NeoProc Quincenal USD}"

HARNESS_URL="http://localhost:3000"
CAPTURE_LOG="$(mktemp -t capture-XXXXXX.log)"
SUBMIT_LOG="$(mktemp -t submit-XXXXXX.log)"

banner() { printf '\n%s\n' "======================================================================"; printf '%s\n' "$1"; printf '%s\n\n' "======================================================================"; }
step()   { printf '\n%s\n' "----------------------------------------------------------------------"; printf '%s\n' "$1"; printf '%s\n\n' "----------------------------------------------------------------------"; }

banner "PAYROLL PIPELINE DEMO (end-to-end, real AutoPlanilla data)"
echo "  Period:   $FROM  ->  $TO"
echo "  Planilla: $PLANILLA"
echo "  Firm:     12345 (demo tenant)"

# ---------------------------------------------------------------------------
# Pre-flight
# ---------------------------------------------------------------------------
if ! curl -s -f -o /dev/null "$HARNESS_URL/login"; then
  echo ""
  echo "ERROR: harness not reachable at $HARNESS_URL"
  echo "Start it first:  mvn -pl testing-harness spring-boot:run"
  exit 1
fi
echo "[ok] harness reachable"

# Purge cached portal sessions so every demo run does a clean login.
# Rationale: server-side cookies can expire on AutoPlanilla / mock before
# our local TTL. A stale cached session makes the agent skip auth and
# then stall on a selector that only exists post-login. Cheap to re-auth
# (~2-3s), so we eat the cost to keep the demo deterministic.
rm -f ~/.financeagent/sessions/autoplanilla.enc ~/.financeagent/sessions/mock-payroll.enc
echo "[ok] purged cached sessions"

# ---------------------------------------------------------------------------
# STEP 1 — real AutoPlanilla capture
# ---------------------------------------------------------------------------
step "STEP 1/3: AutoPlanilla capture (real agent run)"
echo "Invoking agent — this logs into app.autoplanilla.com, navigates to the"
echo "CCSS report, sets the date range, and scrapes rows. Takes ~30-60s."
echo "Full log: $CAPTURE_LOG"
echo ""

set +e
mvn -pl agent-worker exec:java -q \
    -Dportal.id=autoplanilla \
    -Dparams.firmId=12345 \
    -Dparams.planillaName="$PLANILLA" \
    -Dparams.fechaInicio="$FROM" \
    -Dparams.fechaFinal="$TO" \
    > "$CAPTURE_LOG" 2>&1
CAPTURE_STATUS=$?
set -e

if [ "$CAPTURE_STATUS" -ne 0 ]; then
  echo "ERROR: capture agent exited $CAPTURE_STATUS"
  echo "Tail of capture log:"
  tail -60 "$CAPTURE_LOG"
  exit $CAPTURE_STATUS
fi

# Find newest run dir (the capture run)
CAPTURE_RUN_DIR=$(ls -td artifacts/*/ 2>/dev/null | head -1)
CAPTURE_ENVELOPE="${CAPTURE_RUN_DIR}payroll-capture-result.v1.json"

if [ ! -f "$CAPTURE_ENVELOPE" ]; then
  echo "ERROR: capture envelope not found at $CAPTURE_ENVELOPE"
  echo "Capture log tail:"
  tail -40 "$CAPTURE_LOG"
  exit 1
fi

echo "[ok] capture complete"
echo "     envelope: $CAPTURE_ENVELOPE"
echo ""
echo "Capture highlights:"
grep -E '^(Run|Portal:|Session|Status:|Manifest:|capture|scrape|envelope)' "$CAPTURE_LOG" | sed 's/^/  /'

# The capture envelope's result block is encrypted. To extract the employees[]
# for seeding, we decrypt via a one-shot Java helper that uses the same cipher.
# For this demo we rely on the fact that LocalDevCipher uses a key file in
# ~/.financeagent/cipher-keys/payroll-firm-12345 — both the capture writer and
# this script's decrypt can read it. If FINANCEAGENT_CIPHER=none later, the
# result block is cleartext and decrypt is unnecessary.

step "STEP 2/3: Seed the harness from the capture envelope"

# Use a file inside the capture run dir so the path is interpreted the
# same way by MSYS bash and the Windows-native Java process. POSIX /tmp
# resolves to C:\tmp on the JVM side — different from MSYS's /tmp, which
# would silently drop the decrypt output somewhere we can't read it.
DECRYPT_OUT="${CAPTURE_RUN_DIR}decrypted-capture.json"
trap 'rm -f "$CAPTURE_LOG" "$SUBMIT_LOG"' EXIT

# Run a small Java helper (CaptureEnvelopeInspector) that reads the envelope,
# decrypts if needed, and writes the decrypted result.employees[] array as
# plain JSON to DECRYPT_OUT.
DECRYPT_LOG="$(mktemp -t decrypt-XXXXXX.log)"
mvn -pl agent-worker exec:java -q \
    -Dexec.mainClass=com.neoproc.financialagent.worker.demo.CaptureEnvelopeInspector \
    -Dexec.args="$CAPTURE_ENVELOPE $DECRYPT_OUT" \
    > "$DECRYPT_LOG" 2>&1 || {
      echo "ERROR: failed to decrypt capture envelope for seed."
      echo "Decrypt log tail:"
      tail -30 "$DECRYPT_LOG"
      exit 1
    }
rm -f "$DECRYPT_LOG"

if [ ! -f "$DECRYPT_OUT" ]; then
  echo "ERROR: inspector ran but $DECRYPT_OUT was not produced"
  exit 1
fi

EMP_COUNT=$(python -c "import json; print(len(json.load(open(r'$DECRYPT_OUT', encoding='utf-8'))))")
echo "Captured $EMP_COUNT employees. Posting to $HARNESS_URL/employees/seed ..."

SEED_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" \
    --data-binary @"$DECRYPT_OUT" \
    "$HARNESS_URL/employees/seed")
echo "[ok] $SEED_RESPONSE"
echo ""
echo "Seeded employees (first 5):"
python -c "
import json
emps = json.load(open(r'$DECRYPT_OUT', encoding='utf-8'))
for e in emps[:5]:
    print(f\"  {e.get('id'):<14} {e.get('displayName') or e.get('name'):<42} {e.get('grossSalary') or e.get('currentSalary')}\")
if len(emps) > 5:
    print(f\"  ... ({len(emps) - 5} more)\")
"

# ---------------------------------------------------------------------------
# STEP 3 — mock-payroll submit agent
# ---------------------------------------------------------------------------
step "STEP 3/3: mock-payroll submit agent"
echo "Agent logs into the harness, matches captured employees against the"
echo "seeded roster (should be 100% match now), fills salaries, submits,"
echo "and reconciles totals. Takes ~20-40s."
echo "Full log: $SUBMIT_LOG"
echo ""

set +e
mvn -pl agent-worker exec:java -q \
    -Dportal.id=mock-payroll \
    -Dparams.firmId=12345 \
    -Dparams.source.captureEnvelope="$CAPTURE_ENVELOPE" \
    > "$SUBMIT_LOG" 2>&1
SUBMIT_STATUS=$?
set -e

if [ "$SUBMIT_STATUS" -ne 0 ]; then
  echo "ERROR: submit agent exited $SUBMIT_STATUS"
  tail -60 "$SUBMIT_LOG"
  exit $SUBMIT_STATUS
fi

SUBMIT_RUN_DIR=$(ls -td artifacts/*/ 2>/dev/null | head -1)

echo "Submit highlights:"
grep -E '^(Run|Portal:|Session|Status:|Manifest:|\*\*\*|source|match-|roster-|verify|envelope)' "$SUBMIT_LOG" | sed 's/^/  /'

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
banner "DEMO COMPLETE"

cat <<EOF
  Open http://localhost:3000/employees in your browser. You should see:
    - The real NeoProc employees for $FROM .. $TO (seeded from capture)
    - Their salaries, as updated by the submit agent

  Artifacts:
    Capture run: $CAPTURE_RUN_DIR
    Submit run:  $SUBMIT_RUN_DIR

  To reset the harness back to the original 15-employee demo roster:
    curl -X POST $HARNESS_URL/employees/reset

EOF
