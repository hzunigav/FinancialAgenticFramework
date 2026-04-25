package com.neoproc.financialagent.worker;

import org.slf4j.MDC;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * CLI dev entry point. Reads {@code portal.id} and {@code params.*} from
 * system properties and delegates to {@link PortalRunService}.
 *
 * <p>Use this for local smoke testing without a broker:
 * <pre>
 *   mvn exec:java \
 *     -Dexec.mainClass=com.neoproc.financialagent.worker.Agent \
 *     -Dportal.id=mock-payroll \
 *     -Dparams.salary.E001=500000
 * </pre>
 *
 * <p>For the production queue consumer, use {@link WorkerApplication}.
 */
public class Agent {

    public static void main(String[] args) throws IOException, InterruptedException {
        Properties config = loadConfig();
        String portalId = System.getProperty("portal.id", config.getProperty("portal.id"));
        Path artifactsRoot = Paths.get(config.getProperty("artifacts.dir", "artifacts"))
                .toAbsolutePath().normalize();

        Map<String, String> extraBindings = new LinkedHashMap<>();
        System.getProperties().forEach((k, v) -> {
            String key = k.toString();
            if (key.startsWith("params.")) {
                extraBindings.put(key, v.toString());
            }
        });
        extraBindings.putIfAbsent("params.firmId", "1");

        String businessKey = extraBindings.get("params.businessKey");
        if (businessKey != null) {
            MDC.put("businessKey", businessKey);
        }
        MDC.put("firmId", extraBindings.get("params.firmId"));

        PortalRunService service = new PortalRunService(artifactsRoot);
        RunOutcome outcome = service.run(portalId, null, extraBindings);
        System.out.println("Status:    " + outcome.status());
        System.out.println("Artifacts: " + outcome.runDir());
    }

    private static Properties loadConfig() throws IOException {
        Properties props = new Properties();
        try (InputStream in = Agent.class.getResourceAsStream("/config.properties")) {
            if (in == null) {
                throw new IllegalStateException("config.properties not found on classpath");
            }
            props.load(in);
        }
        return props;
    }
}
