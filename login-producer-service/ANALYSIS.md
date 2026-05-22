# login-producer-service — End-to-End Analysis

> Service purpose: expose REST endpoints that publish events to Kafka, consume the
> `customer-data` topic, persist consumed records to MySQL, and expose Prometheus
> metrics consumed by Grafana.

---

## 1. Architecture at a glance

```
                      HTTP                         publish
   client ─────────────────────►  Controllers ───────────────► Kafka topics
                                 (login,customer)              user-login
                                                               customer-data
                                                                    │
                                                                    │ subscribe
                                                                    ▼
                              KafkaListener (CustomerConsumer in
                              LoginProducerServiceApplication.java)
                                          │
                ┌─────────────────────────┼─────────────────────────┐
                ▼                         ▼                         ▼
        ConsumedCustomerRepo     ConsumerMetricsService      MeterRegistry
            (MySQL)              (in-memory deque/counter)   (Micrometer →
                                                              Prometheus)
                                                                    │
                                                                    ▼
                                                              Prometheus :9090
                                                                    │
                                                                    ▼
                                                                 Grafana :3000
```

External integrations

- **Kafka** — bootstrap `localhost:9092`. Topics: `user-login`, `customer-data`.
- **MySQL** — `jdbc:mysql://localhost:3306/todo_app` (table `customer_consumed`).
- **Prometheus** — scrapes `http://localhost:8080/actuator/prometheus`.
- **Grafana** — auto-provisioned dashboard + Prometheus datasource.
- **No Elasticsearch / no scheduler** in this codebase (despite the prompt asking).

---

## 2. Component map

| Layer | Class | Path | Responsibility |
|---|---|---|---|
| Bootstrap | `LoginProducerServiceApplication` | `src/main/java/com/login_producer_service/LoginProducerServiceApplication.java` | Spring Boot main + co-located Kafka consumer config + `CustomerConsumer` listener |
| Producer config | `KafkaProducerConfig` | `config/KafkaProducerConfig.java` | `KafkaTemplate<String,String>` |
| OpenAPI | `OpenApiConfig` | `config/OpenApiConfig.java` | Swagger UI metadata |
| REST | `LoginController` | `controller/LoginController.java` | `POST /login/{userId}` → publish login event |
| REST | `CustomerController` | `controller/CustomerController.java` | `POST /customer`, `POST /customer/produce/{count}` |
| REST | `CustomerMetricsController` | `controller/CustomerMetricsController.java` | `GET /customer/metrics`, `GET /customer/metrics/latency` |
| REST | `LagController` | `controller/LagController.java` | `GET /admin/lag/{groupId}` |
| Service | `LoginProducer` | `service/LoginProducer.java` | Send `LoginEvent` to `user-login` |
| Service | `ConsumerMetricsService` | `service/ConsumerMetricsService.java` | In-memory deque + counter |
| Persistence | `ConsumedCustomer` (entity), `ConsumedCustomerRepository` | `entity/`, `repository/` | JPA persistence of consumed payloads |
| Models | `Customer`, `LoginEvent` | `model/` | Records used as Kafka payloads |

---

## 3. Step-by-step request lifecycles

### 3.1 Login event produce flow

`POST /login/{userId}`

1. `LoginController.login(userId)` is invoked.
2. Calls `LoginProducer.sendLogin(userId)`.
3. `LoginProducer` builds a `LoginEvent(userId, System.currentTimeMillis())`, serializes via Jackson, and `kafkaTemplate.send("user-login", userId, json)`.
4. Returns `"Event sent to Kafka"`. **No consumer for `user-login` exists in this codebase**, so the event sits on the topic until something else consumes it.

### 3.2 Customer produce flow

`POST /customer` (single) and `POST /customer/produce/{count}` (bulk)

1. `CustomerController.produce` serializes body and sends to topic `customer-data`.
2. Bulk variant spawns a raw `Thread` that publishes `count` synthetic messages.
3. Returns `202 Accepted`.

### 3.3 Customer consume flow (the hot path)

The active listener lives **inside** `LoginProducerServiceApplication.java`:

1. `KafkaConsumerConfig` (in the same file) builds a `ConsumerFactory<String, Customer>` using `JsonDeserializer<Customer>` with trusted package `com.login_producer_service.model`. Bootstrap: `localhost:9092`, group: `customer-consumer-group`.
2. `CustomerConsumer.consume(Customer customer)` triggers per record from `customer-data`.
3. Inside `consume`:
   - Serialize `customer` to JSON via Jackson.
   - Read `customer.createdAt()` (epoch millis) into `originalCreatedAt`.
   - Build `ConsumedCustomer(json, Instant.now(), originalCreatedAt)` and `repository.save(...)` → MySQL row in `customer_consumed`.
   - `metrics.recordConsumption()` → push timestamp into in-memory deque + bump `LongAdder`.
   - Increment Micrometer counter `consumer.processed.count{topic="customer-data"}`.
   - If `originalCreatedAt != null`, record latency on Timer `consumer.processing.latency{topic="customer-data"}`.
4. Logs `Consumed Customer from Kafka: ...`.

### 3.4 Metrics read flows

- `GET /customer/metrics?windowSeconds=N` → `ConsumerMetricsService.countLastSeconds(N)` plus `getTotalCount()`. In-memory only; resets on restart.
- `GET /customer/metrics/latency?windowSeconds=N&maxRecords=M` → loads `findByConsumedAtAfter(cutoff)` from MySQL, computes avg / min / max / p95 in Java.

### 3.5 Consumer lag flow

`GET /admin/lag/{groupId}` (typically `customer-consumer-group`)

1. Build a Kafka `AdminClient` per request.
2. `listConsumerGroupOffsets(groupId)` → committed offsets per partition.
3. `listOffsets(... OffsetSpec.latest())` → partition end offsets.
4. `lag = max(0, endOffset - committedOffset)` per partition; aggregate `totalLag`.

### 3.6 Metrics scrape flow (observability)

1. Prometheus hits `GET /actuator/prometheus` every 15 s (`prometheus.yml`).
2. Spring Boot Actuator exposes Micrometer state, including:
   - `consumer_processed_count_total{topic="customer-data"}`
   - `consumer_processing_latency_seconds_{count,sum,max}` and `_bucket{le=...}` (histogram is enabled in `application.yml`).
   - Standard JVM, HTTP server, DataSource, Kafka client, Tomcat metrics.
3. Grafana queries Prometheus on dashboard refresh.

---

## 4. Issues found, by severity

### 4.1 Bugs / correctness

#### B1 — `ConsumerMetricsService.countLastSeconds` ignores the window (HIGH)

```@/Users/hiteshahire/Documents/java-workspace/kafka+flink/login-producer-service/src/main/java/com/login_producer_service/service/ConsumerMetricsService.java:32-38
    public long countLastSeconds(int seconds) {
        if (seconds <= 0) return 0;
        long now = System.currentTimeMillis();
        long cutoff = now - (seconds * 1000L);
        cleanOld(cutoff);
        return timestamps.size();
    }
```

After `cleanOld(cutoff)`, every remaining timestamp is `>= cutoff`, but `recordConsumption` does `cleanOld(now - 120_000)` on every write — meaning the deque **always retains up to ~2 minutes of timestamps**, regardless of the caller's `seconds` value. So `/customer/metrics?windowSeconds=10` returns the 2-minute count, not the 10-second count.

**Fix** — count entries strictly within the requested window without mutating state for smaller queries:

```java
public long countLastSeconds(int seconds) {
    if (seconds <= 0) return 0;
    long cutoff = System.currentTimeMillis() - seconds * 1000L;
    long count = 0;
    // iterate snapshot; deque is concurrent-safe for traversal
    for (Long ts : timestamps) {
        if (ts >= cutoff) count++;
    }
    return count;
}
```

#### B2 — `CustomerMetricsController` truncates **after sorting cutoff is missing** and percentile is biased (MEDIUM)

```@/Users/hiteshahire/Documents/java-workspace/kafka+flink/login-producer-service/src/main/java/com/login_producer_service/controller/CustomerMetricsController.java:88-106
        if (latencies.size() > maxRecords) {
            latencies = latencies.subList(0, maxRecords);
        }

        java.util.Collections.sort(latencies);
```

Two problems:

1. The truncation `subList(0, maxRecords)` happens **before** sort and uses an arbitrary (insertion-order) prefix of the list — the resulting p95 is computed over a non-random sample, biased by whatever order JPA returned.
2. `findByConsumedAtAfter` has no `LIMIT` and no `ORDER BY` — for a busy topic this loads the entire post-cutoff history into memory.

**Fix** — either compute p95 over the full set (already in memory) before truncation, or push the limit and ordering into the repository:

```java
@Query("select c from ConsumedCustomer c where c.consumedAt > :cutoff order by c.consumedAt desc")
List<ConsumedCustomer> findRecent(@Param("cutoff") Instant cutoff, Pageable pageable);
// caller: repo.findRecent(cutoff, PageRequest.of(0, maxRecords))
```

Then sort and percentile-compute on the bounded result.

#### B3 — Kafka `AdminClient` created per request (MEDIUM)

```@/Users/hiteshahire/Documents/java-workspace/kafka+flink/login-producer-service/src/main/java/com/login_producer_service/controller/LagController.java:44-67
        try (AdminClient admin = AdminClient.create(props)) { ... }
```

Each `GET /admin/lag/{group}` opens a new `AdminClient`, which spawns its own metadata fetcher threads and TCP connections, then closes them. Under any monitoring scrape this is a real overhead. Promote to a singleton bean.

```java
@Bean
public AdminClient kafkaAdminClient(@Value("${spring.kafka.bootstrap-servers}") String bs) {
    return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bs));
}
```

#### B4 — Bulk producer uses raw `Thread`, no error propagation (MEDIUM)

```@/Users/hiteshahire/Documents/java-workspace/kafka+flink/login-producer-service/src/main/java/com/login_producer_service/controller/CustomerController.java:43-59
        new Thread(() -> {
            for (int i = 0; i < count; i++) { ... kafkaTemplate.send(...); }
        }, "bulk-producer-thread").start();
```

Issues:

- No bound on `count` → caller can pass `Integer.MAX_VALUE`.
- Errors only `System.err.println`, never reported.
- New thread per request leaks under load.
- `kafkaTemplate.send(...).get()` is not awaited; a fast shutdown can drop messages.

**Fix** — bound `count`, use a small `ExecutorService` bean, log via SLF4J, and await the future for synchronous correctness or accept the asynchronous semantics explicitly.

#### B5 — Counter and metrics-deque can drift apart on partial failure (LOW)

In `CustomerConsumer.consume`, the persistence block, the in-memory metric, and the Micrometer block are wrapped in **separate** try/catch. If `repository.save` throws but Micrometer succeeds, the counter says "processed" while no row was saved. The opposite is also possible. Document or unify (single try) so observability and persistence agree.

#### B6 — `LoginEvent` records are produced but never consumed (LOW)

`POST /login/{userId}` writes to `user-login` but no `@KafkaListener` exists for that topic. Dead end unless an external consumer is intended. Either delete the producer or add a consumer.

#### B7 — Hard-coded bootstrap servers in `KafkaProducerConfig` (LOW)

```@/Users/hiteshahire/Documents/java-workspace/kafka+flink/login-producer-service/src/main/java/com/login_producer_service/config/KafkaProducerConfig.java:19
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
```

`application.yml` already has `spring.kafka.bootstrap-servers: localhost:9092` and Spring Boot's auto-config would build a `KafkaTemplate` from it. The hand-rolled `KafkaProducerConfig` ignores the property and pins to localhost — environments cannot override. Either delete the bean (let Spring Boot auto-config do it) or read the value via `@Value("${spring.kafka.bootstrap-servers}")`.

The same hard-coding occurs in `KafkaConsumerConfig` inside `LoginProducerServiceApplication.java` (`localhost:9092`).

### 4.2 Null handling / validation

| # | Location | Issue | Fix |
|---|---|---|---|
| N1 | `CustomerController.produce(@RequestBody Customer customer)` | No `@Valid`, no null check on `customer.id()` — used as Kafka key, will NPE on null id | Add `@NotNull` constraints on the `Customer` record / validate manually |
| N2 | Bulk endpoint | `count` not bounded | Reject `count > 100_000` or similar |
| N3 | `CustomerConsumer.consume` | `customer` not null-checked | Add guard; deserializer may yield odd states for malformed JSON |
| N4 | `CustomerMetricsController.getLatency` | `c.getConsumedAt()` could be null if persisted incorrectly; calls `.toEpochMilli()` directly | Null-check before subtraction |
| N5 | `LoginController.login` | Throws raw `Exception` to controller signature | Map to a controlled error response |

### 4.3 Performance / resource

- **P1 — JPA write per Kafka record.** `repository.save(entity)` per message; under load this is the bottleneck. Batch via `saveAll` after polling N records, or use `@Transactional` batching with a `@KafkaListener(batch=true)` container factory.
- **P2 — `ObjectMapper` re-instantiated per consumer.** Trivial here, but should be a singleton bean.
- **P3 — `findByConsumedAtAfter` returns unbounded list.** Combined with B2, can OOM. Index `consumed_at` and paginate.
- **P4 — In-memory `ConcurrentLinkedDeque<Long>`** in `ConsumerMetricsService` grows for 2 min before cleanup; at 10 k msg/s that's 1.2 M Long objects (~30 MB). Fine for demos, replace with Micrometer's own counters/timers (already present) and remove this service.
- **P5 — `consume` is single-threaded** because `ConcurrentKafkaListenerContainerFactory` uses default concurrency=1. For higher throughput, set `factory.setConcurrency(N)` to ≤ partition count and increase partition count of `customer-data`.

### 4.4 Reliability / consumer config

- **R1 — No retry / DLT.** `JsonDeserializer` poison messages will block the partition forever. Add `ErrorHandlingDeserializer` wrapping `JsonDeserializer`, plus a `DefaultErrorHandler` with backoff and a Dead-Letter Topic recoverer.
- **R2 — Auto-commit semantics.** Default Spring Kafka acknowledges after the listener returns successfully. If `repository.save` fails (caught and swallowed), the offset is still committed → silent data loss. Either let the exception propagate (so the framework re-delivers) or write to DLT explicitly.
- **R3 — Group `customer-consumer-group` is shared with offsets across deployments.** Fine in production; just be aware on local resets you may need `auto-offset-reset=earliest`.

### 4.5 Security / config hygiene

- **S1 — Hard-coded DB credentials** in `application.yml` (`root/root1234`). Move to env vars / secret manager.
- **S2 — Actuator exposure.** `management.endpoints.web.exposure.include: health,info,prometheus` is fine, but in non-local environments protect `/actuator/prometheus` behind network ACLs or basic auth.
- **S3 — `dialect: org.hibernate.dialect.MySQLDialect`** is OK for Hibernate 6+; fine.

---

## 5. Suggested cleanup edits (priority order)

1. **B1 fix** — `ConsumerMetricsService.countLastSeconds` window honored.
2. **B2 fix** — paginated `findRecent` + sort-then-truncate p95.
3. **B3 fix** — `AdminClient` singleton bean.
4. **R1 fix** — `ErrorHandlingDeserializer` + `DefaultErrorHandler` with DLT `customer-data.DLT`.
5. **B7 fix** — drive bootstrap servers from properties.
6. **B4 fix** — bounded bulk producer with shared executor + SLF4J.
7. **N1 fix** — Bean Validation on `Customer`.

I have not made these code edits yet; tell me which to apply and I'll implement them with regression tests.

---

## 6. Documentation (refreshed)

### 6.1 Quickstart

Prereqs: Java 17, Maven, Kafka on `:9092`, MySQL on `:3306` (DB `todo_app`, user `root` / `root1234`), Prometheus + Grafana (Docker compose or native).

```bash
./mvnw clean package -DskipTests
./mvnw spring-boot:run
```

Native observability stack (no Docker):

```bash
brew services start prometheus   # uses /opt/homebrew/etc/prometheus.args
brew services start grafana
# Or run prometheus pointing at this repo's config:
prometheus --config.file=$PWD/prometheus/prometheus.yml --storage.tsdb.path=/tmp/prom-data
```

### 6.2 REST API

| Method | Path | Purpose |
|---|---|---|
| POST | `/login/{userId}` | Produce a `LoginEvent` to topic `user-login` |
| POST | `/customer` | Produce a `Customer` to topic `customer-data` (JSON body) |
| POST | `/customer/produce/{count}` | Asynchronously produce N synthetic customer messages |
| GET | `/customer/metrics?windowSeconds=N` | In-memory consumed-count over window + lifetime total |
| GET | `/customer/metrics/latency?windowSeconds=N&maxRecords=M` | Avg / min / max / p95 latency from MySQL |
| GET | `/admin/lag/{groupId}` | Per-partition committed/end offset and lag |
| GET | `/actuator/health` | Liveness/readiness |
| GET | `/actuator/prometheus` | Prometheus scrape endpoint |
| GET | `/swagger-ui.html` | OpenAPI UI |

### 6.3 Kafka topics

| Topic | Direction | Payload | Schema |
|---|---|---|---|
| `user-login` | produced only | JSON | `LoginEvent{userId, timestamp}` |
| `customer-data` | produced and consumed | JSON | `Customer{id, name, email, createdAt}` |

### 6.4 Persistence schema

Table `customer_consumed` (auto-created by `ddl-auto: update`):

| Column | Type | Notes |
|---|---|---|
| `id` | bigint, identity | PK |
| `payload` | TEXT | full JSON of the consumed `Customer` |
| `consumed_at` | datetime(6) | wall clock when consumed |
| `original_created_at` | bigint | epoch millis from producer |

Recommended index: `CREATE INDEX idx_customer_consumed_consumed_at ON customer_consumed(consumed_at);`

---

## 7. Grafana monitoring playbook (step-wise)

### 7.1 Metrics to monitor

**Custom (already emitted by this service):**

- `consumer_processed_count_total{topic}` — Counter, processed messages.
- `consumer_processing_latency_seconds_{count,sum,max,bucket}{topic}` — Timer with histogram (enabled in `application.yml`).

**Built-in (Spring Boot Actuator + Micrometer):**

- HTTP server latency: `http_server_requests_seconds_{count,sum,bucket}{uri,method,status,outcome}`.
- JVM: `jvm_memory_used_bytes`, `jvm_gc_pause_seconds_{count,sum}`, `jvm_threads_live_threads`, `process_cpu_usage`, `system_cpu_usage`.
- Tomcat: `tomcat_threads_busy_threads`, `tomcat_threads_current_threads`.
- DataSource (HikariCP): `hikaricp_connections_active`, `hikaricp_connections_pending`, `hikaricp_connections_timeout_total`.
- Kafka client (consumer): `kafka_consumer_records_consumed_total`, `kafka_consumer_records_lag_max` (per `client-id`/`topic`), `kafka_consumer_fetch_latency_avg`. Ensure `management.metrics.enable.kafka=true` (you already set `enable.all=true`).
- Logback: `logback_events_total{level}` for error-rate.

### 7.2 Logs to track

The service uses SLF4J (`org.slf4j.Logger`). Important log lines:

- `CustomerConsumer` INFO: `Consumed Customer from Kafka: ...`
- `CustomerConsumer` WARN: `Failed to persist consumed customer`, `Failed to record consumption metric`, `Failed to record micrometer metric`.
- Kafka client framework logs at WARN/ERROR (rebalance, broker disconnects).
- HikariCP WARN/ERROR (pool exhaustion).

For production, ship logs to ELK / Loki and add a log-based alert on `level=ERROR` rate above threshold.

### 7.3 Suggested dashboards

**Dashboard A — Service overview**

| Panel | PromQL |
|---|---|
| Request rate by URI | `sum by (uri) (rate(http_server_requests_seconds_count[1m]))` |
| Error rate (5xx) | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))` |
| API p95 latency by URI (ms) | `histogram_quantile(0.95, sum by (uri, le) (rate(http_server_requests_seconds_bucket[5m]))) * 1000` |
| JVM heap used | `sum(jvm_memory_used_bytes{area="heap"})` |
| GC pause time / sec | `sum(rate(jvm_gc_pause_seconds_sum[1m]))` |
| Threads live | `jvm_threads_live_threads` |
| CPU usage | `process_cpu_usage` |

**Dashboard B — Kafka consumer (already provisioned for this repo)**

| Panel | PromQL |
|---|---|
| Consumed rate by topic | `sum by (topic) (rate(consumer_processed_count_total[1m]))` |
| Avg processing latency (ms) | `(rate(consumer_processing_latency_seconds_sum{topic="customer-data"}[5m]) / rate(consumer_processing_latency_seconds_count{topic="customer-data"}[5m])) * 1000` |
| p95 processing latency (ms) | `histogram_quantile(0.95, sum(rate(consumer_processing_latency_seconds_bucket{topic="customer-data"}[5m])) by (le)) * 1000` |
| Total processed | `sum(consumer_processed_count_total{topic="customer-data"})` |
| Consumer lag (max) | `max by (topic) (kafka_consumer_records_lag_max)` |
| Records consumed by client | `sum by (client_id) (rate(kafka_consumer_records_consumed_total[1m]))` |

**Dashboard C — Datasource (HikariCP)**

| Panel | PromQL |
|---|---|
| Active connections | `hikaricp_connections_active` |
| Pending threads | `hikaricp_connections_pending` |
| Connection timeouts (rate) | `rate(hikaricp_connections_timeout_total[5m])` |

### 7.4 Alerts to configure

Set in Grafana Alerting or Prometheus rules.

| Alert | Expression | Severity | Note |
|---|---|---|---|
| Service down | `up{job="spring-boot-app"} == 0` for 2m | critical | Either app crashed or scrape failing |
| HTTP 5xx burst | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) > 1` for 5m | warning | tune to baseline |
| API p95 latency high | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket[5m]))) > 1` for 10m | warning | >1 s p95 |
| Consumer lag high | `max by (topic) (kafka_consumer_records_lag_max) > 1000` for 5m | warning | per topic |
| No messages consumed | `rate(consumer_processed_count_total{topic="customer-data"}[5m]) == 0` for 10m | warning | only when traffic expected |
| Processing latency high | `histogram_quantile(0.95, sum(rate(consumer_processing_latency_seconds_bucket[5m])) by (le)) > 1` for 5m | warning | >1 s p95 |
| Heap pressure | `sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"}) > 0.85` for 10m | warning | tune heap |
| GC pause time | `rate(jvm_gc_pause_seconds_sum[5m]) > 0.5` for 5m | warning | >0.5 s/s GC |
| DB pool saturation | `hikaricp_connections_pending > 0` for 5m | warning | threads waiting for connections |
| Logback errors | `sum(rate(logback_events_total{level="error"}[5m])) > 0.5` for 5m | warning | error log rate |

### 7.5 Setup steps (the path that worked on this machine)

1. Install Prometheus + Grafana (`brew install prometheus grafana`).
2. Start Grafana (`brew services start grafana`); login `admin` / `admin` at http://localhost:3000.
3. Start Prometheus pointing at this repo's config:
   ```bash
   prometheus --config.file=$PWD/prometheus/prometheus.yml \
              --storage.tsdb.path=/tmp/prom-data \
              --web.listen-address=:9090
   ```
4. Provision the datasource (one-time) — either keep Grafana running and create via UI, or use the API:
   ```bash
   curl -u admin:admin -X POST -H "Content-Type: application/json" \
     http://localhost:3000/api/datasources \
     -d '{"name":"Prometheus","uid":"prometheus","type":"prometheus","access":"proxy","url":"http://localhost:9090","isDefault":true}'
   ```
5. Import dashboard:
   ```bash
   DASH=$(cat grafana/dashboards/login-producer-service-dashboard.json)
   curl -u admin:admin -X POST -H "Content-Type: application/json" \
     http://localhost:3000/api/dashboards/db \
     -d "{\"dashboard\": $DASH, \"overwrite\": true}"
   ```
6. Generate traffic to materialize Micrometer meters:
   ```bash
   curl -X POST http://localhost:8080/customer/produce/500
   ```
7. Open http://localhost:3000/d/login-producer-service-dashboard and set time range to "Last 15 minutes".

### 7.6 "No data" troubleshooting checklist

- `curl http://localhost:8080/actuator/prometheus | grep ^consumer_` — meters appear only after the first consumed message; produce a few first.
- http://localhost:9090/targets → `spring-boot-app` must be **UP**.
- Grafana datasource uid must match dashboard panels (`uid: prometheus`).
- Time range too short → widen.
- Consumer not running → verify `customer-consumer-group` lag via `GET /admin/lag/customer-consumer-group`.
