package com.neoproc.financialagent.worker.demo;

import com.neoproc.financialagent.common.session.SessionStores;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Seeds a portal session into the configured {@link SessionStores#defaultStore()}
 * from a captured Playwright {@code storageState} JSON file. This is the
 * human-assisted re-seed path: log in once (e.g. via {@code XeroLoginSpike},
 * checking "Trust this device"), then push the captured session so the headless
 * worker can reuse it — never having to drive the Akamai-guarded login itself.
 *
 * <p>Run (writes to AWS Secrets Manager when {@code FINANCEAGENT_CREDENTIALS=aws},
 * else the local encrypted store):
 * <pre>
 * mvn.cmd -pl agent-worker exec:java -q \
 *   -Dexec.mainClass=com.neoproc.financialagent.worker.demo.SessionSeeder \
 *   -Dexec.args="xero xero-spike/storageState.json"
 * </pre>
 *
 * <p>Args: {@code <portalId> <storageState.json path>}.
 */
public final class SessionSeeder {

    private SessionSeeder() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: SessionSeeder <portalId> <storageState.json>");
            System.exit(2);
        }
        String portalId = args[0];
        String storageState = Files.readString(Path.of(args[1]));

        SessionStores.defaultStore().save(portalId, storageState);

        boolean aws = "aws".equalsIgnoreCase(System.getenv("FINANCEAGENT_CREDENTIALS"));
        System.out.println("Seeded session for portal=" + portalId + " into "
                + (aws ? "AWS Secrets Manager" : "the local encrypted store")
                + " (" + storageState.length() + " bytes).");
    }
}
