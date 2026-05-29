package com.vastra.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vastra.dto.AuthDto;
import com.vastra.dto.UserDto;
import com.vastra.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    AuthService authService;

    // Mocking the factory lets StringRedisTemplate + RedisConfig.redisTemplate()
    // construct normally without opening a real connection.
    @MockBean
    RedisConnectionFactory redisConnectionFactory;

    // ── public endpoints ────────────────────────────────────────────────

    @Test
    void register_withoutToken_returns2xx() throws Exception {
        var fakeUser = new UserDto.UserResponse(
                "00000000-0000-0000-0000-000000000001",
                "testuser", "Test User", null, null, 0, 0, 0, 0);
        when(authService.register(any()))
                .thenReturn(new AuthDto.AuthResponse("fake.jwt.token", fakeUser));

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "testuser",
                                "displayName", "Test User",
                                "email", "test@example.com",
                                "password", "password123"
                        ))))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void login_withoutToken_returns2xx() throws Exception {
        var fakeUser = new UserDto.UserResponse(
                "00000000-0000-0000-0000-000000000001",
                "testuser", "Test User", null, null, 0, 0, 0, 0);
        when(authService.login(any()))
                .thenReturn(new AuthDto.AuthResponse("fake.jwt.token", fakeUser));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "test@example.com",
                                "password", "password123"
                        ))))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void actuatorHealth_withoutToken_returns200() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    // ── protected endpoints ─────────────────────────────────────────────

    @Test
    void feed_withoutToken_returns401() throws Exception {
        mvc.perform(get("/api/feed"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wardrobe_withoutToken_returns401() throws Exception {
        mvc.perform(get("/api/wardrobe"))
                .andExpect(status().isUnauthorized());
    }
}
