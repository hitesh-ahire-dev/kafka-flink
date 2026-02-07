package com.example.flink.job;

import com.example.flink.model.LoginEvent;
import com.example.flink.util.LoginEventDeserializationSchema;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;

public class LoginFlinkJob {

    public static void main(String[] args) throws Exception {

        Configuration config = new Configuration();
        config.setString("rest.port", "8081"); // Flink UI port

        // ✅ IMPORTANT: Local env WITH Web UI
        StreamExecutionEnvironment env =
                StreamExecutionEnvironment.createLocalEnvironmentWithWebUI(config);

        env.setParallelism(1);

        KafkaSource<LoginEvent> source =
                KafkaSource.<LoginEvent>builder()
                        .setBootstrapServers("localhost:9092")
                        .setTopics("user-login")
                        .setGroupId("login-flink-group")
                        .setStartingOffsets(OffsetsInitializer.latest())
                        .setValueOnlyDeserializer(
                                new LoginEventDeserializationSchema()
                        )
                        .build();

        env.fromSource(
                        source,
                        WatermarkStrategy.noWatermarks(),
                        "Kafka User Login Source"
                )
                .map(event -> "User logged in: " + event)
                .print();

        env.execute("Login Flink Job with Web UI");
    }
}
