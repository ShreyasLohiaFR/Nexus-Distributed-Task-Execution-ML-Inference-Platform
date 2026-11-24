# Design decisions

Nexus is a single-workspace task service. Multiple independent worker processes may share the same PostgreSQL database. The API and worker code are in one application, but each can be deployed with workers enabled or disabled. The ML service is internal to the worker network. Job payloads are limited to known, pure computation types; arbitrary user code is deliberately unsupported.

PostgreSQL is the queue and source of truth. One statement claims a row using `FOR UPDATE SKIP LOCKED`, sets a new lease token and increments attempts. The claim transaction ends immediately; no database lock is held while executing work. Every completion, failure, or heartbeat checks the lease token and current lease deadline. This fencing prevents a stale worker overwriting a newer attempt. Expired leases are recovered by periodic sweeps. Retries are at-least-once, not exactly-once: a computation can run twice, but stale attempts cannot commit their result. All implemented tasks are side-effect-free. Cancellation invalidates the lease; it cannot forcibly undo computation already running.

Priority is descending, then available time and creation time. Strict priorities can starve low-priority tasks; aging is a future extension. `available_at` supports both future scheduling and exponential retry delay. Failed jobs form the dead-letter view. Jobs are durable across process restarts; database outages, retention, backups and multi-tenant isolation require additional engineering before a public production launch.

We use a same-origin dashboard, a workspace API key, no external model APIs, and a CPU-friendly digit classifier trained on the scikit-learn digits dataset. This is an ML serving demonstration, not a novel ML research contribution.
