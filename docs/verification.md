# Verification plan

## Repeated scaling experiment — 2026-11-25

[Passing PR CI](https://github.com/ShreyasLohiaFR/Nexus-Distributed-Task-Execution-ML-Inference-Platform/actions/runs/45570285078), tested head `3a7f1e28bfe42e2d6b764432e4b4810abbd0d3e0`, merged as `db6e5267b64247d36e574f205cf619c5a8d6e077`. All Java, ML, Docker smoke, worker-termination recovery, Chromium and benchmark checks passed. [Raw benchmark artifact](https://github.com/ShreyasLohiaFR/Nexus-Distributed-Task-Execution-ML-Inference-Platform/actions/runs/45570285078/artifacts/15002320554).

| Worker processes × threads | Trials | Successful jobs | Median jobs/s | Range jobs/s | Median run p95 submission (ms) | Median run p95 completion (ms) |
|---|---:|---:|---:|---:|---:|---:|
| 1 × 2 | 3 | 3000/3000 | 520.45 | 439.71–568.20 | 13.52 | 195.63 |
| 2 × 2 | 3 | 3000/3000 | 522.44 | 465.73–577.78 | 20.33 | 253.46 |
| 4 × 2 | 3 | 3000/3000 | 428.24 | 421.66–607.26 | 24.85 | 185.39 |

All 9,000 requested jobs passed the correctness gates, with zero failed, cancelled or unfinished jobs and zero submission errors. Two workers had nearly the same median throughput as one; four workers were slower at the median and more variable. Potential causes include database contention, shared runner resources, API submission/polling overhead and a growing database; no root cause has yet been isolated. These short SHA256 workloads with eight clients are not a production capacity guarantee. Reported latency is the median of run-level p95 values, not a pooled percentile. Do not compare different CI runners as an isolated speedup experiment.

Reproduce only on a disposable local stack with the API key exported: `python scripts/benchmark_matrix.py --isolated-compose --jobs 1000 --repeats 3 --workers 1 2 4`. Raw JSON and summary are retained in the output directory.

## Release candidate evidence — 2026-11-24

[Successful CI](https://github.com/ShreyasLohiaFR/Nexus-Distributed-Task-Execution-ML-Inference-Platform/actions/runs/45387632217), implementation commit `e5a32418968b59432f13589ff4ec9d52d3bcc950`: 10 Java/PostgreSQL tests and 6 ML tests passed. Compose smoke, real SIGKILL recovery (attempt 2, replacement worker), and Chromium submissions for all three task types, scheduling, cancellation, filtering and reload persistence passed. [Benchmark and dashboard artifact](https://github.com/ShreyasLohiaFR/Nexus-Distributed-Task-Execution-ML-Inference-Platform/actions/runs/45387632217/artifacts/14917474423) was uploaded; screenshot pixels have not been independently inspected.

| Processes × threads | Successful jobs | Jobs/s | p95 submission (ms) | p95 queue-to-completion (ms) |
|---|---:|---:|---:|---:|
| 1 × 2 | 1000/1000 | 327.11 | 34.41 | 116.42 |
| 2 × 2 | 1000/1000 | 400.75 | 25.36 | 122.64 |

Eight clients, Ubuntu 24.04 hosted runner, zero submission errors, failed/cancelled or unfinished jobs in either run. Observed worker IDs: `worker-2608e772-ca74-4884-bb36-21c5e7ac3fc4` in both runs; additionally `worker-6ae4df2f-ad8d-4c46-b5f4-4397e71ac1ea` in the two-process run. Total observed times 3.057 and 2.495 seconds. Throughput includes submission and polling. Two processes yielded 22.5% higher observed throughput but 5.3% worse p95 completion latency. Single short runs, not a capacity guarantee or isolated causal comparison; repeat with controlled hardware and longer workloads. Held-out digits accuracy 97.78%, majority baseline 10.22%. No public deployment.


## Run evidence (provided environment)

- `python -m pytest -q tests/test_ml.py`: 6 tests passed.
- `javac -d <tmp> src/main/java/com/nexus/JobRules.java scripts/RuleSmoke.java && java -cp <tmp> RuleSmoke`: 5 core assertions passed.
- `node --check src/main/resources/static/app.js`: syntax check passed.
- Model report: `docs/ml-evaluation.json`, stratified held-out evaluation.
- Full Maven, PostgreSQL and Docker stack: [CI run 45363232562](https://github.com/ShreyasLohiaFR/Nexus-Distributed-Task-Execution-ML-Inference-Platform/actions/runs/45363232562), commit `3d96048851315cee981c899d83fca75684baed70`, passed all three jobs. 9 Java tests, 6 Python tests, real SIGKILL recovery on attempt 2. Benchmark: 200/200 jobs completed in each configuration; 1x2: 21.78 jobs/s, p95 submission 27.32 ms, p95 completion 7880.81 ms; 2x2: 22.20 jobs/s, p95 submission 24.43 ms, p95 completion 8358.05 ms. These are single-run observations, not a scalability claim. [Benchmark artifact](https://github.com/ShreyasLohiaFR/Nexus-Distributed-Task-Execution-ML-Inference-Platform/actions/runs/45363232562/artifacts/14904609287). This is historical baseline evidence; see the release candidate above.

## Test matrix

| Case | Test | Expected |
|---|---|---|
| Duplicate client submission | JobStoreTest | same job ID; differing payload/key reuse yields conflict |
| Concurrent claim | JobStoreTest | 40 unique jobs across 8 claiming threads |
| Priority and scheduling | JobStoreTest | available high priority first; delayed job not claimed |
| Expired lease | JobStoreTest | queued retry; new token; stale write rejected |
| Attempts exhausted | JobStoreTest | terminal FAILED |
| Cancel running | JobStoreTest | CANCELLED; stale worker cannot complete |
| ML contract | Python tests | invalid pixel arrays rejected; valid response in range |
| End-to-end | scripts/smoke.py | real API, worker, DB and ML round trip |
| API auth and validation | JobApiTest | 401 without key, invalid payload/size rejected, idempotency conflict |
| Worker process loss | scripts/recovery.py | SIGKILL, expired lease, replacement worker succeeds on second attempt |

## Failure demonstration

1. Start Compose and run `scripts/smoke.py` first.
2. For a reliable crash-recovery exercise, stop workers, submit a job, and claim it in the database integration test (the test sets `lease_until` to the past to avoid a slow/flaky test). Run `mvn -Dtest=JobStoreTest#expiredLeaseIsRecoveredAndStaleWorkerCannotCommit test` against the disposable test DB.
3. On a disposable, idle Compose stack only, run `python scripts/recovery.py --isolated-compose`. It pauses ML to keep a real inference task in flight, waits for a claim, terminates the worker process with SIGKILL, restores ML and starts a replacement worker. The assertions require the original lease to remain RUNNING after termination, a second attempt, a different worker ID and eventual success. CI executes this test. Do not report a successful crash-recovery experiment until the workflow passes.
4. With ML unavailable, digit jobs will fail/retry with backoff and eventually enter FAILED. Restart the service and submit a new task; verify it succeeds.

## Benchmark protocol

Use an isolated local stack and keep hardware, payload, software versions, client concurrency, worker threads and number of jobs fixed except the variable under test. First run 100 jobs as a smoke test. Then run e.g. 1,000 jobs with 1, 2 and 4 worker processes (two threads each), three repetitions per configuration. Let all jobs settle before each run. Record the exact process and thread counts, CPU/RAM, median/p95 latency, accepted/succeeded/failed/unfinished counts, and throughput including poll overhead. `scripts/benchmark.py` writes real JSON. Do not report numbers for a run that did not execute.

```bash
docker compose up -d --scale worker=1
python scripts/benchmark.py --jobs 1000 --clients 8 --workers-label '1x2' --output benchmark-results-1x2.json
docker compose up -d --scale worker=2
python scripts/benchmark.py --jobs 1000 --clients 8 --workers-label '2x2' --output benchmark-results-2x2.json
```

If API submission becomes the bottleneck, compare submission latency and queue-to-completion latency; explain PostgreSQL contention and polling overhead. Better throughput is a hypothesis, not a promised result.
