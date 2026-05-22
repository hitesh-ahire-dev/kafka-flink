package com.login_producer_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.login_producer_service.model.Customer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/customer")
public class CustomerController {

    private static final Logger log = LoggerFactory.getLogger(CustomerController.class);

    /** Hard cap on bulk produce so a caller cannot ask for an unbounded run. */
    static final int MAX_BULK_COUNT = 100_000;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    /** Shared, bounded executor for bulk produce work. Replaces per-request raw threads. */
    private final ExecutorService bulkExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "bulk-producer");
                t.setDaemon(true);
                return t;
            });

    public CustomerController(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @PreDestroy
    void shutdown() {
        bulkExecutor.shutdown();
        try {
            if (!bulkExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                bulkExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            bulkExecutor.shutdownNow();
        }
    }

    @PostMapping
    public ResponseEntity<String> produce(@RequestBody Customer customer) {
        if (customer == null || customer.id() == null || customer.id().isBlank()) {
            return ResponseEntity.badRequest().body("customer.id is required");
        }
        try {
            String json = mapper.writeValueAsString(customer);
            kafkaTemplate.send("customer-data", customer.id(), json);
            return ResponseEntity.accepted().body("produced");
        } catch (Exception e) {
            log.error("Failed to produce customer id={}", customer.id(), e);
            return ResponseEntity.internalServerError().body("failed to produce: " + e.getMessage());
        }
    }

    // Produce N customer messages asynchronously and return immediately.
    @PostMapping("/produce/{count}")
    public ResponseEntity<String> produceMany(@PathVariable int count) {
        if (count <= 0) {
            return ResponseEntity.badRequest().body("count must be > 0");
        }
        if (count > MAX_BULK_COUNT) {
            return ResponseEntity.badRequest().body("count must be <= " + MAX_BULK_COUNT);
        }

        bulkExecutor.submit(() -> runBulk(count));

        return ResponseEntity.accepted().body("bulk production started: count=" + count);
    }

    private void runBulk(int count) {
        long failures = 0;
        for (int i = 0; i < count; i++) {
            try {
                String id = System.currentTimeMillis() + "-" + i;
                long ts = System.currentTimeMillis();
                String name = "BulkUser-" + (i + 1);
                String email = "bulkuser" + (i + 1) + "@example.com";
                String json = String.format(
                        "{\"id\":\"%s\",\"name\":\"%s\",\"email\":\"%s\",\"createdAt\":%d}",
                        id, name, email, ts);
                kafkaTemplate.send("customer-data", id, json);
            } catch (Exception e) {
                failures++;
                log.warn("Bulk send failure #{}: {}", i, e.getMessage());
            }
            // throttle slightly to avoid overwhelming local broker
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Bulk producer interrupted after {} sends", i);
                return;
            }
        }
        log.info("Bulk produce finished: count={} failures={}", count, failures);
    }
}

