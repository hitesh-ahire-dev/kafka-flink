package com.login_producer_service.service;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.login_producer_service.model.LoginEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class LoginProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public LoginProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendLogin(String userId) throws Exception {
        LoginEvent event =
                new LoginEvent(userId, System.currentTimeMillis());

        kafkaTemplate.send(
                "user-login",
                userId,
                mapper.writeValueAsString(event)
        );
    }
}

