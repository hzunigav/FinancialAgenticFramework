#!/usr/bin/env bash
#
# Roster-drift test — exercises RosterDiff on both sides.
#
# Reuses the most recent capture envelope under artifacts/ (no re-scrape of
# AutoPlanilla), decrypts it, then seeds the harness with a deliberately
# drifted version of the roster:
#
#   - drops the first real employee from the seed (they stay in the capture)
#       -> expected to surface as `missingFromPortal` (on payroll, not on portal)
#   - appends two ghost employees that are NOT in the capture
#       -> expected to surface as `missingFromPayroll` (on portal, not on payroll)
#
# Then runs the mock-payroll submit agent against the ORIGINAL capture
# envelope. The adapter prints a "Roster diff:" block listing ids/names
# for both buckets, so the outcome is visible in the console.
#
# Prerequisites: same as run-pipeline.sh (harness running, creds set).
#
# Usage:
#   ./demo/test-roster-drift.sh [capture-envelope-path]
# If omitted, uses the newest artifacts/*/payroll-capture-result.v1.json.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

HARNESS_URL="http://localhost:3000"

CAPTURE_ENVELOPE="${1:-}"
if [ -z "$CAPTURE_ENVELOPE" ]; then
  CAPTURE_ENVELOPE=$(ls -td artifacts/*/payroll-capture-result.v1.json 2>/dev/null | head -1)
fi
if [ -z "$CAPTURE_ENVELOPE" ] || [ ! -f "$CAPTURE_ENVELOPE" ]; then
  echo "ERROR: no capture envelope found. Run ./demo/run-pipeline.sh first."
  exit 1
fi
CAPTURE_RUN_DIR="$(dirname "$CAPTURE_ENVELOPE")/"

echo "======================================================================"
echo "ROSTER DRIFT TEST"
echo "======================================================================"
echo "  Capture envelope: $CAPTURE_ENVELOPE"
echo ""

# Harness pre-flight
if ! curl -s -f -o /dev/null "$HARNESS_URL/login"; then
  echo "ERROR: harness not reachable at $HARNESS_URL"
  exit 1
fi

# Clean login every run — see run-pipeline.sh for rationale.
rm -f ~/.financeagent/sessions/mock-payroll.enc

# ---------------------------------------------------------------------------
# Decrypt the capture envelope so we can mutate it before seeding
# ---------------------------------------------------------------------------
DECRYPT_OUT="${CAPTURE_RUN_DIR}decrypted-capture.json"
if [ ! -f "$DECRYPT_OUT" ]; then
  echo "Decrypting capture envelope -> $DECRYPT_OUT"
  DECRYPT_LOG="$(mktemp -t decrypt-XXXXXX.log)"
  mvn -pl agent-worker exec:java -q \
      -Dexec.mainClass=com.neoproc.financialagent.worker.demo.CaptureEnvelopeInspector \
      -Dexec.args="$CAPTURE_ENVELOPE $DECRYPT_OUT" \
      > "$DECRYPT_LOG" 2>&1 || { echo "ERROR: decrypt failed:"; tail -30 "$DECRYPT_LOG"; exit 1; }
  rm -f "$DECRYPT_LOG"
fi

# ---------------------------------------------------------------------------
# Build a drifted seed: drop first employee, append 2 ghosts
# ---------------------------------------------------------------------------
DRIFTED_SEED="${CAPTURE_RUN_DIR}drifted-seed.json"
python - "$DECRYPT_OUT" "$DRIFTED_SEED" <<'PY'
import json, sys
src, dst = sys.argv[1], sys.argv[2]
emps = json.load(open(src, encoding='utf-8'))
if len(emps) < 2:
    raise SystemExit(f"capture has only {len(emps)} employees — need >=2 to drop one")
dropped = emps.pop(0)
ghosts = [
    {"id": "999999901", "displayName": "JUAN PEREZ GHOST",   "grossSalary": 1234567.89},
    {"id": "999999902", "displayName": "MARIA NUEVA EMPLEADA","grossSalary":  987654.32},
]
emps.extend(ghosts)
json.dump(emps, open(dst, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print(f"  dropped:   {dropped.get('id'):<14} {dropped.get('displayName')}")
print(f"  ghost #1:  {ghosts[0]['id']:<14} {ghosts[0]['displayName']}")
print(f"  ghost #2:  {ghosts[1]['id']:<14} {ghosts[1]['displayName']}")
print(f"  seed rows: {len(emps)} (= {len(emps)-2} real + 2 ghosts)")
PY

echo ""
echo "Seeding drifted roster to $HARNESS_URL/employees/seed ..."
SEED_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" \
    --data-binary @"$DRIFTED_SEED" \
    "$HARNESS_URL/employees/seed")
echo "[ok] $SEED_RESPONSE"

# ---------------------------------------------------------------------------
# Run submit — DO NOT redirect output; the adapter prints the roster diff.
# ---------------------------------------------------------------------------
echo ""
echo "Running mock-payroll submit with ORIGINAL (undrifted) capture envelope..."
echo "----------------------------------------------------------------------"
mvn -pl agent-worker exec:java -q \
    -Dportal.id=mock-payroll \
    -Dparams.firmId=12345 \
    -Dparams.source.captureEnvelope="$CAPTURE_ENVELOPE" \
    2>&1 | grep -vE '^(\[INFO\]|\[WARNING\])' || true

SUBMIT_RUN_DIR=$(ls -td artifacts/*/ 2>/dev/null | head -1)
echo ""
echo "======================================================================"
echo "Expected outcome under grand-total reconciliation:"
echo "  status:             MISMATCH"
echo "                        portal grand total = Σ(12 matched canonical) + Σ(2 ghost salaries)"
echo "                        canonical total    = Σ(13 canonical salaries)"
echo "                        gap ≈ sum(ghosts) - dropped.salary"
echo "  missingFromPortal:  1   (employee in capture but not in harness)"
echo "  missingFromPayroll: 2   (ghosts in harness but not in capture)"
echo ""
echo "Submit run: $SUBMIT_RUN_DIR"
echo "View drifted harness roster: $HARNESS_URL/employees"
echo "Reset:                       curl -X POST $HARNESS_URL/employees/reset"
