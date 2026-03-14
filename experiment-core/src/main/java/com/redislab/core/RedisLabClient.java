package com.redislab.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisSentinelPool;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RedisLabClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisLabClient.class);

    private static final String MASTER_NAME = "mymaster";

    private final JedisSentinelPool pool;
    private final List<String> sentinelHosts;

    /**
     * @param sentinelHosts list of "host:port" strings, e.g. ["localhost:26379", "localhost:26380", "localhost:26381"]
     */
    public RedisLabClient(List<String> sentinelHosts) {
        this.sentinelHosts = sentinelHosts;
        Set<String> sentinels = new HashSet<>(sentinelHosts);
        this.pool = new JedisSentinelPool(MASTER_NAME, sentinels);
        log.info("JedisSentinelPool created for master '{}' via sentinels {}", MASTER_NAME, sentinels);
    }

    public void write(String key, String value) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(key, value);
        }
    }

    public String read(String key) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(key);
        }
    }

    /**
     * Sends SENTINEL failover mymaster to the first reachable sentinel.
     */
    public void triggerFailover() {
        log.info("Triggering SENTINEL failover on '{}'", MASTER_NAME);
        for (String sentinelAddr : sentinelHosts) {
            String[] parts = sentinelAddr.split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);
            try (Jedis sentinel = new Jedis(host, port)) {
                sentinel.sentinelFailover(MASTER_NAME);
                log.info("Failover triggered via sentinel {}:{}", host, port);
                return;
            } catch (Exception e) {
                log.warn("Could not trigger failover via {}:{} — {}", host, port, e.getMessage());
            }
        }
        throw new IllegalStateException("No reachable sentinel found to trigger failover");
    }

    /**
     * Polls until the Sentinel reports a master different from originalMasterAddr, or until timeout.
     *
     * @param originalMasterAddr "host:port" of the master before failover, or null to skip identity check
     * @param timeoutSeconds     maximum seconds to wait
     * @return the new master address as "host:port"
     */
    public String waitForNewMaster(String originalMasterAddr, int timeoutSeconds) throws InterruptedException {
        log.info("Waiting up to {}s for new master to be promoted (original: {})", timeoutSeconds, originalMasterAddr);
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;

        while (System.currentTimeMillis() < deadline) {
            for (String sentinelAddr : sentinelHosts) {
                String[] parts = sentinelAddr.split(":");
                String host = parts[0];
                int port = Integer.parseInt(parts[1]);
                try (Jedis sentinel = new Jedis(host, port)) {
                    List<String> masterInfo = sentinel.sentinelGetMasterAddrByName(MASTER_NAME);
                    if (masterInfo != null && masterInfo.size() == 2) {
                        String currentMaster = masterInfo.get(0) + ":" + masterInfo.get(1);
                        if (originalMasterAddr == null || !currentMaster.equals(originalMasterAddr)) {
                            log.info("New master promoted: {}", currentMaster);
                            return currentMaster;
                        }
                    }
                } catch (Exception e) {
                    log.debug("Sentinel {}:{} not reachable during poll: {}", host, port, e.getMessage());
                }
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(
                "Timed out after " + timeoutSeconds + "s waiting for new master to be promoted");
    }

    /**
     * Resolves the current master address from Sentinel as "host:port".
     */
    public String currentMasterAddress() {
        for (String sentinelAddr : sentinelHosts) {
            String[] parts = sentinelAddr.split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);
            try (Jedis sentinel = new Jedis(host, port)) {
                List<String> masterInfo = sentinel.sentinelGetMasterAddrByName(MASTER_NAME);
                if (masterInfo != null && masterInfo.size() == 2) {
                    return masterInfo.get(0) + ":" + masterInfo.get(1);
                }
            } catch (Exception e) {
                log.debug("Could not query sentinel {}:{}: {}", host, port, e.getMessage());
            }
        }
        throw new IllegalStateException("No sentinel returned master address");
    }

    /**
     * Pings a single sentinel to verify the cluster is reachable.
     */
    public boolean isSentinelReachable() {
        for (String sentinelAddr : sentinelHosts) {
            String[] parts = sentinelAddr.split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);
            try (Jedis sentinel = new Jedis(host, port)) {
                String pong = sentinel.ping();
                if ("PONG".equalsIgnoreCase(pong)) {
                    return true;
                }
            } catch (Exception e) {
                log.debug("Sentinel {}:{} ping failed: {}", host, port, e.getMessage());
            }
        }
        return false;
    }

    @Override
    public void close() {
        pool.close();
    }
}
