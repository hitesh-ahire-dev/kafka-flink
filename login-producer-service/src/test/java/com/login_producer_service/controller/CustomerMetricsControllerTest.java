package com.login_producer_service.controller;

import com.login_producer_service.entity.ConsumedCustomer;
import com.login_producer_service.repository.ConsumedCustomerRepository;
import com.login_producer_service.service.ConsumerMetricsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for B2 — p95 must be computed on the sorted full set
 * (within the bounded window) and the repository call must use the bounded
 * findRecent variant rather than the unbounded findByConsumedAtAfter.
 */
class CustomerMetricsControllerTest {

    @Test
    void getLatency_returnsCorrectPercentilesAndUsesBoundedQuery() {
        long now = Instant.now().toEpochMilli();
        // Latencies in ms: 1000, 10, 200, 50, 5 (intentionally unsorted on input).
        List<ConsumedCustomer> rows = List.of(
                new ConsumedCustomer("{}", Instant.ofEpochMilli(now), now - 1000),
                new ConsumedCustomer("{}", Instant.ofEpochMilli(now), now - 10),
                new ConsumedCustomer("{}", Instant.ofEpochMilli(now), now - 200),
                new ConsumedCustomer("{}", Instant.ofEpochMilli(now), now - 50),
                new ConsumedCustomer("{}", Instant.ofEpochMilli(now), now - 5)
        );

        ConsumedCustomerRepository repo = mock(ConsumedCustomerRepository.class);
        when(repo.findRecent(any(Instant.class), any(Pageable.class))).thenReturn(rows);

        ConsumerMetricsService metrics = new ConsumerMetricsService();
        CustomerMetricsController controller = new CustomerMetricsController(metrics, repo);

        ResponseEntity<Map<String, Object>> resp = controller.getLatency(60, 1000);

        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals(5, body.get("count"));
        assertEquals(5L, body.get("minMs"));
        assertEquals(1000L, body.get("maxMs"));
        // p95 idx = ceil(0.95*5) - 1 = 4 -> sorted[4] = 1000
        assertEquals(1000L, body.get("p95Ms"));
        // avg = 253.0
        assertEquals(253.0, (double) body.get("avgMs"), 0.0001);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repo).findRecent(any(Instant.class), pageableCaptor.capture());
        Pageable used = pageableCaptor.getValue();
        assertTrue(used.getPageSize() <= 10_000);
        assertEquals(0, used.getPageNumber());
        verify(repo, never()).findByConsumedAtAfter(any(Instant.class));
    }

    @Test
    void getLatency_rejectsNonPositiveWindow() {
        ConsumedCustomerRepository repo = mock(ConsumedCustomerRepository.class);
        ConsumerMetricsService metrics = new ConsumerMetricsService();
        CustomerMetricsController controller = new CustomerMetricsController(metrics, repo);

        ResponseEntity<Map<String, Object>> resp = controller.getLatency(0, 1000);
        assertEquals(400, resp.getStatusCode().value());
    }
}
