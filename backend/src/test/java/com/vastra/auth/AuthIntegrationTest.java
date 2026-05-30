package com.vastra.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack integration test using the real AuthService + H2.
 * No service mocks — verifies the complete request path including
 * JWT generation and the security filter chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    // Redis is excluded by application-test.yml autoconfigure.exclude,
    // but RedisTemplate still needs a factory bean to construct — mock it.
    @MockBean
    RedisConnectionFactory redisConnectionFactory;

    @Test
    void fullAuthFlow() throws Exception {
        // 1. Register a new user — must succeed without a token
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

        // 2. Extract the JWT token from the response
        String body = registerResult.getResponse().getContentAsString();
        String token = objectMapper.readTree(body).get("token").asText();

        // 3. Login with the same credentials — must return a token
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "integ@example.com",
                                "password", "password123"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        // 4. Protected endpoint without token — must be 401
        mvc.perform(get("/api/wardrobe"))
                .andExpect(status().isUnauthorized());

        // 5. Protected endpoint with valid token — must get past authentication
        //    (may be 404/200 depending on data, but NOT 401/403)
        mvc.perform(get("/api/wardrobe")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.anyOf(
                                org.hamcrest.Matchers.is(401),
                                org.hamcrest.Matchers.is(403)
                        ))));
    }

    @Test
    void actuatorHealth_alwaysReturns200() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
