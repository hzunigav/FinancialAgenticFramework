package com.neoproc.financialagent.worker.ecs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Calls the ECS agent's task scale-in protection endpoint
 * ({@code $ECS_AGENT_URI/task-protection/v1/state}).
 *
 * <p>Why: services scale to zero on queue depth (scripts/scaling-lambda.py),
 * and deploys replace tasks. Either would otherwise SIGTERM a task halfway
 * through a payroll — runs last up to 30 minutes, far beyond any stop timeout,
 * and a half-applied CCSS or INS submission is not something a retry undoes.
 * A protected task keeps running until protection is released or expires;
 * ECS then stops it if the service no longer wants it.
 *
 * <p>The task role needs {@code ecs:UpdateTaskProtection}.
 */
public final class EcsTaskProtectionClient implements TaskProtection {

    private static final Logger log = LoggerFactory.getLogger(EcsTaskProtectionClient.class);

    /**
     * Upper bound, not the expected hold time — protection is released as soon
     * as the message finishes. It only matters if the worker dies without
     * releasing, so it must outlast the longest run: the task queues' 1800 s
     * visibility timeout already caps a run at 30 minutes.
     */
    static final int EXPIRES_IN_MINUTES = 60;

    private final URI endpoint;
    private final HttpClient http;

    EcsTaskProtectionClient(URI agentBaseUri) {
        this.endpoint = URI.create(stripTrailingSlash(agentBaseUri.toString()) + "/task-protection/v1/state");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    /**
     * The ECS agent sets {@code ECS_AGENT_URI} in every Fargate task (platform
     * 1.4+). Its absence means we are not on ECS, so there is nothing to protect.
     */
    public static TaskProtection fromEnvironment() {
        String base = System.getenv("ECS_AGENT_URI");
        if (base == null || base.isBlank()) {
            log.info("ECS_AGENT_URI not set — task scale-in protection disabled");
            return TaskProtection.NONE;
        }
        return new EcsTaskProtectionClient(URI.create(base));
    }

    @Override
    public void set(boolean enabled) {
        String body = enabled
                ? "{\"ProtectionEnabled\":true,\"ExpiresInMinutes\":" + EXPIRES_IN_MINUTES + "}"
                : "{\"ProtectionEnabled\":false}";
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            // The agent answers 200 with either {"protection":{...}} or
            // {"failure":{...}} / {"error":{...}}, so the status alone isn't enough.
            if (response.statusCode() == 200 && response.body().contains("\"protection\"")) {
                log.info("task scale-in protection {}", enabled ? "enabled" : "released");
            } else {
                log.warn("task scale-in protection {} failed: HTTP {} {}",
                        enabled ? "enable" : "release", response.statusCode(), response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("task scale-in protection call interrupted", e);
        } catch (Exception e) {
            log.warn("task scale-in protection call failed", e);
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
