"""Smoke-test authenticated metrics scraping and the provisioned Grafana dashboard."""
import base64
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request


def get(url, headers=None):
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers or {}), timeout=5) as response:
        return json.load(response)


def wait(description, fn, timeout=150):
    end = time.monotonic() + timeout
    last = None
    while time.monotonic() < end:
        try:
            result = fn()
            if result:
                print(description + ": passed")
                return result
        except (OSError, ValueError, KeyError) as error:
            last = str(error)
        time.sleep(3)
    raise RuntimeError(f"{description} timed out: {last}")


def healthy_targets():
    data = get("http://localhost:9090/api/v1/targets")["data"]["activeTargets"]
    api = [t for t in data if t["labels"]["job"] == "nexus-api"]
    workers = [t for t in data if t["labels"]["job"] == "nexus-worker"]
    return len(api) == 1 and len(workers) >= 2 and all(t["health"] == "up" for t in api + workers)


def query(expression):
    url = "http://localhost:9090/api/v1/query?" + urllib.parse.urlencode({"query": expression})
    return get(url)["data"]["result"]


def main():
    try:
        urllib.request.urlopen("http://localhost:8080/actuator/prometheus", timeout=5)
    except urllib.error.HTTPError as error:
        if error.code not in (401, 403):
            raise
    else:
        raise AssertionError("Metrics endpoint was accessible without API key")
    wait("Authenticated scrape of API and two workers", healthy_targets)
    wait("Real JVM samples", lambda: len(query('jvm_memory_used_bytes{job="nexus-api",area="heap"}')) > 0)
    wait("Real JDBC pool samples", lambda: len(query('hikaricp_connections_active{job="nexus-api"}')) > 0)
    basic = base64.b64encode(("admin:" + os.environ["GRAFANA_ADMIN_PASSWORD"]).encode()).decode()
    headers = {"Authorization": "Basic " + basic}
    dashboard = wait("Grafana provisioned dashboard", lambda: get("http://localhost:3000/api/dashboards/uid/nexus-operations", headers))
    assert len(dashboard["dashboard"]["panels"]) == 6
    source = get("http://localhost:3000/api/datasources/uid/nexus-prometheus", headers)
    assert source["url"] == "http://prometheus:9090"
    print("Six panels and Prometheus data source provisioned")


if __name__ == "__main__":
    main()
