"""Browser-to-database integration test on the disposable Compose stack."""
import hashlib
import json
import os
import time
from playwright.sync_api import sync_playwright, expect

BASE = "http://localhost:8080"

def main():
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(viewport={"width": 1440, "height": 1000})
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.goto(BASE)
        expect(page.locator("#submit-button")).to_be_disabled()
        page.locator("#key").fill(os.environ["NEXUS_API_KEY"])
        page.locator("#connect").click()
        expect(page.locator("#connection")).to_have_text("Connected", timeout=15000)

        def submit(kind, payload=None, delay=0):
            page.locator("#kind").select_option(kind)
            page.locator("#delay").fill(str(delay))
            if payload is not None:
                page.locator("#payload").fill(payload)
            with page.expect_response(lambda r: r.url == BASE + "/api/jobs" and r.request.method == "POST") as response:
                page.locator("#submit-button").click()
            job_response = response.value
            assert job_response.status == 201, job_response.status
            job = job_response.json()
            expect(page.locator("#form-message")).to_contain_text("Accepted job", timeout=15000)
            return job["id"]

        def wait_state(job_id, state):
            # Poll the real API and return the same response that satisfied the predicate.
            # Do not rely on a browser-side asynchronous predicate or a second read.
            deadline = time.monotonic() + 30
            headers = {"X-API-Key": os.environ["NEXUS_API_KEY"]}
            last = None
            while time.monotonic() < deadline:
                response = page.request.get(BASE+"/api/jobs/"+job_id, headers=headers)
                assert response.ok, response.status
                last = response.json()
                if last["state"] == state:
                    return last
                if last["state"] in {"SUCCEEDED", "FAILED", "CANCELLED"}:
                    raise AssertionError(f"Expected {state}, got {last}")
                time.sleep(.25)
            raise AssertionError(f"Timed out waiting for {state}: {last}")

        word = submit("WORD_COUNT", "alpha beta gamma")
        assert wait_state(word, "SUCCEEDED")["result"]["words"] == 3
        hashed = submit("SHA256", "nexus integration")
        assert wait_state(hashed, "SUCCEEDED")["result"]["sha256"] == hashlib.sha256(b"nexus integration").hexdigest()
        page.locator("#kind").select_option("DIGIT_CLASSIFY")
        page.locator("#sample").click()
        digit = submit("DIGIT_CLASSIFY")
        assert 0 <= wait_state(digit, "SUCCEEDED")["result"]["digit"] <= 9
        delayed = submit("WORD_COUNT", "cancel this task", 60)
        wait_state(delayed, "QUEUED")
        row = page.locator("#rows tr").filter(has_text=delayed[:8])
        expect(row).to_be_visible(timeout=15000)
        row.get_by_role("button", name="Cancel").click()
        wait_state(delayed, "CANCELLED")
        page.locator("#filter").select_option("CANCELLED")
        expect(page.locator("#rows")).to_contain_text(delayed[:8], timeout=15000)
        page.locator("#filter").select_option("")
        # A full browser reload must not erase jobs stored on the server.
        page.reload()
        page.locator("#key").fill(os.environ["NEXUS_API_KEY"])
        page.locator("#connect").click()
        expect(page.locator("#rows")).to_contain_text(word[:8], timeout=15000)
        page.screenshot(path="nexus-dashboard.png", full_page=True)
        assert not errors, errors
        browser.close()
        print("Browser integration passed: real submissions, all three task types, scheduling, cancel, filter and reload persistence.")

if __name__ == "__main__":
    main()
