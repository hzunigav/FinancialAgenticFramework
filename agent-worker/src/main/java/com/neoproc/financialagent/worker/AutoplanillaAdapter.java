package com.neoproc.financialagent.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import com.neoproc.financialagent.common.credentials.PortalCredentials;
import com.neoproc.financialagent.common.domain.PayrollEmployeeRow;
import com.neoproc.financialagent.common.domain.PayrollSummary;
import com.neoproc.financialagent.contract.payroll.CaptureResultBody;
import com.neoproc.financialagent.contract.payroll.EmployeeRow;
import com.neoproc.financialagent.contract.payroll.EnvelopeStatus;
import com.neoproc.financialagent.contract.payroll.Period;
import com.neoproc.financialagent.contract.payroll.Planilla;
import com.neoproc.financialagent.worker.portal.AutoplanillaMapper;
import com.neoproc.financialagent.worker.portal.PortalDescriptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Capture-only at M3. Pulls the CCSS report into a typed
 * {@link PayrollSummary} on the manifest. Envelope emission is handled
 * by the {@link AbstractCaptureAdapter} base class — this adapter only
 * needs to describe the portal-specific content.
 *
 * <p>Supports multi-planilla consolidated capture: when the request carries
 * {@code task.planillas} (serialized into {@code params.planillasJson} by the
 * listener), {@link #beforeSteps} loads the list into {@code listBindings} so
 * the descriptor's {@code forEach} ticks every planilla's checkbox, and
 * AutoPlanilla consolidates the selection into one report. A single-planilla
 * request (singular {@code task.planilla}, or a CLI {@code -Dparams.planillaId})
 * is just the one-element case of the same path.
 */
final class AutoplanillaAdapter extends AbstractCaptureAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Planilla>> PLANILLA_LIST = new TypeReference<>() {};

    @Override
    public void beforeSteps(PortalDescriptor descriptor,
                            Page page,
                            Map<String, String> bindings,
                            Map<String, List<Map<String, String>>> listBindings,
                            PortalCredentials credentials,
                            RunManifest manifest) {
        // Feed the descriptor's `forEach over: params.planillas` — one row per
        // planilla, keyed by the stable id the multi-select matches on data-value.
        List<Planilla> planillas = resolvePlanillas(bindings);
        List<Map<String, String>> rows = new ArrayList<>(planillas.size());
        for (Planilla p : planillas) {
            if (p.id() == null || p.id().isBlank()) {
                throw new IllegalStateException(
                        "autoplanilla multi-select needs an id per planilla "
                                + "(the option is matched by data-value); got one without: " + p);
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("id", p.id());
            if (p.name() != null) row.put("name", p.name());
            rows.add(row);
        }
        listBindings.put("params.planillas", rows);
        manifest.step("multi-select",
                planillas.size() + " planilla(s): "
                        + planillas.stream().map(Planilla::id).collect(Collectors.joining(", ")));
    }

    @Override
    protected CaptureOutcome buildCaptureOutcome(Map<String, String> scraped,
                                                 List<Map<String, String>> scrapedRows,
                                                 Map<String, String> bindings,
                                                 PortalCredentials credentials,
                                                 RunManifest manifest) {
        List<Planilla> planillas = resolvePlanillas(bindings);
        LocalDate from = LocalDate.parse(require(bindings, "params.from"));
        LocalDate to = LocalDate.parse(require(bindings, "params.to"));
        String label = planillaLabel(planillas);

        PayrollSummary summary = AutoplanillaMapper.toSummary(
                scraped, scrapedRows, label, from, to);
        manifest.scraped = summary;
        manifest.step("capture",
                "planillas=" + label
                        + ", total=" + summary.totalGrossSalaries()
                        + ", renta=" + summary.totalRenta()
                        + ", employees=" + summary.employeeCount()
                        + ", rows=" + summary.employees().size());

        CaptureResultBody body = new CaptureResultBody(
                EnvelopeStatus.SUCCESS,
                new CaptureResultBody.Totals(
                        "CRC",
                        summary.totalGrossSalaries(),
                        summary.totalRenta(),
                        summary.employeeCount()),
                summary.employees().stream()
                        .map(AutoplanillaAdapter::toContractRow)
                        .toList());

        String businessKey = from.getYear() + "-"
                + String.format("%02d", from.getMonthValue())
                + "::" + label;

        // Echo the selection on the result task: a single NAMED planilla keeps
        // the legacy singular shape (the result schema's `planilla` requires a
        // name); anything else — multiple planillas, or a single id-only one —
        // uses the `planillas` array (whose items need only an id).
        boolean singularEcho = planillas.size() == 1 && planillas.get(0).name() != null;
        Planilla single = singularEcho ? planillas.get(0) : null;
        List<Planilla> multi = singularEcho ? null : planillas;

        return new CaptureOutcome(
                "CAPTURED",
                body,
                "autoplanilla",
                businessKey,
                "agent-worker/autoplanilla",
                new Period(from, to),
                single,
                multi);
    }

    /**
     * The planilla selection, from {@code params.planillasJson} (broker /
     * multi-select) or, failing that, a single {@code params.planillaId} /
     * {@code params.planillaName} (CLI / legacy single-planilla).
     */
    private static List<Planilla> resolvePlanillas(Map<String, String> bindings) {
        String json = bindings.get("params.planillasJson");
        if (json != null && !json.isBlank()) {
            try {
                List<Planilla> parsed = MAPPER.readValue(json, PLANILLA_LIST);
                if (!parsed.isEmpty()) return parsed;
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("invalid params.planillasJson: " + json, e);
            }
        }
        String id = bindings.get("params.planillaId");
        String name = bindings.get("params.planillaName");
        if (id != null || name != null) {
            return List.of(new Planilla(id, name));
        }
        throw new IllegalStateException(
                "autoplanilla capture requires params.planillasJson or "
                        + "params.planillaId / params.planillaName");
    }

    /** Human-readable label for the manifest + CLI-fallback businessKey. */
    private static String planillaLabel(List<Planilla> planillas) {
        return planillas.stream()
                .map(p -> p.name() != null ? p.name() : p.id())
                .collect(Collectors.joining(" + "));
    }

    private static EmployeeRow toContractRow(PayrollEmployeeRow r) {
        return new EmployeeRow(r.id(), r.name(), r.grossSalary(), Map.of());
    }
}
