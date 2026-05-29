package com.vastra.dto;

public class AuthDto {
    public record RegisterRequest(String username, String displayName, String email, String password) {}
    public record LoginRequest(String email, String password) {}
    public record AuthResponse(String token, UserDto.UserResponse user) {}
}
