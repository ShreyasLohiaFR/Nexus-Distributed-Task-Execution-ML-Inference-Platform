"""Measure real queue throughput and end-to-end completion latency. Requires a running Nexus."""
import argparse
import concurrent.futures
import json
import os
import statistics
import time
import urllib.request
import urllib.error
import uuid
from datetime import datetime, timezone


def api(base, key, path, payload=None, method=None, idempotency=None):
    headers = {"X-API-Key": key, "Content-Type": "application/json"}
    if idempotency:
        headers["Idempotency-Key"] = idempotency
    request = urllib.request.Request(base.rstrip("/") + "/api" + path,
        data=None if payload is None else json.dumps(payload).encode(), headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)


def percentile(values, fraction):
    values = sorted(values)
    if not values:
        return None
    position = (len(values) - 1) * fraction
    lo, hi = int(position), min(int(position) + 1, len(values) - 1)
    return round(values[lo] + (values[hi] - values[lo]) * (position - lo), 2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--jobs", type=int, default=100)
    parser.add_argument("--clients", type=int, default=8)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--workers-label", default="unspecified", help="Record actual deployment configuration")
    parser.add_argument("--output", default="benchmark-results.json")
    args = parser.parse_args()
    key = os.environ.get("NEXUS_API_KEY")
    if not key:
        parser.error("Set NEXUS_API_KEY in the environment")
    if not (1 <= args.jobs <= 10000 and 1 <= args.clients <= 32 and args.timeout > 0):
        parser.error("jobs 1–10000, clients 1–32, positive timeout")
    before = api(args.base, key, "/stats")
    if before["QUEUED"] or before["RUNNING"]:
        parser.error("Use an idle, isolated benchmark workspace")
    start = time.monotonic()
    submissions = {}
    failures = []
    run = str(uuid.uuid4())

    def submit(index):
        t0 = time.monotonic()
        result = api(args.base, key, "/jobs", {"kind": "SHA256", "payload": {"text": f"{run}:{index}"}, "priority": 5, "maxAttempts": 3}, idempotency=f"{run}-{index}")
        return result["id"], t0, (time.monotonic() - t0) * 1000

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.clients) as pool:
        for future in concurrent.futures.as_completed([pool.submit(submit, i) for i in range(args.jobs)]):
            try:
                job_id, sent, response_ms = future.result()
                submissions[job_id] = (sent, response_ms)
            except Exception as exc:
                failures.append(str(exc))
        submit_duration = time.monotonic() - start
        # Poll individual IDs, not global queue totals. DB timestamps provide queue-to-terminal latency.
        pending = set(submissions)
        terminal = {}
        terminal_jobs = {}
        completion_ms = []
        deadline = start + args.timeout
        while pending and time.monotonic() < deadline:
            def check(job_id):
                try:
                    job = api(args.base, key, f"/jobs/{job_id}")
                    if job["state"] not in {"QUEUED", "RUNNING"}:
                        return job_id, job
                except (OSError, urllib.error.URLError):
                    pass
                return job_id, None
            for job_id, job in pool.map(check, list(pending)):
                if job is not None:
                    pending.remove(job_id)
                    terminal[job_id] = job["state"]
                    terminal_jobs[job_id] = job
                    if job["state"] == "SUCCEEDED":
                        created = datetime.fromisoformat(job["createdAt"].replace("Z", "+00:00"))
                        updated = datetime.fromisoformat(job["updatedAt"].replace("Z", "+00:00"))
                        completion_ms.append((updated - created).total_seconds() * 1000)
            if pending:
                time.sleep(0.25)
    elapsed = time.monotonic() - start
    response_times = [row[1] for row in submissions.values()]
    success = list(terminal.values()).count("SUCCEEDED")
    report = {"timestamp_utc": datetime.now(timezone.utc).isoformat(), "workers_label": args.workers_label,
              "requested": args.jobs, "accepted": len(submissions), "submission_errors": len(failures),
              "succeeded": success, "failed_or_cancelled": len(terminal)-success, "unfinished": len(pending),
              "worker_ids_observed": sorted({job.get("workerId") for job in terminal_jobs.values() if job.get("workerId")}),
              "clients": args.clients, "submission_seconds": round(submit_duration, 3),
              "observed_total_seconds": round(elapsed, 3), "observed_successes_per_second": round(success/elapsed, 2),
              "submit_latency_ms": {"p50": percentile(response_times, .50), "p95": percentile(response_times, .95)},
              "successful_queue_to_completion_ms": {"p50": percentile(completion_ms, .50), "p95": percentile(completion_ms, .95)},
              "notes": "Throughput includes submission and polling overhead. Job latencies use database timestamps. Results depend on hardware and worker configuration."}
    with open(args.output, "w", encoding="utf-8") as file:
        json.dump(report, file, indent=2)
    print(json.dumps(report, indent=2))
    if failures or pending or success != args.jobs:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
