package com.redislab.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Value class capturing the result of a data-integrity check after a Sentinel failover.
 */
public class CorruptionReport {

    private static final Logger log = LoggerFactory.getLogger(CorruptionReport.class);

    private final int totalKeysWritten;
    private final List<String> missingKeys;
    private final Map<String, String[]> wrongValueKeys; // key → [expected, actual]
    private final String aofCheckResult;
    private final boolean corrupted;

    private CorruptionReport(
            int totalKeysWritten,
            List<String> missingKeys,
            Map<String, String[]> wrongValueKeys,
            String aofCheckResult,
            boolean corrupted) {
        this.totalKeysWritten = totalKeysWritten;
        this.missingKeys = missingKeys;
        this.wrongValueKeys = wrongValueKeys;
        this.aofCheckResult = aofCheckResult;
        this.corrupted = corrupted;
    }

    /**
     * Builds a CorruptionReport by diffing the expected map against what is currently in Redis,
     * and optionally running redis-check-aof inside the named Docker container.
     *
     * @param client          live RedisLabClient to read back values
     * @param expectedDataset the map returned by DatasetFixture.writeDataset()
     * @param dataContainerName Docker container name to exec redis-check-aof in (e.g. "redis-master")
     */
    public static CorruptionReport build(
            RedisLabClient client,
            Map<String, String> expectedDataset,
            String dataContainerName) {

        log.debug("Building corruption report — reading back {} keys...", expectedDataset.size());

        List<String> missingKeys = new ArrayList<>();
        Map<String, String[]> wrongValueKeys = new java.util.LinkedHashMap<>();

        for (Map.Entry<String, String> entry : expectedDataset.entrySet()) {
            String key = entry.getKey();
            String expected = entry.getValue();
            String actual = client.read(key);
            if (actual == null) {
                missingKeys.add(key);
            } else if (!actual.equals(expected)) {
                wrongValueKeys.put(key, new String[]{expected, actual});
            }
        }

        String aofCheckResult = runAofCheck(dataContainerName);
        boolean aofCorrupted = aofCheckResult != null
                && (aofCheckResult.contains("error") || aofCheckResult.startsWith("exit=1"));
        boolean corrupted = !missingKeys.isEmpty() || !wrongValueKeys.isEmpty() || aofCorrupted;

        return new CorruptionReport(
                expectedDataset.size(),
                missingKeys,
                wrongValueKeys,
                aofCheckResult,
                corrupted);
    }

    private static final String DOCKER = locateDocker();

    private static String locateDocker() {
        for (String c : java.util.List.of(
                "C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe",
                "/usr/bin/docker", "/usr/local/bin/docker")) {
            if (new java.io.File(c).canExecute()) return c;
        }
        return "docker";
    }

    private static String runAofCheck(String containerName) {
        if (containerName == null || containerName.isBlank()) {
            return "AOF check skipped — no container name provided";
        }
        try {
            log.debug("Running redis-check-aof inside container '{}'...", containerName);
            ProcessBuilder pb = new ProcessBuilder(
                    DOCKER, "exec", containerName,
                    "redis-check-aof", "/data/appendonlydir/appendonly.aof.manifest");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            int exitCode = proc.waitFor();
            log.debug("redis-check-aof exit code: {}", exitCode);
            return "exit=" + exitCode + " | " + output.trim();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return "AOF check failed: " + e.getMessage();
        }
    }

    public void printSummary() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                  CORRUPTION REPORT                          ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Total keys written    : %-36d║%n", totalKeysWritten);
        System.out.printf("║  Missing keys          : %-36d║%n", missingKeys.size());
        System.out.printf("║  Keys with wrong value : %-36d║%n", wrongValueKeys.size());
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  AOF check result      : %-36s║%n", truncate(aofCheckResult, 36));
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  %-60s║%n", corrupted
                ? "VERDICT: *** DATA CORRUPTION DETECTED ***"
                : "VERDICT: Data integrity OK — no corruption found");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        if (!missingKeys.isEmpty()) {
            System.out.println("\nMissing keys (first 20):");
            missingKeys.stream().limit(20).forEach(k -> System.out.println("  - " + k));
            if (missingKeys.size() > 20) {
                System.out.println("  ... and " + (missingKeys.size() - 20) + " more");
            }
        }

        if (!wrongValueKeys.isEmpty()) {
            System.out.println("\nKeys with wrong values (first 20):");
            wrongValueKeys.entrySet().stream().limit(20).forEach(e -> {
                String[] vals = e.getValue();
                System.out.printf("  - %s  expected='%s'  actual='%s'%n", e.getKey(), vals[0], vals[1]);
            });
            if (wrongValueKeys.size() > 20) {
                System.out.println("  ... and " + (wrongValueKeys.size() - 20) + " more");
            }
        }

        if (aofCheckResult != null && !aofCheckResult.isBlank()) {
            System.out.println("\nAOF check detail:");
            System.out.println("  " + aofCheckResult);
        }
        System.out.println();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "N/A";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    // Accessors for programmatic use
    public boolean isCorrupted() { return corrupted; }
    public int getTotalKeysWritten() { return totalKeysWritten; }
    public List<String> getMissingKeys() { return missingKeys; }
    public Map<String, String[]> getWrongValueKeys() { return wrongValueKeys; }
    public String getAofCheckResult() { return aofCheckResult; }
}
