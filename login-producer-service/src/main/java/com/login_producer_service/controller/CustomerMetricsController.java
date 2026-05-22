package com.login_producer_service.controller;

import com.login_producer_service.service.ConsumerMetricsService;
import com.login_producer_service.repository.ConsumedCustomerRepository;
import com.login_producer_service.entity.ConsumedCustomer;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/customer/metrics")
public class CustomerMetricsController {

    private final ConsumerMetricsService metricsService;
    private final ConsumedCustomerRepository consumedRepository;

    public CustomerMetricsController(ConsumerMetricsService metricsService, ConsumedCustomerRepository consumedRepository) {
        this.metricsService = metricsService;
        this.consumedRepository = consumedRepository;
    }

    /**
     * Return number of consumed records in the last `windowSeconds` seconds (default 60).
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getMetrics(@RequestParam(name = "windowSeconds", defaultValue = "60") int windowSeconds) {
        if (windowSeconds <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "windowSeconds must be > 0"));
        }

        long count = metricsService.countLastSeconds(windowSeconds);
        long total = metricsService.getTotalCount();

        Map<String, Object> resp = new HashMap<>();
        resp.put("windowSeconds", windowSeconds);
        resp.put("countInWindow", count);
        resp.put("totalConsumed", total);
        resp.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(resp);
    }

    /**
     * Return latency statistics (ms) between producer's createdAt and consumption time.
     * windowSeconds: last N seconds to consider (default 60)
     * maxRecords: limit number of records processed for percentile computation (default 1000)
     */
    @GetMapping("/latency")
    public ResponseEntity<Map<String, Object>> getLatency(@RequestParam(name = "windowSeconds", defaultValue = "60") int windowSeconds,
                                                          @RequestParam(name = "maxRecords", defaultValue = "1000") int maxRecords) {
        if (windowSeconds <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "windowSeconds must be > 0"));
        }
        if (maxRecords <= 0) maxRecords = 1000;
        // hard cap to protect memory/CPU even if a caller asks for a huge value
        int effectiveMax = Math.min(maxRecords, 10_000);

        java.time.Instant cutoff = java.time.Instant.now().minusSeconds(windowSeconds);
        // bounded query: most recent N records within the window
        java.util.List<ConsumedCustomer> recent =
                consumedRepository.findRecent(cutoff, PageRequest.of(0, effectiveMax));
        if (recent.isEmpty()) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("windowSeconds", windowSeconds);
            resp.put("count", 0);
            resp.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(resp);
        }

        // compute latencies in ms using originalCreatedAt (epoch millis) and consumedAt
        java.util.List<Long> latencies = new java.util.ArrayList<>(recent.size());
        for (ConsumedCustomer c : recent) {
            Long created = c.getOriginalCreatedAt();
            if (created == null) continue;
            java.time.Instant consumedAt = c.getConsumedAt();
            if (consumedAt == null) continue;
            long latency = consumedAt.toEpochMilli() - created;
            latencies.add(latency);
        }

        if (latencies.isEmpty()) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("windowSeconds", windowSeconds);
            resp.put("count", 0);
            resp.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(resp);
        }

        java.util.Collections.sort(latencies);
        long sum = 0L;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (Long v : latencies) {
            sum += v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double avg = (double) sum / latencies.size();
        // p95
        int idx95 = (int) Math.ceil(0.95 * latencies.size()) - 1;
        if (idx95 < 0) idx95 = 0;
        long p95 = latencies.get(Math.min(idx95, latencies.size() - 1));

        Map<String, Object> resp = new HashMap<>();
        resp.put("windowSeconds", windowSeconds);
        resp.put("count", latencies.size());
        resp.put("avgMs", avg);
        resp.put("minMs", min);
        resp.put("maxMs", max);
        resp.put("p95Ms", p95);
        resp.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(resp);
    }
}

