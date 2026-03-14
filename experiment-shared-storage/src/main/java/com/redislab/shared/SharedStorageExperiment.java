package com.redislab.shared;

import com.redislab.core.CorruptionReport;
import com.redislab.core.DatasetFixture;
import com.redislab.core.RedisLabClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Experiment: Shared Storage
 *
 * All three Redis nodes (master + 2 replicas) mount the SAME Docker volume.
 * After a Sentinel failover this leads to RDB/AOF collisions and data corruption
 * because a promoted replica will find the master's persisted files in its data
 * directory, overwriting or interleaving its own diverged state.
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

    // The data container to run redis-check-aof against.
    // After failover the new master may be any of the replicas; we check the shared volume
    // by examining any container that holds it — redis-master is always a good proxy.
    private static final String AOF_CONTAINER = "redis-master";

    private static final int FAILOVER_WAIT_SECONDS = 30;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=============================================================");
        System.out.println("  Redis Sentinel Lab — SHARED STORAGE EXPERIMENT");
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
                System.err.println("  docker compose -f infra/compose/shared-storage/docker-compose.yml up -d");
                System.err.println();
                System.exit(2);
            }
        }
        log.info("Sentinel cluster is reachable.");
    }
}
