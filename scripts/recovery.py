"""Exercise real process failure on a disposable, local Compose stack."""
import argparse
import os
import subprocess
import time
import uuid
from benchmark import api

BASE = "http://localhost:8080"


def compose(*args):
    subprocess.run(["docker", "compose", *args], check=True, timeout=90)


def wait_for(key, job_id, predicate, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        job = api(BASE, key, "/jobs/" + job_id)
        if predicate(job):
            return job
        time.sleep(.15)
    raise TimeoutError(f"Job {job_id} did not reach expected state")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--isolated-compose", action="store_true",
                        help="Confirm the stack is disposable; this stops its worker processes")
    args = parser.parse_args()
    if not args.isolated_compose:
        parser.error("Run only against an isolated disposable Compose stack")
    key = os.environ["NEXUS_API_KEY"]
    counts = api(BASE, key, "/stats")
    if counts["RUNNING"] or counts["QUEUED"]:
        raise RuntimeError("Start with an idle workspace")
    compose("up", "-d", "--scale", "worker=1", "--no-build", "worker")
    compose("pause", "ml")
    try:
        job = api(BASE, key, "/jobs", {
            "kind": "DIGIT_CLASSIFY", "payload": {"pixels": [0]*64}, "maxAttempts": 3
        }, idempotency=str(uuid.uuid4()))
        first = wait_for(key, job["id"], lambda j: j["state"] == "RUNNING", 8)
        compose("kill", "-s", "SIGKILL", "worker")
        killed = api(BASE, key, "/jobs/" + job["id"])
        assert killed["state"] == "RUNNING" and killed["attempts"] == 1, killed
    finally:
        compose("unpause", "ml")
        compose("up", "-d", "--no-build", "worker")
    done = wait_for(key, job["id"], lambda j: j["state"] in {"SUCCEEDED", "FAILED", "CANCELLED"}, 90)
    assert done["state"] == "SUCCEEDED" and done["attempts"] == 2, done
    assert done["workerId"] != first["workerId"], done
    print(f"Recovered job {job['id']} after a worker SIGKILL; completed on attempt {done['attempts']}")


if __name__ == "__main__":
    main()
