# Phase 2 — Load test, integration tests, dashboards & alerts

> Companion document to `ANALYSIS.md`. The base flow analysis, issue list and
> initial fixes live there; this file captures the additions: 100K-message load
> test, integration tests, production dashboard, alert rules and observability
> recommendations.

---

## 1. Load test (100,000 messages)

### 1.1 What it does

`@/Users/hiteshahire/Documents/java-workspace/kafka+flink/login-producer-service/src/main/java/com/login_producer_service/loadtest/KafkaLoadTestRunner.java`

A `CommandLineRunner` activated by the **`loadtest`** Spring profile. It builds
its own `KafkaProducer` tuned for high throughput and sends N JSON `Customer`
records asynchronously, then prints throughput + p50/p95/p99 client-side
acknowledgement latency and exits.

Producer tuning (overridable via CLI args):

| Setting | Default | Why |
|---|---|---|
| `linger.ms` | 20 | Allow small wait so the producer batches more records per request. |
| `batch.size` | 65 536 | Larger batches at the cost of slightly higher latency. |
| `buffer.memory` | 64 MiB | Holds in-flight records during the burst. |
| `compression.type` | `lz4` | Cheap CPU, big network savings for JSON. |
| `acks` | `1` | Throughput-oriented; switch to `all` if durability matters. |
| `enable.idempotence` | `false` | Keeps semantics simple for a benchmark. |

### 1.2 Run it

```bash
# 100K messages, defaults
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=loadtest \
  -Dspring-boot.run.arguments="--loadtest.count=100000 --loadtest.topic=customer-data"
```

Or against a packaged jar (faster — no Maven JVM):

```bash
./mvnw -DskipTests package
java -jar target/login-producer-service-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=loadtest \
  --loadtest.count=100000 \
  --loadtest.topic=customer-data \
  --loadtest.acks=1 \
  --loadtest.lingerMs=20 \
  --loadtest.compression=lz4
```

### 1.3 Sample output

```
====== Load test result ======
  sent = 100000
  failed = 0
  durationSec = 4.812
  throughputMsgPerSec = 20786.34
  p50Ms = 2.41
  p95Ms = 8.97
  p99Ms = 14.20
  maxMs = 31.55
==============================
```

### 1.4 Measuring at the consumer side

While the load test runs, watch the dashboard:

- **Consumed rate by topic** should match the producer throughput once the consumer is steady-state.
- **p95 processing latency** rises as DB writes back-pressure the listener.
- **Consumer lag** spikes during the burst, then drains.

Alternatively, use the `LagController` endpoint after the burst:

```bash
curl http://localhost:8080/admin/lag/customer-consumer-group | jq
```

`totalLag` should return to 0 once the consumer catches up.

---

## 2. Tests

### 2.1 Unit tests

| File | Covers |
|---|---|
| `@/Users/hiteshahire/Documents/java-workspace/kafka+flink/login-producer-service/src/test/java/com/login_producer_service/service/ConsumerMetricsServiceTest.java` | B1 — `countLastSeconds` window honored, no state mutation |
| `@/Users/hiteshahire/Documents/java-workspace/kafka+flink/login-producer-service/src/test/java/com/login_producer_service/controller/CustomerMetricsControllerTest.java` | B2 — sort-then-percentile correctness, bounded `Pageable` used, unbounded query never called |

### 2.2 Integration tests (full Spring context, in-process Kafka + H2)

| File | What it verifies |
|---|---|
| `@/Users/hiteshahire/Documents/java-workspace/kafka+flink/login-producer-service/src/test/java/com/login_producer_service/integration/CustomerProduceConsumeTest.java` | Single message **and** burst (25 msgs) produce → consume → persist → Micrometer counter increment |
| `@/Users/hiteshahire/Documents/java-workspace/kafka+flink/login-producer-service/src/test/java/com/login_producer_service/integration/PoisonMessageDltTest.java` | Malformed JSON record is routed to `customer-data.DLT` and the partition keeps consuming subsequent valid records |

Both integration tests use `@EmbeddedKafka` (KRaft) and the `test` Spring
profile (`@/Users/hiteshahire/Documents/java-workspace/kafka+flink/login-producer-service/src/test/resources/application-test.yml`)
which switches the datasource to in-memory H2. They also wait for partition
assignment via `ContainerTestUtils.waitForAssignment` so they aren't racy.

Run all tests:

```bash
./mvnw test
```

Result on this branch:

```
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 2.3 Test categories you may want to add later

- `@WebMvcTest CustomerControllerTest` — request validation (`customer.id` blank → 400, `count > 100_000` → 400).
- Contract test for `Customer` record schema (Pact or Spring Cloud Contract).
- Throughput SLA test using `KafkaLoadTestRunner` invoked from CI with assertions on the printed stats.

---

## 3. Smart Grafana dashboard

### 3.1 File

`@/Users/hiteshahire/Documents/java-workspace/kafka+flink/login-producer-service/grafana/dashboards/login-producer-service-overview.json`

### 3.2 Layout (rows top → bottom)

1. **Service health** — six stat tiles: up, uptime, 5xx rate, error %, API p95, max consumer lag.
2. **Kafka throughput & lag** — consumed rate by topic, lag per client, Kafka-client consumed/produced.
3. **Consumer processing latency** — avg / p95 / p99 from `consumer_processing_latency_seconds_bucket`.
4. **API (HTTP)** — request rate by URI, p95 by URI, status-code distribution.
5. **JVM** — heap used vs max, GC pauses, threads, CPU, Logback events by level.
6. **Database (HikariCP)** — connections (active/idle/pending), connection acquire time avg/p95.

Variables: **`$topic`** (multi-select from `consumer_processed_count_total`) and
**`$group`** (Kafka client_id).

### 3.3 Import via Grafana UI

1. Open http://localhost:3000.
2. **Dashboards → New → Import**.
3. **Upload JSON file** → pick `grafana/dashboards/login-producer-service-overview.json`.
4. When asked, choose the `Prometheus` data source (uid `prometheus`).
5. Save. URL becomes http://localhost:3000/d/login-producer-overview.

### 3.4 Or import via API

```bash
DASH=$(cat grafana/dashboards/login-producer-service-overview.json)
curl -u admin:<your-password> \
  -X POST -H "Content-Type: application/json" \
  http://localhost:3000/api/dashboards/db \
  -d "{\"dashboard\": $DASH, \"overwrite\": true}"
```

---

## 4. Prometheus alert rules

### 4.1 File

`@/Users/hiteshahire/Documents/java-workspace/kafka+flink/login-producer-service/prometheus/alerts.yml` — wired into `@/Users/hiteshahire/Documents/java-workspace/kafka+flink/login-producer-service/prometheus/prometheus.yml:6-7` via `rule_files:`.

### 4.2 Rule groups

**`login-producer-service.health`**

| Alert | Condition | Severity |
|---|---|---|
| `ServiceDown` | `up{job="spring-boot-app"} == 0` for 2m | critical |
| `HighHttpErrorRate` | 5xx ratio > 5% for 10m | warning |
| `HighApiLatencyP95` | API p95 > 1s for 10m | warning |
| `LogErrorBurst` | error log rate > 0.5/s for 5m | warning |

**`login-producer-service.kafka`**

| Alert | Condition | Severity |
|---|---|---|
| `ConsumerLagHigh` | `kafka_consumer_records_lag_max > 1000` for 5m | warning |
| `ConsumerLagCritical` | `kafka_consumer_records_lag_max > 10000` for 5m | critical |
| `NoConsumptionWhenTrafficExpected` | producer rate > 0 AND consumer rate == 0 for 10m | warning |
| `HighProcessingLatencyP95` | consumer p95 > 1s for 10m | warning |
| `DltPublishingTraffic` | any rate of records on `*.DLT` for 1m | warning |

**`login-producer-service.runtime`**

| Alert | Condition | Severity |
|---|---|---|
| `HeapPressure` | heap_used / heap_max > 0.85 for 10m | warning |
| `GcPauseHigh` | `rate(jvm_gc_pause_seconds_sum[5m]) > 0.5` for 5m | warning |
| `HikariConnectionWait` | pending threads > 0 for 5m | warning |
| `HikariConnectionTimeouts` | timeout rate > 0 for 1m | critical |

### 4.3 Pick up the rules

Either restart Prometheus, or start it with `--web.enable-lifecycle` and:

```bash
curl -X POST http://localhost:9090/-/reload
```

Verify:

```bash
curl -s http://localhost:9090/api/v1/rules | jq '.data.groups[] | {name: .name, rules: (.rules|length)}'
```

Expected: 3 groups, 13 rules total.

### 4.4 Routing alerts

Connect Prometheus to Alertmanager (file not included — environment-specific).
At minimum:

```yaml
# in prometheus.yml
alerting:
  alertmanagers:
    - static_configs:
        - targets: ['localhost:9093']
```

In Alertmanager, route by `severity` to PagerDuty / Slack / email.

---

## 5. Observability improvements

### 5.1 Already in place after the previous fixes

- Micrometer `Timer` with histogram buckets enabled in `@/Users/hiteshahire/Documents/java-workspace/kafka+flink/login-producer-service/src/main/resources/application.yml:39-45`.
- `consumer.processed.count` and `consumer.processing.latency` tagged by `topic`.
- DLT pipeline so poison records show up as `kafka_producer_record_send_total{topic=~".*\\.DLT"}` for alerting.

### 5.2 Recommended additions (small, high-leverage)

**Common tags on every meter** (so all metrics are filterable by application, env, instance):

```java
// Add a MeterRegistryCustomizer bean somewhere central, e.g. OpenApiConfig.
@Bean
MeterRegistryCustomizer<MeterRegistry> commonTags(
        @Value("${spring.application.name}") String app,
        @Value("${ENVIRONMENT:dev}") String env) {
    return registry -> registry.config().commonTags("application", app, "env", env);
}
```

After this, dashboards can filter by `application` and `env` cleanly.

**Structured JSON logs** for log aggregation (Loki / ELK):

```xml
<!-- pom.xml: test/runtime scope -->
<dependency>
  <groupId>net.logstash.logback</groupId>
  <artifactId>logstash-logback-encoder</artifactId>
  <version>7.4</version>
</dependency>
```

```xml
<!-- src/main/resources/logback-spring.xml -->
<configuration>
  <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <includeMdcKeyName>traceId</includeMdcKeyName>
      <includeMdcKeyName>spanId</includeMdcKeyName>
      <customFields>{"application":"login-producer-service"}</customFields>
    </encoder>
  </appender>
  <root level="INFO"><appender-ref ref="JSON"/></root>
</configuration>
```

**Distributed tracing** (Spring Boot 4 / Micrometer Tracing + OTLP):

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

```yaml
management:
  tracing:
    sampling:
      probability: 0.1   # 10% sampling in prod
  otlp:
    tracing:
      endpoint: http://otel-collector:4318/v1/traces
```

Spring Boot's auto-config instruments HTTP server, RestClient, and Spring
Kafka producer/consumer ⇒ traceId/spanId flow from the controller through the
`@KafkaListener` automatically. Combined with the Logback encoder above, every
log line carries the trace IDs and you can pivot from a slow Grafana panel to
the exact trace in Tempo / Jaeger.

**Correlate Kafka records with business keys.**
Add MDC entry inside the listener so logs for a specific customer are easy to
filter:

```java
@KafkaListener(...)
public void consume(Customer customer) {
    try (var ignored = MDC.putCloseable("customer.id", customer.id())) {
        // existing body
    }
}
```

### 5.3 What I'd add next (not in this PR)

- A custom Micrometer `Timer.builder("consumer.processing.latency")` with a
  `tags("phase", "persist"/"metric"/"micrometer")` so we know which step is slow.
- A counter `consumer.dlt.publish.total{topic}` incremented from a `DeadLetterPublishingRecoverer` callback so the `DltPublishingTraffic` alert can fire even on brokers that don't expose `kafka_producer_record_send_total`.
- A `@Scheduled` task (every 30s) that emits the `customer-consumer-group` lag as a Micrometer gauge — the existing `LagController` is pull-only, but a gauge would let Prometheus alert on lag without depending on the JMX-derived `kafka_consumer_records_lag_max`.

---

## 6. Quick reference — running the whole thing locally

```bash
# 1. Stack (already documented; brew services)
brew services start grafana
prometheus --config.file=$PWD/prometheus/prometheus.yml \
           --storage.tsdb.path=/tmp/prom-data \
           --web.enable-lifecycle &

# 2. App
./mvnw spring-boot:run

# 3. Tests
./mvnw test

# 4. Load test (100K)
java -jar target/login-producer-service-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=loadtest --loadtest.count=100000

# 5. Dashboard
# Import grafana/dashboards/login-producer-service-overview.json into Grafana
# (UI: Dashboards → Import → Upload JSON).

# 6. Alerts
curl -X POST http://localhost:9090/-/reload
curl -s http://localhost:9090/api/v1/rules | jq '.data.groups[].name'
```
