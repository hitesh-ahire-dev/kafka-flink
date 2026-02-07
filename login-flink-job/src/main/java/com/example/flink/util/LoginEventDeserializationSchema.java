package com.example.flink.util;

import com.example.flink.model.LoginEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.serialization.AbstractDeserializationSchema;

import java.io.IOException;

public class LoginEventDeserializationSchema
        extends AbstractDeserializationSchema<LoginEvent> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
        public LoginEvent deserialize(byte[] message) {
        try {
            return objectMapper.readValue(message, LoginEvent.class);
        } catch (IOException e) {
            throw new RuntimeException("Error deserializing LoginEvent", e);
        }
    }
}

