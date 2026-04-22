package com.financialagent.worker;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class RunManifest {

    public String runId;
    public Instant startedAt;
    public Instant finishedAt;
    public String status = "RUNNING";
    public String error;

    public Portal portal = new Portal();
    public Artifacts artifacts = new Artifacts();

    public String finalUrl;
    public String finalTitle;

    public String playwrightVersion;
    public String agentWorkerVersion;

    public List<Step> steps = new ArrayList<>();

    public Verification verification;

    public static class Portal {
        public String id;
        public String baseUrl;
        public String username;
    }

    public static class Artifacts {
        public String trace = "trace.zip";
        public String har = "network.har";
        public String screenshot = "report.png";
    }

    public record Step(Instant at, String action, String target) {}

    public static class Verification {
        public String status;
        public Object source;
        public Object scraped;
        public List<Diff> diffs;

        public record Diff(String field, Object source, Object scraped) {}
    }

    public RunManifest step(String action, String target) {
        this.steps.add(new Step(Instant.now(), action, target));
        return this;
    }
}
