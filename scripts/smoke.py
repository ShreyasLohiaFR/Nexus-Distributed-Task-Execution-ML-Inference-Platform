"""End-to-end API check against a running local Docker Compose stack."""
import os
import time
import uuid
from benchmark import api

BASE = os.getenv("NEXUS_BASE", "http://localhost:8080")
KEY = os.environ["NEXUS_API_KEY"]


def wait(job_id, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        job = api(BASE, KEY, "/jobs/" + job_id)
        if job["state"] not in ("QUEUED", "RUNNING"):
            return job
        time.sleep(.25)
    raise TimeoutError(job_id)


def main():
    payload = {"kind": "WORD_COUNT", "payload": {"text": "alpha beta gamma"}, "maxAttempts": 3}
    idem = str(uuid.uuid4())
    a = api(BASE, KEY, "/jobs", payload, idempotency=idem)
    b = api(BASE, KEY, "/jobs", payload, idempotency=idem)
    assert a["id"] == b["id"]
    done = wait(a["id"])
    assert done["state"] == "SUCCEEDED" and done["result"]["words"] == 3, done
    future = api(BASE, KEY, "/jobs", {**payload, "delaySeconds": 120})
    cancelled = api(BASE, KEY, f"/jobs/{future['id']}/cancel", method="POST")
    assert cancelled["state"] == "CANCELLED"
    digit = api(BASE, KEY, "/jobs", {"kind": "DIGIT_CLASSIFY", "payload": {"pixels": [0]*64}})
    result = wait(digit["id"])
    assert result["state"] == "SUCCEEDED" and 0 <= result["result"]["digit"] <= 9, result
    print("Passed: idempotency, execution, scheduled cancellation, and ML inference")


if __name__ == "__main__":
    main()
