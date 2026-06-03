package com.neoproc.financialagent.worker;

import com.neoproc.financialagent.worker.portal.PortalDescriptor;
import com.neoproc.financialagent.worker.portal.PortalDescriptorLoader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the AutoPlanilla multi-planilla wiring: the adapter turns
 * the resolved planilla list into the {@code forEach} list binding, and the
 * descriptor loops that binding by stable id.
 */
class AutoplanillaAdapterTest {

    private final AutoplanillaAdapter adapter = new AutoplanillaAdapter();

    @Test
    void beforeSteps_buildsPlanillasListBindingFromJson() {
        Map<String, String> bindings = new HashMap<>();
        bindings.put("params.planillasJson",
                "[{\"id\":\"1051\",\"name\":\"FEUJI Costa Rica USD\"},{\"id\":\"1052\"}]");
        Map<String, List<Map<String, String>>> listBindings = new HashMap<>();

        adapter.beforeSteps(null, null, bindings, listBindings, null, new RunManifest());

        List<Map<String, String>> rows = listBindings.get("params.planillas");
        assertEquals(2, rows.size());
        assertEquals("1051", rows.get(0).get("id"));
        assertEquals("FEUJI Costa Rica USD", rows.get(0).get("name"));
        assertEquals("1052", rows.get(1).get("id"));
        assertNull(rows.get(1).get("name"), "name is omitted when the planilla has no label");
    }

    @Test
    void beforeSteps_singularFallbackFromPlanillaId() {
        // CLI / legacy single-planilla path: no planillasJson, just the id.
        Map<String, String> bindings = new HashMap<>();
        bindings.put("params.planillaId", "1051");
        bindings.put("params.planillaName", "FEUJI Costa Rica USD");
        Map<String, List<Map<String, String>>> listBindings = new HashMap<>();

        adapter.beforeSteps(null, null, bindings, listBindings, null, new RunManifest());

        List<Map<String, String>> rows = listBindings.get("params.planillas");
        assertEquals(1, rows.size());
        assertEquals("1051", rows.get(0).get("id"));
    }

    @Test
    void descriptor_multiSelectLoopsForEachOverPlanillasById() throws IOException {
        PortalDescriptor d = PortalDescriptorLoader.load("autoplanilla");

        PortalDescriptor.Step forEach = d.steps().stream()
                .filter(s -> s.action() == PortalDescriptor.Action.forEach)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "autoplanilla steps must include a forEach for multi-planilla select"));

        assertEquals("params.planillas", forEach.over());
        assertEquals("planilla", forEach.item());
        assertEquals(1, forEach.steps().size());
        assertTrue(forEach.steps().get(0).selector().contains("${planilla.id}"),
                "the looped click must select each option by the per-item planilla id (data-value)");
    }
}
