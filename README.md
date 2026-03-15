# Redis Sentinel Lab

## Project Goal

This lab targets the specific case where **Redis is the source of truth** — no primary database exists to reload from after a failure. In that role, Redis requires exclusive persistent storage per instance.

The lab proves this with a reproducible, automated scenario: it orchestrates a three-node Redis Sentinel cluster via Docker Compose, writes node-specific datasets to each instance, then triggers a concurrent AOF rewrite across all nodes. On shared storage, all three nodes race to overwrite the same manifest file — last rename wins, the other nodes' epoch files are permanently orphaned, and their data is silently discarded. The lab reports exactly which nodes' data survived and which were lost.

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

### Machine-specific IP configuration

The sentinel configs and Docker Compose files contain a hardcoded LAN IP (`192.168.68.105`).
This is a known limitation: on Windows with Docker Desktop there is no single address that
resolves correctly from both inside a container and from the Windows host without manual
configuration. `host.docker.internal` resolves to different addresses on each side, and
Docker internal IPs (`172.x.x.x`) are unreachable from the Windows host entirely.

**To run on a different machine**, replace `192.168.68.105` with your machine's LAN IP in:
- `infra/sentinel1.conf`, `infra/sentinel2.conf`, `infra/sentinel3.conf`
- `infra/compose/shared-storage/docker-compose.yml`

Your LAN IP is the IPv4 address shown in `ipconfig` under your active network adapter
(typically Wi-Fi or Ethernet), **not** `127.0.0.1` and not the Docker virtual adapter.

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

> **Can't run this locally?** Pre-run logs are captured in
> [`docs/sample-output/shared-storage-experiment.log`](docs/sample-output/shared-storage-experiment.log) and
> [`docs/sample-output/isolated-storage-experiment.log`](docs/sample-output/isolated-storage-experiment.log).

### Shared Storage — Manifest Race Report

```
╔══════════════════════════════════════════════════════════════╗
║         AOF MANIFEST RACE CONDITION REPORT                  ║
╠══════════════════════════════════════════════════════════════╣
║  Loaded by: redis-replica1  (Sentinel master after restart)  ║
╠══════════════════════════════════════════════════════════════╣
║  Node                 Written    Survived   Orphaned  Fate  ║
╠══════════════════════════════════════════════════════════════╣
║  redis-master         100        0          100       LOST  ║
╠══════════════════════════════════════════════════════════════╣
║  VERDICT: *** 100 master keys orphaned by manifest overwrite ║
╚══════════════════════════════════════════════════════════════╝
```

100 exclusive keys are written to the master only, then the master's
`BGREWRITEAOF` runs and completes — its epoch (including those keys) is
correctly written to disk. The replicas then run `BGREWRITEAOF` and their
`rename(temp_manifest → appendonly.aof.manifest)` calls overwrite the master's
manifest entry on the shared volume. The master's epoch files remain on disk
but are no longer referenced. On restart, all nodes load a replica's epoch
and the master's 100 exclusive keys are silently gone.

### Isolated Storage — Clean Report

```
╔══════════════════════════════════════════════════════════════╗
║         AOF MANIFEST RACE CONDITION REPORT                  ║
╠══════════════════════════════════════════════════════════════╣
║  Loaded by: redis-master  (Sentinel master after restart)   ║
╠══════════════════════════════════════════════════════════════╣
║  Node                 Written    Survived   Orphaned  Fate  ║
╠══════════════════════════════════════════════════════════════╣
║  redis-master         100        100        0         OK    ║
╠══════════════════════════════════════════════════════════════╣
║  VERDICT: Data integrity OK — no manifest race detected      ║
╚══════════════════════════════════════════════════════════════╝
```

The same rewrite sequence runs but each node's `rename()` targets its own
private `/data` directory. The master's manifest stays at epoch 2 throughout —
the replica rewrites (epoch 3) are physically incapable of reaching it.
On restart the master loads its own epoch and all 100 exclusive keys survive.

---

## Why Shared Storage Breaks Redis

> **Scope note:** the failure mode below matters specifically when Redis is your **source of truth** — no backing database exists to reload from. If Redis is used purely as a cache (cold-start = cache miss = reload from the primary store), shared storage is less dangerous: you risk a cold start, not permanent data loss. This lab targets the source-of-truth case.

Redis 7.x multi-part AOF stores persistence in an epoch-based layout:

```
/data/appendonlydir/
  appendonly.aof.manifest        ← index: which epoch files to load
  appendonly.aof.1.base.rdb      ← full snapshot at epoch 1
  appendonly.aof.1.incr.aof      ← incremental writes at epoch 1
```

Both files are designed on the assumption that **a single server process owns `/data` exclusively**.

### The Manifest Race Condition

When `BGREWRITEAOF` runs (triggered automatically on replication promotion, or manually), Redis:

1. Forks a child that serialises the full dataset into a new epoch file.
2. Atomically renames a temp manifest file to `appendonly.aof.manifest` via `rename(2)`.

With shared storage, all three nodes target the same path. Three concurrent `rename()` calls on the same inode is a standard last-writer-wins race: the last process to call `rename()` overwrites what the others wrote. The surviving manifest references **only one node's epoch files**. All other epochs remain on disk but are orphaned — the manifest no longer lists them, so Redis ignores them on startup.

This means:

- All nodes restart and load identical data from the single surviving epoch.
- The other nodes' exclusive datasets are silently discarded.
- The orphaned epoch files are not deleted — they accumulate on disk — but Redis cannot use them without manual manifest reconstruction.
- **There is no error log.** Redis does not warn that its epoch was overwritten. The data loss surfaces only when a query returns nothing.

### Comparison: Isolated Storage

Each Redis instance has its own dedicated volume. Three concurrent `BGREWRITEAOF` calls each write to their own isolated `/data/appendonlydir/`. There is no shared manifest to race over. On restart each node loads its own epoch, all exclusive datasets survive, and the lab reports zero orphaned keys.

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
