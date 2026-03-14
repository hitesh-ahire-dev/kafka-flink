# Kafka–Flink Streaming Pipeline

This repository demonstrates a simple end-to-end streaming pipeline where a Java Spring producer writes user login events to Apache Kafka and an Apache Flink job consumes and processes those events in real time.

The README below documents the concrete implementation in this repo, how data flows through the system, how to run it locally for development, and recommendations for production. When referring to implementation artifacts, the README names the actual files in this project so you can inspect them directly.

---

## Project snapshot

- Producer: `login-producer-service/src/main/java/com/login_producer_service/service/LoginProducer.java`
- Flink job: `login-flink-job/src/main/java/com/example/flink/job/LoginFlinkJob.java`
- Event model: `login-flink-job/src/main/java/com/example/flink/model/LoginEvent.java`
- Deserializer: `login-flink-job/src/main/java/com/example/flink/util/LoginEventDeserializationSchema.java`

---

## High-level pipeline (source → processing → sink)

Producer (Spring) → Kafka topic `user-login` → Flink `KafkaSource` (group: `login-flink-group`) → Deserialization (JSON → `LoginEvent`) → map() transformation → Print sink (console)

---

## Detailed end-to-end flow

1) How the pipeline starts
- The Flink job entrypoint is `LoginFlinkJob.main(...)`. It creates a local Flink environment with a Web UI on port `8081` using:
  - `StreamExecutionEnvironment.createLocalEnvironmentWithWebUI(config)`
- Parallelism is set to 1 in the job (`env.setParallelism(1)`).
- The producer code (`LoginProducer.sendLogin`) constructs a `LoginEvent` and sends it as JSON to Kafka topic `user-login` using Spring Kafka's `KafkaTemplate`.

2) How Flink connects to Kafka
- The Flink job creates a `KafkaSource<LoginEvent>` with the following important settings (see `LoginFlinkJob`):
  - `.setBootstrapServers("localhost:9092")`
  - `.setTopics("user-login")`
  - `.setGroupId("login-flink-group")`
  - `.setStartingOffsets(OffsetsInitializer.latest())` (job will consume only new messages by default)
  - `.setValueOnlyDeserializer(new LoginEventDeserializationSchema())`
- The source is registered with `env.fromSource(...)` and a `WatermarkStrategy.noWatermarks()` is used in this example (no event-time handling).

3) How data is consumed from Kafka topics
- Flink's Kafka connector establishes network connections to the Kafka bootstrap server, discovers topic partitions, and assigns them to source subtasks.
- Offsets and consumer group semantics are controlled by `.setGroupId(...)`. With `OffsetsInitializer.latest()` the job starts at the end of each partition (new events only). Use `OffsetsInitializer.earliest()` to reprocess historical data on startup.
- The message value bytes are deserialized into `LoginEvent` objects using `LoginEventDeserializationSchema`.

4) How the data is processed or transformed in Flink
- The example applies a single transformation:
  - `.map(event -> "User logged in: " + event)` which converts each `LoginEvent` into a string (the `LoginEvent` class uses Lombok `@ToString`).
- There are no keyed operations, windows, joins, or stateful operators in this simple job.

5) Enrichment or filtering logic
- The current job contains no filtering or enrichment. If you need those:
  - Filtering: add `.filter(e -> <predicate>)`.
  - Enrichment: use `AsyncFunction` or stream-table joins to fetch metadata from external stores and attach it to events.
  - Aggregation/analytics: use `.keyBy(...)` followed by windowing or stateful operators.

6) How the processed data is written to the destination (sink)
- This example uses `.print()` as the sink, which emits results to stdout (and is visible in the local Flink Web UI and logs). This is suitable for development and debugging only.
- For production, replace `.print()` with a durable sink such as `KafkaSink`, JDBC sink, filesystem sink (Parquet/CSV on S3), or connectors like Elasticsearch. Also enable checkpointing and transactional/2PC sinks for exactly-once semantics.

7) Fault-tolerance and operational details
- The job currently does not enable checkpointing. For production, add `env.enableCheckpointing(...)`, configure a state backend, and ensure sinks support transactional writes if you require end-to-end exactly-once guarantees.
- The current source uses group id `login-flink-group` — multiple instances of this job with the same group id will balance partitions among them.
- Because `env.setParallelism(1)` is set, scaling requires increasing parallelism and ensuring the Kafka topic has enough partitions.

---

## Implementation notes (what the code does)

- `LoginEvent` (POJO):
  - Fields: `userId` (String), `timestamp` (long)
- `LoginEventDeserializationSchema`:
  - Extends `AbstractDeserializationSchema<LoginEvent>` and uses Jackson `ObjectMapper` to convert message bytes into `LoginEvent` instances. It throws a `RuntimeException` if deserialization fails.
- `LoginFlinkJob`:
  - Builds a `KafkaSource<LoginEvent>` with `OffsetsInitializer.latest()` and `WatermarkStrategy.noWatermarks()`.
  - Converts incoming `LoginEvent` to a log string with `.map(...)` and writes to console with `.print()`.
- `LoginProducer` (Spring service):
  - Uses `KafkaTemplate<String,String>` to send JSON-serialized `LoginEvent` messages to topic `user-login` using `userId` as the message key.

---

## Running locally (quick guide)

Prerequisites:
- Kafka running locally on `localhost:9092` (or update `LoginFlinkJob` bootstrap server setting).
- Java and Maven for building the projects.

Start the producer (Spring boot) and the Flink job locally (development):

1. Build producer and start it (from `login-producer-service`):

```bash
# from repository root
cd login-producer-service
./mvnw spring-boot:run
```

2. Build and run the Flink job locally (it opens a Web UI at http://localhost:8081):

```bash
cd login-flink-job
./mvnw -q -DskipTests package   # if present; alternatively run from IDE
java -jar target/login-flink-job-1.0-SNAPSHOT.jar
```

Alternatively run the `LoginFlinkJob` main class from your IDE which will produce the same behavior and open the local Web UI.

Notes:
- Ensure Kafka is reachable at the configured `bootstrap.servers`. If Kafka runs in Docker or a remote host, update `LoginFlinkJob` accordingly.
- By default the Flink job is set to `OffsetsInitializer.latest()` so it will process only messages produced after the job starts.

---

## Example Kafka message (JSON)

```json
{
  "userId": "user123",
  "timestamp": 1700000000000
}
```

Expected output in Flink job console/logs (example):

```
User logged in: LoginEvent(userId=user123, timestamp=1700000000000)
```

---

## Suggestions for production improvements

- Enable checkpointing and configure a durable state backend (RocksDB, filesystem, etc.).
- Configure Flink cluster deployment (standalone/YARN/Kubernetes) rather than local environment.
- Use `OffsetsInitializer.earliest()` if you need to replay historical data or manage offsets externally.
- Replace `.print()` with a proper sink (e.g., `KafkaSink` with transactional support) and ensure sink consistency with checkpointing.
- Add error handling for deserialization (dead-letter topic) instead of failing the job immediately.

---

If you want, I can update the Flink job to:
- enable checkpointing and state backend,
- write processed events back to a Kafka topic (`KafkaSink`), or
- demonstrate event-time processing with watermarks and windowed aggregation.

Tell me which enhancement you'd like and I will update the code and run the repository checks.
