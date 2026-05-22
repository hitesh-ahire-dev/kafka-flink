package com.login_producer_service.loadtest;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standalone Kafka load test runner.
 *
 * Activate by running with the {@code loadtest} Spring profile, e.g.:
 *
 * <pre>
 * ./mvnw spring-boot:run -Dspring-boot.run.profiles=loadtest \
 *   -Dspring-boot.run.arguments="--loadtest.count=100000 --loadtest.topic=customer-data"
 * </pre>
 *
 * Or with a packaged jar:
 *
 * <pre>
 * java -jar login-producer-service.jar \
 *   --spring.profiles.active=loadtest \
 *   --loadtest.count=100000 \
 *   --loadtest.topic=customer-data \
 *   --loadtest.acks=1
 * </pre>
 *
 * Producer is tuned for high throughput (lz4, large batch, linger 20ms). The runner
 * sends records asynchronously, fans in all callbacks via CompletableFuture, then
 * prints throughput, p50 / p95 / p99 client-side acknowledgement latency, and
 * exits the JVM with code 0 on success / 1 on partial failure.
 */
@Component
@Profile("loadtest")
public class KafkaLoadTestRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(KafkaLoadTestRunner.class);

    private final ConfigurableApplicationContext context;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${loadtest.count:100000}")
    private int count;

    @Value("${loadtest.topic:customer-data}")
    private String topic;

    @Value("${loadtest.acks:1}")
    private String acks;

    @Value("${loadtest.lingerMs:20}")
    private int lingerMs;

    @Value("${loadtest.batchSizeBytes:65536}")
    private int batchSize;

    @Value("${loadtest.bufferMemoryBytes:67108864}")
    private long bufferMemory;

    @Value("${loadtest.compression:lz4}")
    private String compression;

    public KafkaLoadTestRunner(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void run(String... args) {
        log.info("KafkaLoadTestRunner starting: count={} topic={} bootstrap={} acks={} linger={}ms batch={}B compression={}",
                count, topic, bootstrapServers, acks, lingerMs, batchSize, compression);

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, acks);
        props.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, bufferMemory);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, compression);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "load-test-producer");

        AtomicLong sent = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        // pre-size: latencies in nanos; only sample first 50k for percentile to bound memory
        int sampleSize = Math.min(count, 50_000);
        long[] latNanos = new long[sampleSize];
        AtomicLong sampleIdx = new AtomicLong();

        CompletableFuture<?>[] futures = new CompletableFuture<?>[count];

        long t0 = System.nanoTime();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            for (int i = 0; i < count; i++) {
                String key = "loadtest-" + i;
                long createdAt = System.currentTimeMillis();
                String value = String.format(
                        "{\"id\":\"%s\",\"name\":\"LoadUser-%d\",\"email\":\"load%d@example.com\",\"createdAt\":%d}",
                        key, i, i, createdAt);

                long sendStart = System.nanoTime();
                CompletableFuture<RecordMetadata> f = new CompletableFuture<>();
                producer.send(new ProducerRecord<>(topic, key, value), (md, ex) -> {
                    if (ex == null) {
                        sent.incrementAndGet();
                        long idx = sampleIdx.getAndIncrement();
                        if (idx < latNanos.length) {
                            latNanos[(int) idx] = System.nanoTime() - sendStart;
                        }
                        f.complete(md);
                    } else {
                        failed.incrementAndGet();
                        f.completeExceptionally(ex);
                    }
                });
                futures[i] = f;
            }
            log.info("All {} sends submitted, awaiting acknowledgements...", count);
            // join; tolerate per-record failures (counted separately)
            CompletableFuture.allOf(futures).exceptionally(t -> null).join();
            producer.flush();
        }
        long totalNanos = System.nanoTime() - t0;
        double seconds = totalNanos / 1_000_000_000.0;
        double throughput = sent.get() / seconds;

        long[] sortedSample = trimAndSort(latNanos, (int) Math.min(sampleIdx.get(), latNanos.length));
        Map<String, Object> stats = new HashMap<>();
        stats.put("sent", sent.get());
        stats.put("failed", failed.get());
        stats.put("durationSec", seconds);
        stats.put("throughputMsgPerSec", throughput);
        stats.put("p50Ms", percentileMs(sortedSample, 0.50));
        stats.put("p95Ms", percentileMs(sortedSample, 0.95));
        stats.put("p99Ms", percentileMs(sortedSample, 0.99));
        stats.put("maxMs", sortedSample.length == 0 ? 0.0 : sortedSample[sortedSample.length - 1] / 1_000_000.0);

        log.info("====== Load test result ======");
        stats.forEach((k, v) -> log.info("  {} = {}", k, v));
        log.info("==============================");

        int exitCode = failed.get() == 0 ? 0 : 1;
        // gracefully shut down the Spring context, then exit
        new Thread(() -> System.exit(exitCode), "loadtest-exit").start();
        context.close();
    }

    private static long[] trimAndSort(long[] arr, int len) {
        long[] out = new long[Math.max(0, len)];
        System.arraycopy(arr, 0, out, 0, out.length);
        java.util.Arrays.sort(out);
        return out;
    }

    private static double percentileMs(long[] sortedNanos, double p) {
        if (sortedNanos.length == 0) return 0.0;
        int idx = (int) Math.ceil(p * sortedNanos.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sortedNanos.length) idx = sortedNanos.length - 1;
        return sortedNanos[idx] / 1_000_000.0;
    }
}
