# Redis Sentinel Lab

## Project Goal

This lab proves, with reproducible, automated evidence, that Redis requires exclusive persistent storage per instance. It orchestrates a three-node Redis Sentinel cluster via Docker Compose, drives a deterministic 1000-key read/write workload through Jedis, triggers a controlled Sentinel failover, and then verifies data integrity against the pre-failover snapshot — producing a concrete corruption report when storage is shared across nodes versus a clean result when each node owns its own isolated volume.

---

## Prerequisites

| Tool | Minimum version |
|------|----------------|
| Docker | 24.x |
| Docker Compose | v2 (the `docker compose` plugin) |
| Java | 21 |
| Gradle | 8.x (or use the included wrapper `gradlew`) |

---

## Running on Windows

- All commands in this README are written for Windows and work in PowerShell, CMD, or Git Bash
- Docker Desktop for Windows must be running before starting any experiment
- The Makefile targets are not available on Windows natively — run the three commands for each experiment manually as shown in the "Running the Experiments" section below
- If using Git Bash, `./gradlew` also works as an alternative to `gradlew`

---

## Running the Experiments

### Experiment 1 — Shared Storage (expects corruption)

```bash
# 1. Start the shared-storage cluster (all nodes mount the same volume)
docker compose -f infra/compose/shared-storage/docker-compose.yml up -d

# 2. Wait a few seconds for Sentinels to elect a master, then run the experiment
gradlew :experiment-shared-storage:run

# 3. Tear down and clean volumes when done
docker compose -f infra/compose/shared-storage/docker-compose.yml down -v
```

### Experiment 2 — Isolated Storage (expects clean result)

```bash
# 1. Start the isolated-storage cluster (each node has its own volume)
docker compose -f infra/compose/isolated-storage/docker-compose.yml up -d

# 2. Wait a few seconds, then run
gradlew :experiment-isolated-storage:run

# 3. Tear down and clean volumes when done
docker compose -f infra/compose/isolated-storage/docker-compose.yml down -v
```

> **Tip:** You can monitor Sentinel logs with
> `docker compose -f infra/compose/<variant>/docker-compose.yml logs -f sentinel1`

---

## What to Expect

### Shared Storage — Corruption Report

```
╔══════════════════════════════════════════════════════════════╗
║                  CORRUPTION REPORT                           ║
╠══════════════════════════════════════════════════════════════╣
║  Total keys written    : 1000                                ║
║  Missing keys          : 312                                 ║
║  Keys with wrong value : 47                                  ║
╠══════════════════════════════════════════════════════════════╣
║  AOF check result      : exit=1 | AOF is not valid ...       ║
╠══════════════════════════════════════════════════════════════╣
║  VERDICT: *** DATA CORRUPTION DETECTED ***                   ║
╚══════════════════════════════════════════════════════════════╝
```

The exact numbers vary per run; the point is that corruption is consistently
present and reproducible.

### Isolated Storage — Clean Report

```
╔══════════════════════════════════════════════════════════════╗
║                  CORRUPTION REPORT                           ║
╠══════════════════════════════════════════════════════════════╣
║  Total keys written    : 1000                                ║
║  Missing keys          : 0                                   ║
║  Keys with wrong value : 0                                   ║
╠══════════════════════════════════════════════════════════════╣
║  AOF check result      : exit=0 | AOF is valid               ║
╠══════════════════════════════════════════════════════════════╣
║  VERDICT: Data integrity OK — no corruption found            ║
╚══════════════════════════════════════════════════════════════╝
```

---

## Why Shared Storage Breaks Redis

Redis uses two complementary persistence mechanisms, and both are designed on the assumption that a single server process owns the storage directory exclusively:

### 1. RDB Snapshot Collision

When a Redis master performs a `BGSAVE`, it forks a child process that atomically writes a `dump.rdb` file. If multiple Redis instances share the same directory, a replica performing its own `BGSAVE` (triggered by the replication handshake) will overwrite the master's `dump.rdb` in-place, or vice versa. After a failover, the newly promoted master loads whichever `dump.rdb` last won the race — which may represent a stale or partially-written dataset.

### 2. AOF Interleaving

The Append-Only File (`appendonly.aof`) is a sequential log of every write command. With shared storage, master and replica processes both open the same `appendonly.aof` for writing. Because Redis does not use file locking across processes, their write buffers interleave at the OS level, producing a log with interleaved, out-of-order commands. `redis-check-aof` will report this file as invalid, and any restart or replay will produce garbage data.

### 3. Stale Reads After Failover

When a replica is promoted to master, Redis normally discards its replication buffer and begins serving its own in-memory state. With shared storage the new master may reload persistence files that were written by the old master after the replica last synced — effectively rolling back to an older dataset, making keys that were written just before the failover invisible.

### The Fix

Each Redis instance **must** have its own dedicated, exclusive volume. This is what `isolated-storage/docker-compose.yml` demonstrates: `redis-master-data`, `redis-replica1-data`, and `redis-replica2-data` are separate volumes. Replication keeps the in-memory datasets in sync; persistence is a private concern of each node. After failover the promoted replica's own clean AOF/RDB is loaded, data integrity is preserved, and the lab reports zero corruption.

---

## Project Layout

```
redis-sentinel-lab/
├── build.gradle                        # Root Gradle build (Java 21, common deps)
├── settings.gradle                     # Module declarations
├── gradle/libs.versions.toml           # Version catalog (Jedis, SLF4J, Logback)
├── infra/
│   ├── sentinel.conf                   # Shared Sentinel configuration
│   └── compose/
│       ├── shared-storage/docker-compose.yml
│       └── isolated-storage/docker-compose.yml
├── experiment-core/                    # Shared library (RedisLabClient, DatasetFixture, CorruptionReport)
├── experiment-shared-storage/          # Runnable experiment: shared volume topology
└── experiment-isolated-storage/        # Runnable experiment: per-node volume topology
```
