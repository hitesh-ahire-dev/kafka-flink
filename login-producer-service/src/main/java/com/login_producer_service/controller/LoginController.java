package com.login_producer_service.controller;

import com.login_producer_service.service.LoginProducer;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/login")
public class LoginController {

    private final LoginProducer producer;

    public LoginController(LoginProducer producer) {
        this.producer = producer;
    }

    @PostMapping("/{userId}")
    public String login(@PathVariable String userId) throws Exception {
        producer.sendLogin(userId);
        return "Event sent to Kafka";
    }
}