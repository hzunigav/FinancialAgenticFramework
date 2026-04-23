package com.neoproc.financialagent.worker.portal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Control-flow tests for the {@code forEach} action. We can verify the
 * binding-scope lifecycle (push, execute, pop) without a real Playwright
 * page — substeps are represented by {@code pause} actions, which
 * exercise the resolve path (placeholder expansion from the pushed
 * scope) and do not touch the page.
 */
class PortalEngineForEachTest {

    private static PortalDescriptor.Step pauseBindTo(String prompt, String bindTo) {
        return new PortalDescriptor.Step(
                PortalDescriptor.Action.pause,
                null, null, null, null, prompt, bindTo, null,
                null, null, null);
    }

    private static PortalDescriptor.Step forEachStep(String over, String item,
                                                     List<PortalDescriptor.Step> sub) {
        return new PortalDescriptor.Step(
                PortalDescriptor.Action.forEach,
                null, null, null, null, null, null, null,
                over, item, sub);
    }

    @Test
    void forEach_pushesRowScopeAndExecutesSubstepsPerRow() {
        // Each iteration records the resolved prompt, proving `${row.X}`
        // resolution draws from the pushed row scope.
        List<String> prompts = new ArrayList<>();
        Map<String, String> bindings = new HashMap<>();
        Map<String, List<Map<String, String>>> listBindings = new HashMap<>();
        listBindings.put("updates", List.of(
                Map.of("id", "A1", "salary", "100"),
                Map.of("id", "B2", "salary", "200"),
                Map.of("id", "C3", "salary", "300")));

        PortalEngine engine = new PortalEngine(
                null, bindings, listBindings,
                (a, t) -> {},
                prompt -> { prompts.add(prompt); return "ack"; },
                false);

        engine.runSteps("http://example",
                List.of(forEachStep("updates", "row",
                        List.of(pauseBindTo(
                                "filling ${row.id} with ${row.salary}",
                                "response")))));

        assertEquals(List.of(
                "filling A1 with 100",
                "filling B2 with 200",
                "filling C3 with 300"), prompts);
    }

    @Test
    void forEach_popsRowScopeAfterIteration() {
        // After the loop completes, row-scoped keys must not leak into
        // later steps — otherwise a subsequent ${row.id} could silently
        // resolve to the last row's value.
        Map<String, String> bindings = new HashMap<>();
        Map<String, List<Map<String, String>>> listBindings = new HashMap<>();
        listBindings.put("updates", List.of(Map.of("id", "X", "salary", "9")));

        PortalEngine engine = new PortalEngine(
                null, bindings, listBindings,
                (a, t) -> {},
                prompt -> "",
                false);

        engine.runSteps("http://example",
                List.of(forEachStep("updates", "row",
                        List.of(pauseBindTo("touch", "noop")))));

        assertTrue(bindings.keySet().stream().noneMatch(k -> k.startsWith("row.")),
                "row-scoped keys should not leak after forEach completes: " + bindings);
    }

    @Test
    void forEach_emptyListIsNoOp() {
        Map<String, List<Map<String, String>>> listBindings = new HashMap<>();
        listBindings.put("updates", List.of());

        PortalEngine engine = new PortalEngine(
                null, new HashMap<>(), listBindings,
                (a, t) -> {},
                prompt -> { throw new AssertionError("substeps should not run for empty list"); },
                false);

        engine.runSteps("http://example",
                List.of(forEachStep("updates", "row",
                        List.of(pauseBindTo("should-not-fire", "noop")))));
    }

    @Test
    void forEach_missingListBinding_throws() {
        PortalEngine engine = new PortalEngine(
                null, new HashMap<>(), new HashMap<>(),
                (a, t) -> {},
                prompt -> "",
                false);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> engine.runSteps("http://example",
                        List.of(forEachStep("missing", "row",
                                List.of(pauseBindTo("x", "y"))))));
        assertTrue(ex.getMessage().contains("missing"),
                "error should name the missing binding key");
    }

    @Test
    void forEach_submitInSubstep_stillTripsShadowGuard() {
        PortalDescriptor.Step submit = new PortalDescriptor.Step(
                PortalDescriptor.Action.click,
                "button[type='submit']",
                null, null, null, null, null, Boolean.TRUE,
                null, null, null);

        Map<String, List<Map<String, String>>> listBindings = new HashMap<>();
        listBindings.put("updates", List.of(Map.of("id", "A1")));

        PortalEngine engine = new PortalEngine(
                null, new HashMap<>(), listBindings,
                (a, t) -> {},
                prompt -> "",
                true);

        assertThrows(PortalEngine.ShadowHalt.class,
                () -> engine.runSteps("http://example",
                        List.of(forEachStep("updates", "row", List.of(submit)))));
    }
}
