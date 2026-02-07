package com.login_producer_service.model;

public record LoginEvent(
        String userId,
        long timestamp
) {}