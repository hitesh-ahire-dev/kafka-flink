package com.login_producer_service.service;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Service;

/**
 * Simple in-memory metrics collector for consumed records.
 * Records timestamps of each consumed record and allows querying counts within a time window.
 */
@Service
public class ConsumerMetricsService {

    private final ConcurrentLinkedDeque<Long> timestamps = new ConcurrentLinkedDeque<>();
    private final LongAdder total = new LongAdder();

    /**
     * Record a single consumption event (current time).
     */
    public void recordConsumption() {
        long now = System.currentTimeMillis();
        timestamps.addLast(now);
        total.increment();
        // best-effort cleanup to prevent unbounded growth
        cleanOld(now - 120_000); // keep at least last 2 minutes in memory
    }

    /**
     * Return the number of records consumed in the last `seconds` seconds.
     * Counts entries strictly within the window without mutating the deque,
     * because the deque retains a fixed ~2 minute history (see recordConsumption).
     */
    public long countLastSeconds(int seconds) {
        if (seconds <= 0) return 0;
        long cutoff = System.currentTimeMillis() - (seconds * 1000L);
        long count = 0;
        for (Long ts : timestamps) {
            if (ts != null && ts >= cutoff) {
                count++;
            }
        }
        return count;
    }

    /**
     * All-time consumed count (since process start).
     */
    public long getTotalCount() {
        return total.longValue();
    }

    private void cleanOld(long cutoffInclusive) {
        // remove older timestamps from the head
        while (true) {
            Long head = timestamps.peekFirst();
            if (head == null) break;
            if (head < cutoffInclusive) {
                timestamps.pollFirst();
            } else {
                break;
            }
        }
    }
}

