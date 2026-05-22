package com.login_producer_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import com.login_producer_service.model.Customer;
import com.login_producer_service.service.ConsumerMetricsService;
import io.micrometer.core.instrument.MeterRegistry;
import com.login_producer_service.repository.ConsumedCustomerRepository;
import com.login_producer_service.entity.ConsumedCustomer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;

import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
public class LoginProducerServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(LoginProducerServiceApplication.class, args);
	}

}

/**
 * Kafka consumer configuration for Customer objects.
 * Placed in this file to avoid creating additional files in the workspace.
 */
@Configuration
@EnableKafka
class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, Customer> consumerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            MeterRegistry meterRegistry) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "customer-consumer-group");
        // Wrap deserializers with ErrorHandlingDeserializer so poison messages don't
        // halt the partition: invalid records are surfaced to the DefaultErrorHandler.
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class.getName());
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class.getName());
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, Customer.class.getName());
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.login_producer_service.model");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        DefaultKafkaConsumerFactory<String, Customer> factory = new DefaultKafkaConsumerFactory<>(props);
        // Expose Kafka client metrics (kafka_consumer_records_consumed_total,
        // kafka_consumer_records_lag_max, ...) via Micrometer so Grafana can use them.
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return factory;
    }

    /**
     * Error handler with bounded retries; on exhaustion, the failed record is
     * published to '<topic>.DLT' via DeadLetterPublishingRecoverer.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> dltKafkaTemplate) {
        // The recoverer template uses DelegatingByTypeSerializer so it can serialize
        // both the original String key and the raw byte[] value of a record whose
        // value deserialization failed. We also force the historical '.DLT' suffix
        // (Spring Kafka 4 defaults to '-dlt').
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                dltKafkaTemplate,
                (rec, ex) -> new TopicPartition(rec.topic() + ".DLT", rec.partition()));
        // 3 retries with 1s backoff before publishing to DLT
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Customer> customerKafkaListenerContainerFactory(
            ConsumerFactory<String, Customer> consumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, Customer> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}

/**
 * Simple consumer that listens for a single Customer object and logs it.
 */
@Component
class CustomerConsumer {

    private static final Logger log = LoggerFactory.getLogger(CustomerConsumer.class);
    private final ConsumerMetricsService metrics;
    private final ConsumedCustomerRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MeterRegistry meterRegistry;

    public CustomerConsumer(ConsumerMetricsService metrics, ConsumedCustomerRepository repository, MeterRegistry meterRegistry) {
        this.metrics = metrics;
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(topics = "customer-data", groupId = "customer-consumer-group", containerFactory = "customerKafkaListenerContainerFactory")
    public void consume(Customer customer) {
        // persist, record metric and log
        Long originalCreatedAt = null;
        try {
            String json = objectMapper.writeValueAsString(customer);
            try {
                // Customer.createdAt is epoch millis
                originalCreatedAt = customer.createdAt();
            } catch (Exception ignored) {}
            ConsumedCustomer entity = new ConsumedCustomer(json, Instant.now(), originalCreatedAt);
            repository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to persist consumed customer: {}", e.getMessage());
        }

        try {
            metrics.recordConsumption();
        } catch (Exception e) {
            log.warn("Failed to record consumption metric: {}", e.getMessage());
        }

        // record micrometer metrics: counter and timer (processing latency)
        try {
            meterRegistry.counter("consumer.processed.count", "topic", "customer-data").increment();
            if (originalCreatedAt != null) {
                long latencyMs = Instant.now().toEpochMilli() - originalCreatedAt.longValue();
                meterRegistry.timer("consumer.processing.latency", "topic", "customer-data").record(java.time.Duration.ofMillis(Math.max(0, latencyMs)));
            }
        } catch (Exception e) {
            log.warn("Failed to record micrometer metric: {}", e.getMessage());
        }

        log.info("Consumed Customer from Kafka: {}", customer);
    }
}



