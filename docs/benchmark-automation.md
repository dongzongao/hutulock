# Automated Performance Benchmark

## Overview

HutuLock uses GitHub Actions to automatically run performance benchmarks on every commit to the main branch. This ensures consistent performance tracking and helps identify performance regressions early.

## How It Works

### Workflow Trigger

The benchmark workflow (`.github/workflows/performance-benchmark.yml`) runs:
- On every push to `main` branch that affects server/client code
- Weekly on Sunday (scheduled)
- Manually via workflow dispatch

### Test Environment

- **Platform**: Ubuntu latest (GitHub Actions runner)
- **Cluster**: 3-node Raft cluster (node1:8881, node2:8882, node3:8883)
- **JDK**: OpenJDK 17 (Temurin distribution)

### Test Scenarios

1. **Read-only Test**
   - 100 threads, 30 seconds
   - Tests `exists()` operations
   - Measures read throughput and latency

2. **Write-only Test**
   - 50 threads, 30 seconds
   - Tests `tryLock()` + `unlock()` operations
   - Measures write throughput and latency

3. **Mixed Test (9:1 read/write ratio)**
   - 100 threads, 30 seconds
   - 90% reads, 10% writes
   - Simulates typical production workload

### Metrics Collected

For each test scenario:
- **QPS** (Queries Per Second)
- **P50 Latency** (median)
- **P95 Latency** (95th percentile)
- **P99 Latency** (99th percentile)
- **P999 Latency** (99.9th percentile)
- **Total operations**
- **Failed operations**

## Results Storage

### JSON History

All benchmark results are stored in `docs/benchmark-history.json`:

```json
[
  {
    "timestamp": "2026-04-04T10:30:00Z",
    "commit": "a0d3009...",
    "read_test": {
      "threads": 100,
      "duration_ms": 30000,
      "total_ops": 29400000,
      "qps": 980000,
      "p50_latency_us": 0.5,
      "p99_latency_us": 1.2
    },
    "write_test": { ... },
    "mixed_test": { ... }
  }
]
```

The history keeps the last 50 benchmark runs.

### Performance Charts

The workflow generates `docs/benchmark-chart.png` with 4 visualizations:

1. **QPS Trends** - Shows QPS over time for all test scenarios
2. **P99 Latency Trends** - Shows latency trends over time
3. **Latest QPS Comparison** - Bar chart comparing latest results
4. **Latest Latency Distribution** - Shows P50/P95/P99/P999 for latest run

### Badge

A dynamic badge is generated in `docs/benchmark-badge.json` showing the latest QPS numbers.

## Viewing Results

### In README

The main README displays:
- Benchmark status badge
- Latest performance chart
- Summary table of results
- Link to historical data

### In GitHub Actions

1. Go to the [Actions tab](https://github.com/dongzongao/hutulock/actions)
2. Click on "Performance Benchmark" workflow
3. View individual run logs and artifacts

### Historical Trends

View the complete history:
```bash
cat docs/benchmark-history.json | jq '.'
```

## Running Locally

You can run the same benchmark locally:

```bash
# Start 3-node cluster
./bin/cluster.sh

# Wait for cluster to be ready
sleep 30

# Run benchmark
cd hutulock-client
mvn exec:java -Dexec.mainClass="com.hutulock.client.benchmark.LockBenchmark" \
  -Dexec.args="127.0.0.1:8881,127.0.0.1:8882,127.0.0.1:8883 100 30"
```

Or use the quick benchmark:
```bash
./bin/benchmark.sh all 100 30
```

## Performance Expectations

### Typical Results (3-node cluster)

| Metric | Read | Write | Mixed (9:1) |
|:-------|-----:|------:|------------:|
| QPS | 900K+ | 4K+ | 100K+ |
| P99 Latency | <2μs | <100ms | <50ms |

### Performance Regression Detection

If a commit causes:
- **QPS drop > 20%** - Investigate immediately
- **P99 latency increase > 50%** - Check for blocking operations
- **Failed operations > 1%** - Check for stability issues

## Troubleshooting

### Workflow Fails to Start Cluster

Check the node logs in the workflow output:
```bash
cat node1.log
cat node2.log
cat node3.log
```

Common issues:
- Port conflicts (8881-8883, 9881-9883)
- Insufficient memory
- Raft election timeout

### Benchmark Results Look Wrong

Verify:
1. All 3 nodes are running (`ps aux | grep hutulock`)
2. Cluster has elected a leader (check node logs)
3. Client can connect to all nodes
4. No network issues in GitHub Actions runner

### Chart Generation Fails

Check Python dependencies:
```bash
pip install matplotlib pandas
```

Verify JSON format:
```bash
cat benchmark-results.json | jq '.'
```

## Customization

### Adjust Test Duration

Edit `.github/workflows/performance-benchmark.yml`:

```yaml
# Change from 30s to 60s
Map<String, Object> readTest = runTest(client, 100, 60, "read");
```

### Add New Test Scenarios

Add a new test in the workflow:

```java
// Test 4: High concurrency
System.out.println("Running high concurrency test...");
Map<String, Object> highConcTest = runTest(client, 500, 30, "mixed");
results.put("high_concurrency_test", highConcTest);
```

### Change Chart Style

Edit the `generate_charts.py` section in the workflow to customize colors, layout, or add new visualizations.

## Best Practices

1. **Don't skip CI** - The `[skip ci]` tag is only used for the benchmark commit itself to avoid infinite loops
2. **Monitor trends** - Look at the charts regularly to spot gradual performance degradation
3. **Investigate spikes** - Sudden changes in latency or QPS deserve investigation
4. **Compare with local** - If CI results differ significantly from local, check the environment

## Future Enhancements

Planned improvements:
- [ ] Multi-region testing
- [ ] Larger cluster sizes (5, 7 nodes)
- [ ] Network partition simulation
- [ ] Memory and CPU profiling
- [ ] Comparison with Redis/ZooKeeper
- [ ] Performance regression alerts (GitHub Issues)
