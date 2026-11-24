# Verification plan

## Run evidence (provided environment)

- `python -m pytest -q tests/test_ml.py`: 6 tests passed.
- `javac -d <tmp> src/main/java/com/nexus/JobRules.java scripts/RuleSmoke.java && java -cp <tmp> RuleSmoke`: 5 core assertions passed.
- `node --check src/main/resources/static/app.js`: syntax check passed.
- Model report: `docs/ml-evaluation.json`, stratified held-out evaluation.
- Full Maven, PostgreSQL, and Docker stack: NOT run in the authoring environment (missing Maven/Docker/PostgreSQL, network restricted). CI is configured to execute the Java suite on a disposable database and the end-to-end smoke script against a fresh Compose stack. The code still needs full build and runtime validation.

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
