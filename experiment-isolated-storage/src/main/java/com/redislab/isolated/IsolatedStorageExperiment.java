package com.redislab.isolated;

import com.redislab.core.CorruptionReport;
import com.redislab.core.DatasetFixture;
import com.redislab.core.RedisLabClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Experiment: Isolated Storage
 *
 * Each Redis node (master + 2 replicas) mounts its own dedicated Docker volume.
 * After a Sentinel failover the promoted replica has its own clean AOF/RDB state
 * that was kept in sync via Redis replication — no file collisions, no stale reads.
 * This experiment should produce a clean (no-corruption) report.
 *
 * Run via:  ./gradlew :experiment-isolated-storage:run
 * Prerequisites: docker compose -f infra/compose/isolated-storage/docker-compose.yml up -d
 */
public class IsolatedStorageExperiment {

    private static final Logger log = LoggerFactory.getLogger(IsolatedStorageExperiment.class);

    private static final List<String> SENTINELS = List.of(
            "localhost:26379",
            "localhost:26380",
            "localhost:26381"
    );

    // After failover, the new master is one of the replicas; redis-master container may still
    // be running (as a replica of the new master). We check that container's isolated volume.
    private static final String AOF_CONTAINER = "redis-master";

    private static final int FAILOVER_WAIT_SECONDS = 30;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=============================================================");
        System.out.println("  Redis Sentinel Lab — ISOLATED STORAGE EXPERIMENT");
        System.out.println("=============================================================");
        System.out.println();

        // Step 1: Assert Docker Compose is up
        assertClusterReachable();

        try (RedisLabClient client = new RedisLabClient(SENTINELS)) {

            // Step 2: Capture master address before failover
            String masterBefore = client.currentMasterAddress();
            log.info("Current master before failover: {}", masterBefore);

            // Step 3: Write dataset
            Map<String, String> expected = DatasetFixture.writeDataset(client);
            log.info("Wrote {} keys. Sample: {}={}", expected.size(),
                    DatasetFixture.key(0), client.read(DatasetFixture.key(0)));

            // Step 4: Trigger failover
            client.triggerFailover();

            // Step 5: Wait for new master
            String masterAfter = client.waitForNewMaster(masterBefore, FAILOVER_WAIT_SECONDS);
            log.info("Master after failover: {}", masterAfter);

            // Step 6: Build and print corruption report
            CorruptionReport report = CorruptionReport.build(client, expected, AOF_CONTAINER);
            report.printSummary();

            System.exit(report.isCorrupted() ? 1 : 0);
        }
    }

    private static void assertClusterReachable() {
        log.info("Checking Sentinel connectivity on {}...", SENTINELS);
        try (RedisLabClient probe = new RedisLabClient(SENTINELS)) {
            if (!probe.isSentinelReachable()) {
                System.err.println();
                System.err.println("ERROR: Cannot reach any Sentinel at " + SENTINELS);
                System.err.println();
                System.err.println("Start the cluster first:");
                System.err.println("  docker compose -f infra/compose/isolated-storage/docker-compose.yml up -d");
                System.err.println();
                System.exit(2);
            }
        }
        log.info("Sentinel cluster is reachable.");
    }
}
