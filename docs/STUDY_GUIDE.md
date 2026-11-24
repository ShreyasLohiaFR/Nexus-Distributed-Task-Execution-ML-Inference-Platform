# Nexus interview and study guide

## 1. First, trace one job

Read `JobController.java`, `JobRequest.java`, `JobStore.java`, `Worker.java`, and `TaskExecutor.java` in that order. Submit a WORD_COUNT job in the dashboard; inspect its UUID, state, attempts and output. Write out the SQL and describe what happens before and after the transaction commits. Change the payload and observe the result. The browser polls; it doesn't execute jobs.

## 2. Learn the state machine

QUEUED → RUNNING → SUCCEEDED / CANCELLED / FAILED. A retry goes from RUNNING back to QUEUED with `available_at` in the future. Each claim increments attempts. Once exhausted, a failure becomes terminal. Cancelling a queued or running job clears its lease. A terminal state cannot be overwritten by a late worker.

**Exercise:** draw a sequence diagram with two workers trying to claim the same row. Explain why the update is atomic, why SKIP LOCKED avoids blocking on already-locked rows, and why the database transaction must not stay open for the entire task execution.

## 3. Lease fencing and idempotency

A lease is time-limited ownership of a job attempt. Each claim generates a UUID lease token. Heartbeats extend a still-valid lease. Expired leases are recovered; a subsequent claim gets a different token. Completion only succeeds when job ID, RUNNING state, token and lease expiration all match. A worker might still compute after losing its lease; it cannot commit its result. This is at-least-once execution. A real payment or email task would require an independent idempotent side-effect strategy (e.g. an outbox and deduplicating consumer).

Client idempotency is different: a unique key prevents duplicate job creation. The request fingerprint includes kind, payload, priority, max attempts, delay. A reused key with a different request is rejected. This example handles single-field JSON payloads; a general-purpose API should use explicit canonical JSON normalization.

**Exercise:** predict what happens when the API succeeds but the client times out, when a worker dies after computation but before commit, and when heartbeat and recovery race.

## 4. Database choices

PostgreSQL provides transactions, row-level locks, partial indexes and a durable source of truth. In a small service, it avoids a separate broker and the dual-write problem. The queue scan uses a partial index over QUEUED rows; recovery scans running leases. At higher traffic, polling and hot-index updates can be expensive; measure and consider a broker rather than assuming microservices improve throughput.

**Exercise:** inspect `EXPLAIN (ANALYZE, BUFFERS)` on the claim query against a disposable database, compare execution plans with different queue sizes, and explain why strict priority can starve jobs.

## 5. Concurrency and reliability

Each worker process has a fixed thread pool and semaphore. It only claims when a slot is available. Multiple processes can share PostgreSQL without in-memory coordination. Scheduled tasks poll and renew leases; database timestamps provide a common time source. Retries use 2^attempt seconds; no jitter is implemented. HTTP inference calls have a deadline. A hung local task is not covered by a generic execution timeout; implemented local tasks are bounded by payload size. Explain the tradeoffs, then implement jitter or fairness independently.

## 6. ML engineering

`ml/model.py` loads 8×8 grayscale digit images, splits stratified train/test with seed 42, fits a scaler and logistic regression on training data only, evaluates held-out accuracy/macro-F1, and compares with a majority baseline. FastAPI validates the input shape and serves a prediction and model probability. The reported probability is not a calibrated guarantee of correctness. Data distribution and model size are small; don't claim novel AI research. Draw a digit in the UI and see how binary pixels differ from training grayscale.

**Exercise:** compare logistic regression to a random forest using a separate validation split, keep the final test set untouched, record precision/recall/F1 and training time, and test confidence calibration. Explain why model metrics and system throughput measure different things.

## 7. Security, tests and CI

The API uses a single workspace key from environment variables and same-origin requests. Secrets are not in Git. Only the API port is bound to localhost. Payload types and ranges are checked; tasks cannot run arbitrary code. The development security design is intentionally limited. Describe what you'd need for internet deployment: TLS, per-user auth, ownership checks, quotas, bounded request parsing, retention, dependency audits and logging. Run CI and inspect any test failures. Never run `JobStoreTest` against a real database with user data.

## 8. Independent exercises before putting it on your resume

1. Implement a new pure task type (e.g. character-frequency summary) end to end, with validation and a test.
2. Fix a deliberately broken test and explain the root cause without AI.
3. Add retry jitter and prove delays stay within bounds.
4. Run Docker Compose, demonstrate scheduled execution and cancellation, and record a short demo.
5. Run and record load benchmarks with 1/2/4 worker processes. Explain why scaling might stop helping.
6. Run the deterministic expired-lease test and a real worker-kill experiment. Explain what was proven and what was not.
7. Draw the full architecture and job state machine from memory.
8. Review the code in a real interview-style walkthrough; describe AI assistance accurately if asked.

## 9. Questions and short answers

**Why not Redis?** PostgreSQL is already the durable state store, so one atomic statement claims and updates state without dual writes. Redis/RabbitMQ may help if measured workloads justify a separate broker.

**Does SKIP LOCKED provide exactly-once execution?** No. It prevents simultaneous claim of the same eligible row while its lock is held. Lease recovery permits a later attempt and thus duplicate computation. Tokens reject stale writes.

**What happens on API restart?** Jobs remain in PostgreSQL. Workers can continue processing. A worker crash leaves a lease that expires and is recovered.

**Can you cancel a running task instantly?** Not necessarily. The API clears the lease, marks CANCELLED, and blocks completion. Pure computation may finish; interruption or external rollback would require a separate contract.

**What is your actual benchmark?** Use your own JSON files and hardware details. Before an integration run there is no verified distributed throughput figure.

**What has been independently verified in the provided environment?** Six Python ML tests, five Java core assertions, JavaScript syntax, and a held-out model evaluation. The full DB integration suite and Compose need to pass on a real runtime.
