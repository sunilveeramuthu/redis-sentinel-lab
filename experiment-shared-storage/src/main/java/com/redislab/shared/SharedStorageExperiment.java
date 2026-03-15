package com.redislab.shared;

import com.redislab.core.CorruptionReport;
import com.redislab.core.DatasetFixture;
import com.redislab.core.RedisLabClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Experiment: Shared Storage
 *
 * All three Redis nodes (master + 2 replicas) mount the SAME Docker volume.
 *
 * To expose data loss the experiment:
 *   1. Writes a stable baseline dataset.
 *   2. Severs replication on both replicas (REPLICAOF NO ONE) so the OS TCP stack
 *      has no open replication socket to buffer incoming writes into.
 *   3. Writes a second "unreplicated" batch directly to the master.
 *      These writes land in the master's AOF and are ACK'd to the client, but the
 *      replicas have no replication connection — the data never reaches them.
 *   4. Kills the master with SIGKILL (no flush, no graceful hand-off).
 *   5. Waits for Sentinel to promote one of the replicas as the new master.
 *   6. Reads back every key that was ACK'd from the new master and reports what is missing.
 *   7. Restarts the dead master so it reloads its diverged AOF from the shared volume.
 *
 * Run via:  ./gradlew :experiment-shared-storage:run
 * Prerequisites: docker compose -f infra/compose/shared-storage/docker-compose.yml up -d
 */
public class SharedStorageExperiment {

    private static final Logger log = LoggerFactory.getLogger(SharedStorageExperiment.class);

    private static final List<String> SENTINELS = List.of(
            "localhost:26379",
            "localhost:26380",
            "localhost:26381"
    );

    /** Maps the host port a Redis node announces to its Docker container name. */
    private static final java.util.Map<Integer, String> PORT_TO_CONTAINER = java.util.Map.of(
            6379, "redis-master",
            6380, "redis-replica1",
            6381, "redis-replica2"
    );

    /** Keys written with replicas paused — guaranteed never to reach replicas. */
    private static final int UNREPLICATED_BATCH = 300;

    private static final int FAILOVER_WAIT_SECONDS = 30;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=============================================================");
        System.out.println("  Redis Sentinel Lab — SHARED STORAGE EXPERIMENT");
        System.out.println("=============================================================");
        System.out.println();

        assertClusterReachable();

        try (RedisLabClient client = new RedisLabClient(SENTINELS)) {

            // ── Step 0: flush stale data ──
            log.info("[0/7] Flushing stale data — shared volume persists keys across restarts");
            client.flushAll();
            Thread.sleep(500); // let FLUSHALL propagate through replication

            // ── Step 1: discover topology + write baseline ──
            String masterBefore = client.currentMasterAddress();
            int masterPort = Integer.parseInt(masterBefore.split(":")[1]);
            String masterContainer = PORT_TO_CONTAINER.getOrDefault(masterPort, "redis-master");
            List<String> replicaContainers = PORT_TO_CONTAINER.entrySet().stream()
                    .filter(e -> e.getKey() != masterPort)
                    .map(java.util.Map.Entry::getValue)
                    .collect(java.util.stream.Collectors.toList());

            log.info("[1/7] Topology  →  master: {}  |  replicas: {}",
                    masterContainer, String.join(", ", replicaContainers));
            log.info("[1/7] Writing {} baseline keys — replication live, all nodes receive them",
                    DatasetFixture.KEY_COUNT);
            Map<String, String> baseline = DatasetFixture.writeDataset(client);

            // ── Step 2: sever the replication connection on both replicas ──
            // REPLICAOF NO ONE tears down the TCP replication socket; the OS has nothing
            // to buffer subsequent master writes into on the replica side.
            log.info("[2/7] Severing replication — replicas must have no TCP socket to drain buffered writes:");
            for (String replica : replicaContainers) {
                log.info("      {}  →  REPLICAOF NO ONE", replica);
                dockerExec(replica, "redis-cli", "REPLICAOF", "NO", "ONE");
            }
            log.info("      Both replicas now standalone with {} keys — blind to any further master writes",
                    baseline.size());

            // ── Step 3: write unreplicated batch ──
            log.info("[3/7] Writing {} unreplicated keys to {} — master ACKs each write to the client,",
                    UNREPLICATED_BATCH, masterContainer);
            log.info("      but {} have no replication socket — these writes CANNOT reach them",
                    String.join(" or ", replicaContainers));
            Map<String, String> unreplicated = new LinkedHashMap<>();
            for (int i = 0; i < UNREPLICATED_BATCH; i++) {
                String k = "unreplicated:" + i;
                String v = "lost-value-" + i;
                client.write(k, v);
                unreplicated.put(k, v);
            }
            log.info("      Done — {} writes ACK'd, visible only in {}'s memory and AOF",
                    UNREPLICATED_BATCH, masterContainer);

            // ── Step 4: kill master ──
            log.info("[4/7] SIGKILL  →  {}  (no flush, no graceful handoff)", masterContainer);
            log.info("      {} in-memory writes die here — unrecoverable", UNREPLICATED_BATCH);
            docker("kill", masterContainer);

            // ── Step 5: wait for Sentinel election ──
            log.info("[5/7] Waiting for Sentinel to elect a new master (up to {}s)...", FAILOVER_WAIT_SECONDS);
            String masterAfter = client.waitForNewMaster(masterBefore, FAILOVER_WAIT_SECONDS);
            int newMasterPort = Integer.parseInt(masterAfter.split(":")[1]);
            String newMasterContainer = PORT_TO_CONTAINER.getOrDefault(newMasterPort, "redis-master");
            log.info("      New master elected: {}  —  promoted with {} keys, missing the {} unreplicated writes",
                    newMasterContainer, baseline.size(), UNREPLICATED_BATCH);

            // ── Step 6: corruption report ──
            log.info("[6/7] Verifying data integrity on {} — reading back all {} expected keys...",
                    newMasterContainer, baseline.size() + UNREPLICATED_BATCH);
            Map<String, String> allExpected = new LinkedHashMap<>(baseline);
            allExpected.putAll(unreplicated);

            CorruptionReport report = CorruptionReport.build(client, allExpected, newMasterContainer);
            report.printSummary();

            // ── Step 7: restart old master ──
            log.info("[7/7] Restarting {} — it will reload the shared-volume AOF ({} keys on disk),",
                    masterContainer, baseline.size() + UNREPLICATED_BATCH);
            log.info("      then Sentinel forces it to sync from {} — permanently discarding the {} writes",
                    newMasterContainer, UNREPLICATED_BATCH);
            docker("start", masterContainer);

            System.exit(report.isCorrupted() ? 1 : 0);
        }
    }

    // On Windows, docker.exe is not always on the JVM's PATH; use the full path.
    private static final String DOCKER = locateDocker();

    private static String locateDocker() {
        for (String candidate : List.of(
                "C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe",
                "/usr/bin/docker", "/usr/local/bin/docker")) {
            if (new java.io.File(candidate).canExecute()) return candidate;
        }
        return "docker"; // fallback — hope it's on PATH
    }

    /** Runs docker exec <container> <innerArgs...> synchronously. */
    private static void dockerExec(String container, String... innerArgs) {
        String[] cmd = new String[innerArgs.length + 3];
        cmd[0] = DOCKER; cmd[1] = "exec"; cmd[2] = container;
        System.arraycopy(innerArgs, 0, cmd, 3, innerArgs.length);
        run(cmd, "exec " + container);
    }

    /** Runs a docker command synchronously and logs output at DEBUG. */
    private static void docker(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = DOCKER;
        System.arraycopy(args, 0, cmd, 1, args.length);
        run(cmd, args[0]);
    }

    private static void run(String[] cmd, String label) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            int exit = p.waitFor();
            if (!out.isBlank()) log.debug("docker {}: {} (exit={})", label, out, exit);
        } catch (IOException e) {
            log.warn("docker {} IO error: {}", label, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("docker {} interrupted", label);
        }
    }

    private static void assertClusterReachable() {
        log.info("Checking Sentinel connectivity on {}...", SENTINELS);
        try (RedisLabClient probe = new RedisLabClient(SENTINELS)) {
            if (!probe.isSentinelReachable()) {
                System.err.println("ERROR: Cannot reach any Sentinel at " + SENTINELS);
                System.err.println("Start: docker compose -f infra/compose/shared-storage/docker-compose.yml up -d");
                System.exit(2);
            }
        }
        log.info("Sentinel cluster is reachable.");
    }
}
