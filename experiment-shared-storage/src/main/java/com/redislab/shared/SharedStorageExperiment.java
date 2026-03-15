package com.redislab.shared;

import com.redislab.core.DatasetFixture;
import com.redislab.core.RedisLabClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

/**
 * Experiment: Shared Storage — AOF Manifest Race Condition
 *
 * All three Redis nodes (master + 2 replicas) mount the SAME Docker volume.
 *
 * Redis 7.x multi-part AOF uses an epoch-based layout in /data/appendonlydir/:
 *   appendonly.aof.manifest       — index that lists which epoch files to load
 *   appendonly.aof.N.base.rdb     — full snapshot at epoch N
 *   appendonly.aof.N.incr.aof     — incremental writes at epoch N
 *
 * When BGREWRITEAOF runs, Redis atomically renames a temp manifest over
 * appendonly.aof.manifest via rename(2). With shared storage, all three nodes
 * target the same path — last rename wins, all other epochs are orphaned.
 *
 * The experiment:
 *   1. Writes a fully replicated baseline dataset.
 *   2. Severs replication so each node is standalone.
 *   3. Writes exclusive keys to the MASTER only — only the master holds this data.
 *   4. Triggers BGREWRITEAOF on the master and waits for completion — master's
 *      epoch (including its exclusive keys) is now safely written to disk.
 *   5. Then triggers BGREWRITEAOF on both replicas simultaneously — their rename()
 *      calls target the same manifest path, overwriting the master's entry.
 *   6. Stops all nodes, then restarts them — all load from the surviving manifest,
 *      which now references a replica's epoch, not the master's.
 *   7. Reports that the master's exclusive keys are gone.
 *
 * Run via:  gradlew :experiment-shared-storage:run
 * Prerequisites: docker compose -f infra/compose/shared-storage/docker-compose.yml up -d
 */
public class SharedStorageExperiment {

    private static final Logger log = LoggerFactory.getLogger(SharedStorageExperiment.class);

    private static final List<String> SENTINELS = List.of(
            "localhost:26379", "localhost:26380", "localhost:26381");

    /** Maps host port to Docker container name (fixed by docker-compose port bindings). */
    private static final Map<Integer, String> PORT_TO_CONTAINER = Map.of(
            6379, "redis-master",
            6380, "redis-replica1",
            6381, "redis-replica2");

    /** Keys written exclusively to the master after replication is severed. */
    private static final int MASTER_EXCLUSIVE_KEYS = 100;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=============================================================");
        System.out.println("  Redis Sentinel Lab — SHARED STORAGE EXPERIMENT");
        System.out.println("  (AOF Manifest Race Condition)");
        System.out.println("=============================================================");
        System.out.println();

        assertClusterReachable();

        int masterPort = 0;
        String masterContainer = "";
        List<Integer> replicaPorts = new ArrayList<>();
        List<String> replicaContainers = new ArrayList<>();
        Map<String, String> masterExclusiveKeys = new LinkedHashMap<>();

        // ──────────────────────────────────────────────────────────────────────
        // Phase 1: prepare cluster state (steps 0–3)
        // ──────────────────────────────────────────────────────────────────────
        try (RedisLabClient client = new RedisLabClient(SENTINELS)) {

            // ── Step 0: flush stale data ──
            log.info("[0/7] Flushing stale data — shared volume persists keys across restarts");
            client.flushAll();
            Thread.sleep(500);

            // ── Step 1: discover topology + write replicated baseline ──
            String masterAddr = client.currentMasterAddress();
            masterPort = Integer.parseInt(masterAddr.split(":")[1]);
            masterContainer = PORT_TO_CONTAINER.getOrDefault(masterPort, "redis-master");

            final int capturedMasterPort = masterPort;
            List<Map.Entry<Integer, String>> replicas = PORT_TO_CONTAINER.entrySet().stream()
                    .filter(e -> e.getKey() != capturedMasterPort)
                    .sorted(Map.Entry.comparingByKey())
                    .collect(Collectors.toList());
            for (Map.Entry<Integer, String> e : replicas) {
                replicaPorts.add(e.getKey());
                replicaContainers.add(e.getValue());
            }

            log.info("[1/7] Topology  →  master: {}  |  replicas: {}",
                    masterContainer, String.join(", ", replicaContainers));
            log.info("[1/7] Writing {} baseline keys — replication live, all nodes receive them",
                    DatasetFixture.KEY_COUNT);
            DatasetFixture.writeDataset(client);
            log.info("      Baseline written — all 3 nodes hold an identical {} key dataset",
                    DatasetFixture.KEY_COUNT);

            // ── Step 2: sever the replication connection on both replicas ──
            log.info("[2/7] Severing replication — each node becomes standalone:");
            for (String replica : replicaContainers) {
                log.info("      {} → REPLICAOF NO ONE", replica);
                dockerExec(replica, "redis-cli", "REPLICAOF", "NO", "ONE");
            }
            Thread.sleep(300);

            // ── Step 3: write exclusive keys to the master only ──
            // These keys are NOT on the replicas. If the master's epoch survives the
            // manifest race, these keys will be readable after restart. If a replica's
            // rewrite wins the race, the master's epoch is orphaned and these keys vanish.
            log.info("[3/7] Writing {} exclusive keys directly to {} only:",
                    MASTER_EXCLUSIVE_KEYS, masterContainer);
            log.info("      Replicas have no replication socket — they cannot receive these writes.");
            log.info("      Only {}'s epoch will contain them.", masterContainer);
            masterExclusiveKeys = writeDirectly(masterPort, masterContainer, MASTER_EXCLUSIVE_KEYS);
            log.info("      {} exclusive keys written — {} total in {}'s memory",
                    MASTER_EXCLUSIVE_KEYS, DatasetFixture.KEY_COUNT + MASTER_EXCLUSIVE_KEYS, masterContainer);
        }

        // ──────────────────────────────────────────────────────────────────────
        // Phase 2: trigger rewrites in a deterministic order (steps 4–5)
        // ──────────────────────────────────────────────────────────────────────

        // ── Step 4: master's BGREWRITEAOF first — lock the master's epoch on disk ──
        log.info("[4/7] Triggering BGREWRITEAOF on {} first and waiting for completion:",
                masterContainer);
        log.info("      This writes {}'s epoch (with all {} keys) to disk.", masterContainer,
                DatasetFixture.KEY_COUNT + MASTER_EXCLUSIVE_KEYS);
        log.info("      The manifest now correctly references {}'s epoch.", masterContainer);
        triggerAndWaitBgrewriteaof(masterPort, masterContainer, 30);
        log.info("      Manifest after master's rewrite:");
        showManifest(masterContainer);

        // ── Step 5: replicas' BGREWRITEAOF — they overwrite the manifest ──
        log.info("[5/7] Now triggering BGREWRITEAOF on both replicas simultaneously:");
        log.info("      Each replica has only {} keys (no exclusive keys).", DatasetFixture.KEY_COUNT);
        log.info("      On shared storage, their rename() calls target the SAME manifest path.");
        log.info("      One replica's rename() will overwrite {}'s manifest entry.", masterContainer);
        log.info("      {}'s epoch files remain on disk — but nothing references them anymore.", masterContainer);
        triggerConcurrentBgrewriteaof(replicaPorts);
        waitForAofRewrite(replicaPorts.get(0), replicaContainers.get(0), 30);
        waitForAofRewrite(replicaPorts.get(1), replicaContainers.get(1), 30);
        log.info("      Manifest after replicas' rewrite (master's epoch is now orphaned):");
        showManifest(masterContainer);

        // ──────────────────────────────────────────────────────────────────────
        // Phase 3: stop/restart (steps 6–7) and verify
        // ──────────────────────────────────────────────────────────────────────

        // ── Step 6: stop all Redis data nodes ──
        log.info("[6/7] Stopping all Redis nodes — shared volume state frozen on disk:");
        for (String container : List.of("redis-master", "redis-replica1", "redis-replica2")) {
            log.info("      docker stop {}", container);
            docker("stop", container);
        }
        Thread.sleep(2000);

        // ── Step 7: restart — all nodes load from the surviving manifest ──
        log.info("[7/7] Restarting all nodes — each reads the SAME manifest from /data:");
        log.info("      The manifest references a replica's epoch.");
        log.info("      {}'s exclusive keys exist in its epoch files on disk,", masterContainer);
        log.info("      but the manifest no longer lists them. They are permanently orphaned.");
        for (String container : List.of("redis-master", "redis-replica1", "redis-replica2")) {
            log.info("      docker start {}", container);
            docker("start", container);
        }
        Thread.sleep(4000);

        try (RedisLabClient client = new RedisLabClient(SENTINELS)) {
            String newMaster = client.waitForNewMaster(null, 30);
            int newMasterPort = Integer.parseInt(newMaster.split(":")[1]);
            String newMasterContainer = PORT_TO_CONTAINER.getOrDefault(newMasterPort, "redis-master");
            log.info("      Sentinel elected: {}  — reading back {}'s exclusive keys...",
                    newMasterContainer, masterContainer);

            printRaceReport(client, masterContainer, newMasterContainer, masterExclusiveKeys);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Map<String, String> writeDirectly(int hostPort, String containerName, int count) {
        Map<String, String> written = new LinkedHashMap<>(count);
        try (Jedis jedis = new Jedis("localhost", hostPort)) {
            for (int i = 0; i < count; i++) {
                String k = "node-" + containerName + ":" + i;
                String v = "val-" + containerName + "-" + i;
                jedis.set(k, v);
                written.put(k, v);
            }
        }
        return written;
    }

    /** Triggers BGREWRITEAOF on the master and blocks until the rewrite completes. */
    private static void triggerAndWaitBgrewriteaof(int port, String containerName, int timeoutSeconds)
            throws InterruptedException {
        try (Jedis jedis = new Jedis("localhost", port)) {
            jedis.bgrewriteaof();
            log.info("      BGREWRITEAOF sent → {}  (port {})", containerName, port);
        } catch (Exception e) {
            log.warn("      BGREWRITEAOF on {} failed: {}", containerName, e.getMessage());
        }
        waitForAofRewrite(port, containerName, timeoutSeconds);
    }

    /** Fires BGREWRITEAOF on all replica ports simultaneously. */
    private static void triggerConcurrentBgrewriteaof(List<Integer> ports) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(ports.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();

        for (int port : ports) {
            String containerName = PORT_TO_CONTAINER.getOrDefault(port, "port-" + port);
            Thread t = new Thread(() -> {
                try (Jedis jedis = new Jedis("localhost", port)) {
                    ready.countDown();
                    go.await();
                    jedis.bgrewriteaof();
                    log.info("      BGREWRITEAOF sent → {}  (port {})", containerName, port);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.warn("      BGREWRITEAOF on {} (port {}) failed: {}", containerName, port, e.getMessage());
                }
            });
            threads.add(t);
        }

        threads.forEach(Thread::start);
        ready.await();
        go.countDown();
        for (Thread t : threads) t.join(5000);
    }

    private static void waitForAofRewrite(int port, String containerName, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            try (Jedis jedis = new Jedis("localhost", port)) {
                String info = jedis.info("persistence");
                if (info != null && info.contains("aof_rewrite_in_progress:0")) {
                    log.debug("      AOF rewrite complete on {}", containerName);
                    return;
                }
            } catch (Exception e) {
                log.debug("      Cannot poll {} yet: {}", containerName, e.getMessage());
            }
            Thread.sleep(300);
        }
        log.warn("      AOF rewrite did not complete within {}s on {}", timeoutSeconds, containerName);
    }

    private static void showManifest(String containerName) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    DOCKER, "exec", containerName,
                    "cat", "/data/appendonlydir/appendonly.aof.manifest");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String raw = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            log.info("      --- /data/appendonlydir/appendonly.aof.manifest ---");
            for (String line : raw.split("\n")) {
                if (!line.isBlank()) log.info("      {}", line.trim());
            }
            log.info("      ---------------------------------------------------");
        } catch (IOException e) {
            log.warn("      Could not read manifest: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void printRaceReport(
            RedisLabClient client,
            String masterContainer,
            String newMasterContainer,
            Map<String, String> masterExclusiveKeys) {

        int survived = 0;
        for (Map.Entry<String, String> kv : masterExclusiveKeys.entrySet()) {
            String actual = client.read(kv.getKey());
            if (kv.getValue().equals(actual)) survived++;
        }
        int orphaned = masterExclusiveKeys.size() - survived;
        boolean corrupted = orphaned > 0;

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║         AOF MANIFEST RACE CONDITION REPORT                  ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf( "║  Loaded by: %-49s║%n", newMasterContainer + "  (Sentinel master after restart)");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  Node                 Written    Survived   Orphaned  Fate  ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf( "║  %-21s %-10d %-10d %-10d %-5s║%n",
                masterContainer, masterExclusiveKeys.size(), survived, orphaned,
                corrupted ? "LOST  " : "OK    ");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        if (corrupted) {
            System.out.printf("║  %-60s║%n",
                    String.format("VERDICT: *** %d master keys orphaned by manifest overwrite ***", orphaned));
        } else {
            System.out.printf("║  %-60s║%n", "VERDICT: Data integrity OK — no manifest race detected");
        }
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        System.exit(corrupted ? 1 : 0);
    }

    // ── Docker utilities ─────────────────────────────────────────────────────

    private static final String DOCKER = locateDocker();

    private static String locateDocker() {
        for (String candidate : List.of(
                "C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe",
                "/usr/bin/docker", "/usr/local/bin/docker")) {
            if (new File(candidate).canExecute()) return candidate;
        }
        return "docker";
    }

    private static void dockerExec(String container, String... innerArgs) {
        String[] cmd = new String[innerArgs.length + 3];
        cmd[0] = DOCKER; cmd[1] = "exec"; cmd[2] = container;
        System.arraycopy(innerArgs, 0, cmd, 3, innerArgs.length);
        run(cmd, "exec " + container);
    }

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
