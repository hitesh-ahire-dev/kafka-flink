package com.login_producer_service.controller;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Simple controller to report consumer group lag using Kafka AdminClient.
 * Example: GET /admin/lag/customer-consumer-group
 */
@RestController
@RequestMapping("/admin")
public class LagController {

    private final AdminClient adminClient;
    private final String bootstrapServers;

    public LagController(AdminClient adminClient,
                         @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        this.adminClient = adminClient;
        this.bootstrapServers = bootstrapServers;
    }

    @GetMapping("/lag/{groupId}")
    public ResponseEntity<?> getLag(@PathVariable String groupId) {
        try {
            Map<TopicPartition, OffsetAndMetadata> committed = adminClient.listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata().get();

            if (committed == null || committed.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Collections.singletonMap("message", "No offsets found for group: " + groupId));
            }

            Map<TopicPartition, org.apache.kafka.clients.admin.OffsetSpec> request = new HashMap<>();
            for (TopicPartition tp : committed.keySet()) {
                request.put(tp, org.apache.kafka.clients.admin.OffsetSpec.latest());
            }

            ListOffsetsResult listOffsetsResult = adminClient.listOffsets(request);
            Map<TopicPartition, ListOffsetsResultInfo> offsets = listOffsetsResult.all().get();

            Map<String, Object> report = new HashMap<>();

            long totalLag = 0L;
            for (Map.Entry<TopicPartition, OffsetAndMetadata> e : committed.entrySet()) {
                TopicPartition tp = e.getKey();
                long committedOffset = e.getValue().offset();
                ListOffsetsResultInfo info = offsets.get(tp);
                long endOffset = info == null ? -1L : info.offset();
                long lag = (endOffset < 0) ? -1L : Math.max(0L, endOffset - committedOffset);

                Map<String, Object> part = new HashMap<>();
                part.put("topic", tp.topic());
                part.put("partition", tp.partition());
                part.put("committedOffset", committedOffset);
                part.put("endOffset", endOffset);
                part.put("lag", lag);

                report.put(tp.topic() + ":" + tp.partition(), part);
                if (lag >= 0) totalLag += lag;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("groupId", groupId);
            result.put("bootstrapServers", bootstrapServers);
            result.put("totalLag", totalLag);
            result.put("partitions", report);

            return ResponseEntity.ok(result);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error", ex.getMessage()));
        } catch (ExecutionException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error", ex.getMessage()));
        }
    }
}

