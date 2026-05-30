package com.vastra.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vastra.entity.UserEntity;
import com.vastra.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack integration tests with real AuthService + H2.
 * Covers auth flow and verifies that vector embedding fields can be
 * read back and written (non-null) through Hibernate without type errors.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;

    @MockBean
    RedisConnectionFactory redisConnectionFactory;

    @Test
    void fullAuthFlow() throws Exception {
        // 1. Register — must succeed without a token (null embedding in INSERT)
        MvcResult registerResult = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "integuser",
                                "displayName", "Integration User",
                                "email", "integ@example.com",
                                "password", "password123"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();

        String token = objectMapper.readTree(
                registerResult.getResponse().getContentAsString()).get("token").asText();

        // 2. Login — must return a token
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "integ@example.com",
                                "password", "password123"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        // 3. Protected endpoint without token — 401
        mvc.perform(get("/api/wardrobe"))
                .andExpect(status().isUnauthorized());

        // 4. Protected endpoint with valid token — not 401/403
        mvc.perform(get("/api/wardrobe")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.anyOf(
                                org.hamcrest.Matchers.is(401),
                                org.hamcrest.Matchers.is(403)
                        ))));
    }

    /**
     * Verify that a non-null vector value can be saved and reloaded through
     * Hibernate without a type-binding error.  This catches any regression in
     * the @ColumnTransformer / H2 DOMAIN setup.
     */
    @Test
    void vectorEmbeddingRoundTrip() throws Exception {
        // Register first to get a user in the DB
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "vecuser",
                                "displayName", "Vector User",
                                "email", "vec@example.com",
                                "password", "password123"
                        ))))
                .andExpect(status().isOk());

        // Load the user, set a fake embedding string, save — must not throw
        UserEntity user = userRepository.findByEmail("vec@example.com").orElseThrow();
        assertThat(user.getStyleEmbedding()).isNull();

        // pgvector text format: [v1,v2,...,v512]
        String fakeEmbedding = buildFakeEmbedding(512);
        user.setStyleEmbedding(fakeEmbedding);
        userRepository.save(user);

        // Reload and confirm the value round-tripped
        UserEntity reloaded = userRepository.findByEmail("vec@example.com").orElseThrow();
        assertThat(reloaded.getStyleEmbedding()).isNotNull();
    }

    @Test
    void actuatorHealth_alwaysReturns200() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    private static String buildFakeEmbedding(int dim) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < dim; i++) {
            sb.append("0.").append(i % 10);
            if (i < dim - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
