package com.login_producer_service.config;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, String> producerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            MeterRegistry meterRegistry) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(config);
        // Expose Kafka client metrics (kafka_producer_*) via Micrometer.
        factory.addListener(new MicrometerProducerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * Producer + template used by the DLT recoverer. When a value deserialization
     * fails, Spring Kafka forwards the original key (often {@code String}) and the
     * raw {@code byte[]} value to the DLT, so the DLT producer must be able to
     * serialize both. {@link DelegatingByTypeSerializer} dispatches by runtime type.
     */
    @Bean
    public ProducerFactory<Object, Object> dltProducerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            MeterRegistry meterRegistry) {
        Map<Class<?>, Serializer<?>> keyDelegates = new LinkedHashMap<>();
        keyDelegates.put(byte[].class, new ByteArraySerializer());
        keyDelegates.put(String.class, new StringSerializer());
        Map<Class<?>, Serializer<?>> valueDelegates = new LinkedHashMap<>();
        valueDelegates.put(byte[].class, new ByteArraySerializer());
        valueDelegates.put(String.class, new StringSerializer());

        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        DefaultKafkaProducerFactory<Object, Object> factory = new DefaultKafkaProducerFactory<>(config,
                new DelegatingByTypeSerializer(keyDelegates),
                new DelegatingByTypeSerializer(valueDelegates));
        factory.addListener(new MicrometerProducerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    public KafkaTemplate<Object, Object> dltKafkaTemplate(ProducerFactory<Object, Object> dltProducerFactory) {
        return new KafkaTemplate<>(dltProducerFactory);
    }

    /**
     * Singleton AdminClient used by LagController. Creating one per request leaks
     * background threads and TCP connections; share one across the application.
     */
    @Bean(destroyMethod = "close")
    public AdminClient kafkaAdminClient(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        Map<String, Object> props = new HashMap<>();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        return AdminClient.create(props);
    }
}

