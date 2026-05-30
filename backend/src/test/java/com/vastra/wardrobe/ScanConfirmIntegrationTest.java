package com.vastra.wardrobe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vastra.entity.UserEntity;
import com.vastra.repository.UserRepository;
import com.vastra.security.JwtUtil;
import com.vastra.service.ScanJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScanConfirmIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepo;
    @Autowired JwtUtil jwtUtil;
    @Autowired PasswordEncoder passwordEncoder;

    @MockBean RedisConnectionFactory redisConnectionFactory;
    @MockBean ScanJobService scanJobService;

    private String token;
    private UUID userId;

    @BeforeEach
    void setup() {
        userRepo.findByEmail("confirm@example.com").ifPresentOrElse(
            u -> { userId = u.getId(); },
            () -> {
                var user = new UserEntity();
                user.setUsername("confirmuser");
                user.setDisplayName("Confirm User");
                user.setEmail("confirm@example.com");
                user.setPasswordHash(passwordEncoder.encode("password123"));
                var saved = userRepo.save(user);
                userId = saved.getId();
            }
        );
        token = jwtUtil.generateToken(userId, "confirmuser");
    }

    @Test
    void confirmScanItem_savesItemAndReturnsImageUrl() throws Exception {
        String jobId = "test-job-001";
        Map<String, Object> fakeJob = Map.of(
            "jobId", jobId,
            "status", "COMPLETE",
            "detectedItems", List.of(
                Map.of(
                    "crop_key", "item-crops/test-crop.jpg",
                    "category", "shirt",
                    "color_palette", List.of("#FFFFFF", "#000000"),
                    "embedding", List.of(0.1, 0.2, 0.3)
                )
            )
        );
        when(scanJobService.getJobStatus(jobId)).thenReturn(fakeJob);

        mvc.perform(post("/api/wardrobe/scan/{jobId}/confirm", jobId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("itemIndex", 0))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.category").value("TOP"))
            .andExpect(jsonPath("$.imageUrl").isNotEmpty());
    }

    @Test
    void confirmScanItem_notFoundJob_returns404() throws Exception {
        when(scanJobService.getJobStatus("no-such-job")).thenReturn(null);

        mvc.perform(post("/api/wardrobe/scan/no-such-job/confirm")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("itemIndex", 0))))
            .andExpect(status().isNotFound());
    }

    @Test
    void confirmScanItem_jobNotComplete_returns400() throws Exception {
        when(scanJobService.getJobStatus("pending-job"))
            .thenReturn(Map.of("jobId", "pending-job", "status", "PROCESSING", "detectedItems", List.of()));

        mvc.perform(post("/api/wardrobe/scan/pending-job/confirm")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("itemIndex", 0))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void confirmScanItem_badIndex_returns400() throws Exception {
        Map<String, Object> fakeJob = Map.of(
            "jobId", "job-one-item",
            "status", "COMPLETE",
            "detectedItems", List.of(
                Map.of("crop_key", "k", "category", "TOP", "color_palette", List.of(), "embedding", List.of(0.1))
            )
        );
        when(scanJobService.getJobStatus("job-one-item")).thenReturn(fakeJob);

        mvc.perform(post("/api/wardrobe/scan/job-one-item/confirm")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("itemIndex", 5))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void confirmedItem_appearsInGetWardrobe() throws Exception {
        String jobId = "test-job-wardrobe";
        Map<String, Object> fakeJob = Map.of(
            "jobId", jobId,
            "status", "COMPLETE",
            "detectedItems", List.of(
                Map.of(
                    "crop_key", "item-crops/wardrobe-test.jpg",
                    "category", "BOTTOM",
                    "color_palette", List.of("#1A1A1A"),
                    "embedding", List.of(0.5, 0.6)
                )
            )
        );
        when(scanJobService.getJobStatus(jobId)).thenReturn(fakeJob);

        mvc.perform(post("/api/wardrobe/scan/{jobId}/confirm", jobId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("itemIndex", 0))))
            .andExpect(status().isOk());

        mvc.perform(get("/api/wardrobe")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.category == 'BOTTOM')]").exists());
    }
}
