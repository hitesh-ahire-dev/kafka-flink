package com.login_producer_service.model;

/**
 * Simple Customer data object consumed from Kafka.
 */
public record Customer(
        String id,
        String name,
        String email,
        long createdAt
) {}

