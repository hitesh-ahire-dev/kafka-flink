package com.login_producer_service.integration;

import com.login_producer_service.repository.ConsumedCustomerRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Full produce → consume → persist → metric pipeline test using
 * Spring Kafka's EmbeddedKafkaBroker and an in-memory H2 database.
 *
 * Verifies:
 * - A Customer JSON message published to {@code customer-data} is consumed
 *   by the @KafkaListener.
 * - The consumed payload is persisted in {@code customer_consumed} (H2).
 * - The Micrometer counter {@code consumer.processed.count} increments.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(
        partitions = 1,
        topics = {"customer-data", "customer-data.DLT"}
)
class CustomerProduceConsumeTest {

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ConsumedCustomerRepository repository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @BeforeEach
    void cleanup() {
        // Wait for the listener container to be assigned partitions BEFORE producing,
        // otherwise the default 'latest' offset reset causes early messages to be skipped.
        listenerRegistry.getListenerContainers()
                .forEach(c -> ContainerTestUtils.waitForAssignment(c, embeddedKafka.getPartitionsPerTopic()));
        repository.deleteAll();
    }

    @Test
    void singleCustomerMessage_isConsumedPersistedAndCounted() {
        long before = counterValue();
        long createdAt = System.currentTimeMillis();
        String json = String.format(
                "{\"id\":\"it-1\",\"name\":\"IT User\",\"email\":\"it@example.com\",\"createdAt\":%d}", createdAt);

        kafkaTemplate.send("customer-data", "it-1", json);

        await().atMost(20, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    assertThat(repository.count()).isEqualTo(1L);
                    assertThat(counterValue()).isEqualTo(before + 1);
                });

        var stored = repository.findAll().get(0);
        assertThat(stored.getOriginalCreatedAt()).isEqualTo(createdAt);
        assertThat(stored.getPayload()).contains("\"id\":\"it-1\"");
        assertThat(stored.getConsumedAt()).isNotNull();
    }

    @Test
    void burstOfMessages_allConsumedAndPersisted() {
        int n = 25;
        long before = repository.count();
        for (int i = 0; i < n; i++) {
            String key = "burst-" + i;
            String value = String.format(
                    "{\"id\":\"%s\",\"name\":\"Burst%d\",\"email\":\"b%d@example.com\",\"createdAt\":%d}",
                    key, i, i, System.currentTimeMillis());
            kafkaTemplate.send("customer-data", key, value);
        }

        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(250, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(repository.count()).isEqualTo(before + n));
    }

    private long counterValue() {
        Search s = meterRegistry.find("consumer.processed.count").tag("topic", "customer-data");
        Counter counter = s.counter();
        return counter == null ? 0L : (long) counter.count();
    }
}
