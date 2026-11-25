"""Repeatable, isolated Nexus scaling experiment; no production traffic claims."""
import argparse
import json
import os
from pathlib import Path
import statistics
import subprocess
import sys
from datetime import datetime, timezone


def summarize(runs, jobs):
    """Reject incomplete runs and summarize per-configuration observations."""
    groups = {}
    for row in runs:
        workers = row["workers_label"]
        if (row["requested"] != jobs or row["accepted"] != jobs
                or row["succeeded"] != jobs or row["submission_errors"]
                or row["failed_or_cancelled"] or row["unfinished"]):
            raise ValueError(f"Incomplete benchmark: {workers}")
        groups.setdefault(workers, []).append(row)
    summary = {}
    for workers, rows in groups.items():
        rates = [r["observed_successes_per_second"] for r in rows]
        submit = [r["submit_latency_ms"]["p95"] for r in rows]
        completion = [r["successful_queue_to_completion_ms"]["p95"] for r in rows]
        if any(v is None for v in rates + submit + completion):
            raise ValueError(f"Missing latency or throughput: {workers}")
        summary[workers] = {
            "repetitions": len(rows),
            "successful_jobs": sum(r["succeeded"] for r in rows),
            "median_jobs_per_second": round(statistics.median(rates), 2),
            "min_jobs_per_second": min(rates),
            "max_jobs_per_second": max(rates),
            "median_run_p95_submission_ms": round(statistics.median(submit), 2),
            "median_run_p95_completion_ms": round(statistics.median(completion), 2),
        }
    return summary


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--isolated-compose", action="store_true",
                        help="Confirm this is a disposable, dedicated local Compose stack.")
    parser.add_argument("--jobs", type=int, default=1000)
    parser.add_argument("--clients", type=int, default=8)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--workers", type=int, nargs="+", default=[1, 2, 4])
    parser.add_argument("--output", type=Path, default=Path("scaling-results"))
    args = parser.parse_args()
    if not args.isolated_compose:
        parser.error("Use only a disposable local stack and pass --isolated-compose.")
    if not os.environ.get("NEXUS_API_KEY"):
        parser.error("Set NEXUS_API_KEY.")
    if not (1 <= args.jobs <= 10000 and 1 <= args.clients <= 32
            and 2 <= args.repeats <= 5 and all(1 <= w <= 4 for w in args.workers)
            and len(set(args.workers)) == len(args.workers)):
        parser.error("Invalid jobs, clients, repetitions or worker configuration.")
    args.output.mkdir(parents=True, exist_ok=False)
    env = {**os.environ, "WORKER_CONCURRENCY": "2"}
    runs = []
    script = Path(__file__).with_name("benchmark.py")
    # Alternate order across repetitions to reduce systematic warm-up bias.
    for repetition in range(1, args.repeats + 1):
        order = args.workers if repetition % 2 else list(reversed(args.workers))
        for workers in order:
            subprocess.run(["docker", "compose", "up", "-d", "--no-build",
                            "--wait", "--scale", f"worker={workers}"],
                           env=env, check=True)
            path = args.output / f"{workers}x2-run-{repetition}.json"
            subprocess.run([sys.executable, str(script), "--jobs", str(args.jobs),
                            "--clients", str(args.clients), "--timeout", "180",
                            "--workers-label", f"{workers}x2", "--output", str(path)],
                           env=env, check=True)
            runs.append(json.loads(path.read_text(encoding="utf-8")))
            summarize(runs, args.jobs)
    report = {"timestamp_utc": datetime.now(timezone.utc).isoformat(),
              "jobs_per_run": args.jobs, "clients": args.clients,
              "summary": summarize(runs, args.jobs), "runs": runs,
              "limitations": "Median of run-level p95s, not pooled p95. Short SHA256 workload; "
                             "database grows across runs; throughput includes polling; "
                             "hosted runner hardware may vary; not production capacity."}
    (args.output / "summary.json").write_text(json.dumps(report, indent=2)+"\n", encoding="utf-8")
    print(json.dumps(report["summary"], indent=2))


if __name__ == "__main__":
    run()
