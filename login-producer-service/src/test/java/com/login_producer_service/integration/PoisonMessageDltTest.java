package com.login_producer_service.integration;

import com.login_producer_service.repository.ConsumedCustomerRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies the ErrorHandlingDeserializer + DefaultErrorHandler pipeline (R1):
 * a malformed JSON record on {@code customer-data} must be retried and then
 * forwarded to {@code customer-data.DLT} without halting the partition or
 * being persisted to the database.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(
        partitions = 1,
        topics = {"customer-data", "customer-data.DLT"}
)
class PoisonMessageDltTest {

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ConsumedCustomerRepository repository;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @BeforeEach
    void waitForListener() {
        listenerRegistry.getListenerContainers()
                .forEach(c -> ContainerTestUtils.waitForAssignment(c, embeddedKafka.getPartitionsPerTopic()));
    }

    @Test
    void poisonRecord_isRoutedToDltAndDoesNotHaltPartition() {
        long initialRows = repository.count();

        // Send malformed JSON to trigger ErrorHandlingDeserializer.
        kafkaTemplate.send("customer-data", "poison-1", "{not-json");

        // Then send a valid record after the poison one to verify the partition is not stuck.
        long createdAt = System.currentTimeMillis();
        String validJson = String.format(
                "{\"id\":\"after-poison\",\"name\":\"OK\",\"email\":\"ok@example.com\",\"createdAt\":%d}",
                createdAt);
        kafkaTemplate.send("customer-data", "after-poison", validJson);

        // The valid one must still be persisted -> partition not stalled.
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(250, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(repository.count()).isEqualTo(initialRows + 1));
        assertThat(repository.findAll().stream().anyMatch(c -> c.getPayload().contains("after-poison"))).isTrue();

        // The poison record must end up on the DLT (after retries).
        AtomicReference<ConsumerRecord<String, String>> dltRecord = new AtomicReference<>();
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    ConsumerRecord<String, String> rec = pollFirst("customer-data.DLT", "dlt-test-group");
                    if (rec != null) dltRecord.set(rec);
                    assertThat(dltRecord.get()).isNotNull();
                });
        assertThat(dltRecord.get().key()).isEqualTo("poison-1");
        assertThat(dltRecord.get().value()).isEqualTo("{not-json");
    }

    private ConsumerRecord<String, String> pollFirst(String topic, String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
            for (ConsumerRecord<String, String> r : records) return r;
            return null;
        }
    }
}
