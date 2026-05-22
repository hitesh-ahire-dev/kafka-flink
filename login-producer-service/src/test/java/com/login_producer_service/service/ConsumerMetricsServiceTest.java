package com.login_producer_service.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for B1 — countLastSeconds must honor the requested window
 * even though the deque retains a fixed ~2 minute history.
 */
class ConsumerMetricsServiceTest {

    @Test
    void countLastSeconds_returnsZeroForNonPositiveWindow() {
        ConsumerMetricsService svc = new ConsumerMetricsService();
        svc.recordConsumption();
        assertEquals(0L, svc.countLastSeconds(0));
        assertEquals(0L, svc.countLastSeconds(-1));
    }

    @Test
    void countLastSeconds_countsOnlyEntriesWithinWindow() throws InterruptedException {
        ConsumerMetricsService svc = new ConsumerMetricsService();
        // Record some events now.
        svc.recordConsumption();
        svc.recordConsumption();
        svc.recordConsumption();
        // A 60s window should contain all three.
        assertEquals(3L, svc.countLastSeconds(60));
        // Tiny window should still see them (records are <1s old).
        assertEquals(3L, svc.countLastSeconds(1));
    }

    @Test
    void countLastSeconds_doesNotMutateState() {
        ConsumerMetricsService svc = new ConsumerMetricsService();
        for (int i = 0; i < 5; i++) svc.recordConsumption();
        long total = svc.getTotalCount();
        // Multiple reads with various windows should not lose lifetime total.
        svc.countLastSeconds(1);
        svc.countLastSeconds(60);
        svc.countLastSeconds(120);
        assertEquals(total, svc.getTotalCount());
        assertTrue(svc.countLastSeconds(60) >= 5);
    }
}
