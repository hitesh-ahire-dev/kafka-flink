Grafana dashboards for login-producer-service
==========================================

This folder contains a Grafana dashboard JSON you can import to monitor the
login-producer-service using Prometheus metrics exposed by Spring Boot Actuator
and Micrometer.

Files:
- `dashboards/login-producer-service-dashboard.json` - Grafana dashboard auto-provisioned by docker-compose (Kafka consumer rate, avg/p95 processing latency, total processed).

Steps to enable metrics and import the dashboard
------------------------------------------------

1. Build and run the service (after the dependency changes in `pom.xml`).

   mvn -f login-producer-service clean package
   java -jar login-producer-service/target/login-producer-service-0.0.1-SNAPSHOT.jar

2. Confirm the Prometheus-format metrics are exposed by the application at:

   http://localhost:8080/actuator/prometheus

3. Configure Prometheus to scrape the application. Example scrape config:

   - job_name: 'login-producer-service'
     static_configs:
       - targets: ['host.docker.internal:8080']
       # or 'localhost:8080' if Prometheus runs on the same host

4. In Grafana, import `login-producer-dashboard.json` (Dashboard -> + -> Import),
   choose your Prometheus data source, and the dashboard will be available.

Notes & troubleshooting
- If your application listens on a different port, update the Prometheus target
  and Grafana template variable accordingly.
- The dashboard uses the `application` label emitted by Micrometer. If your
  Prometheus metrics do not contain that label, remove the `{application=~"$app"}`
  filters in the dashboard JSON or set the Grafana variable to match the label used.

