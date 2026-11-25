# Local operations monitoring (development only)

Run the normal Nexus stack on a trusted machine. Export the same NEXUS_API_KEY used by the API, and an independent strong GRAFANA_ADMIN_PASSWORD. Then run:

```sh
docker compose -f compose.yaml -f compose.observability.yaml up -d --build --scale worker=2
```

Prometheus is available at http://127.0.0.1:9090 and Grafana at http://127.0.0.1:3000 (admin and your configured password). Open Nexus / Nexus operations. Prometheus authenticates to protected actuator endpoints using a Compose secret sourced from the environment; the key is not committed. DNS service discovery tracks individual worker containers. The dashboard reports scrape health, HTTP request rate, JVM memory, process CPU and JDBC pool pressure. These are diagnostic signals, not measured job throughput. Do not expose either monitoring port to the public internet; this is not a production observability deployment. Stop the stack using the same two Compose files with `down`; do not use `-v` on a workspace you want to retain.

References: https://prometheus.io/docs/prometheus/latest/configuration/configuration/ and https://grafana.com/docs/grafana/latest/administration/provisioning/
