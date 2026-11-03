package com.neoproc.financialagent.worker.ecs;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Exercises the client against a stand-in for the ECS agent endpoint. */
class EcsTaskProtectionClientTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String response = "{\"protection\":{\"ProtectionEnabled\":true}}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " " + body);
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private EcsTaskProtectionClient client(String suffix) {
        return new EcsTaskProtectionClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v4/abc" + suffix));
    }

    @Test
    void enableSendsProtectionWithAnExpiry() {
        client("").set(true);

        assertThat(requests).containsExactly(
                "PUT /v4/abc/task-protection/v1/state {\"ProtectionEnabled\":true,\"ExpiresInMinutes\":"
                        + EcsTaskProtectionClient.EXPIRES_IN_MINUTES + "}");
    }

    @Test
    void releaseSendsProtectionDisabled() {
        client("/").set(false);   // trailing slash on ECS_AGENT_URI must not double up

        assertThat(requests).containsExactly(
                "PUT /v4/abc/task-protection/v1/state {\"ProtectionEnabled\":false}");
    }

    @Test
    void agentFailuresNeverPropagateToTheRun() {
        status = 200;
        response = "{\"failure\":{\"Reason\":\"TASK_NOT_VALID\"}}";
        assertThatCode(() -> client("").set(true)).doesNotThrowAnyException();

        status = 500;
        response = "boom";
        assertThatCode(() -> client("").set(true)).doesNotThrowAnyException();

        server.stop(0);   // connection refused
        assertThatCode(() -> client("").set(false)).doesNotThrowAnyException();
    }
}
