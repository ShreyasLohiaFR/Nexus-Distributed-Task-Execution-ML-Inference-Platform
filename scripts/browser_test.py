"""Browser-to-database integration test on the disposable Compose stack."""
import hashlib
import json
import os
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
            page.wait_for_function("""([id,state,key]) => fetch('/api/jobs/'+id, {headers:{'X-API-Key':key}})
               .then(r=>r.ok?r.json():null).then(j=>j && j.state===state)""",
                arg=[job_id, state, os.environ["NEXUS_API_KEY"]], timeout=30000, polling=250)
            return page.request.get(BASE+"/api/jobs/"+job_id, headers={"X-API-Key":os.environ["NEXUS_API_KEY"]}).json()

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
