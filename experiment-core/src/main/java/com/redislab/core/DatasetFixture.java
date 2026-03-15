package com.redislab.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes 1000 deterministic key-value pairs into Redis and returns the expected map
 * so callers can diff it against what is actually in Redis after a failover.
 */
public class DatasetFixture {

    private static final Logger log = LoggerFactory.getLogger(DatasetFixture.class);

    public static final int KEY_COUNT = 1000;
    public static final String KEY_PREFIX = "lab:key:";
    public static final String VALUE_PREFIX = "value-";

    public static String key(int i) {
        return KEY_PREFIX + i;
    }

    public static String value(int i) {
        return VALUE_PREFIX + i;
    }

    /**
     * Writes all 1000 keys to Redis via the given client.
     *
     * @return the expected map of key → value for later diffing
     */
    public static Map<String, String> writeDataset(RedisLabClient client) {
        log.debug("Writing {} keys to Redis...", KEY_COUNT);
        Map<String, String> expected = new LinkedHashMap<>(KEY_COUNT);
        for (int i = 0; i < KEY_COUNT; i++) {
            String k = key(i);
            String v = value(i);
            client.write(k, v);
            expected.put(k, v);
        }
        log.debug("Dataset write complete.");
        return expected;
    }
}
