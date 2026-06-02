package com.vastra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class ScanJobService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ScanJobService.class);

    private static final String JOB_PREFIX = "scan:job:";
    private static final Duration TTL = Duration.ofHours(24);

    private final RedisTemplate<String, Object> redis;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${vastra.cv-service.base-url}")
    private String cvServiceUrl;

    public ScanJobService(RedisTemplate<String, Object> redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> getJobStatus(String jobId) {
        Object raw = redis.opsForValue().get(JOB_PREFIX + jobId);
        if (raw == null) return null;
        return objectMapper.convertValue(raw, Map.class);
    }

    @Async
    public void processScanAsync(String jobId, UUID userId, String r2ImageKey) {
        String redisKey = JOB_PREFIX + jobId;
        Map<String, Object> job = new HashMap<>();
        job.put("jobId", jobId);
        job.put("userId", userId.toString());
        job.put("r2InputKey", r2ImageKey);
        job.put("status", "QUEUED");
        job.put("detectedItems", new ArrayList<>());
        job.put("createdAt", Instant.now().toString());
        redis.opsForValue().set(redisKey, job, TTL);

        try {
            job.put("status", "PROCESSING");
            redis.opsForValue().set(redisKey, job, TTL);

            log.info("scan job {} → POST {}/scan image_key={}", jobId, cvServiceUrl, r2ImageKey);
            var scanRequest = Map.of("image_key", r2ImageKey);
            var scanResponse = restTemplate.postForObject(
                cvServiceUrl + "/scan", scanRequest, Map.class
            );

            List<Map<String, Object>> detections = scanResponse != null
                ? (List<Map<String, Object>>) scanResponse.getOrDefault("detections", List.of())
                : List.of();
            log.info("scan job {} ← /scan returned {} detection(s)", jobId, detections.size());

            log.info("scan job {} → POST {}/embed ({} detections)", jobId, cvServiceUrl, detections.size());
            var embedRequest = Map.of("image_key", r2ImageKey, "detections", detections);
            var embedResponse = restTemplate.postForObject(
                cvServiceUrl + "/embed", embedRequest, Map.class
            );

            List<Map<String, Object>> detectedItems = embedResponse != null
                ? (List<Map<String, Object>>) embedResponse.getOrDefault("items", List.of())
                : List.of();
            log.info("scan job {} ← /embed returned {} item(s)", jobId, detectedItems.size());

            job.put("status", "COMPLETE");
            job.put("detectedItems", detectedItems);
            job.put("completedAt", Instant.now().toString());
            log.info("scan job {} COMPLETE", jobId);
        } catch (Exception e) {
            log.error("scan job {} FAILED calling CV service at {}: {}", jobId, cvServiceUrl, e.toString());
            job.put("status", "FAILED");
            job.put("errorMessage", "CV service error: " + e.getMessage());
            job.put("completedAt", Instant.now().toString());
        }

        redis.opsForValue().set(redisKey, job, TTL);
    }
}
