package com.vastra.service;

import com.vastra.dto.AuthDto;
import com.vastra.dto.UserDto;
import com.vastra.entity.UserEntity;
import com.vastra.repository.UserRepository;
import com.vastra.security.JwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final R2Service r2Service;

    public AuthService(UserRepository userRepo, PasswordEncoder passwordEncoder, JwtUtil jwtUtil, R2Service r2Service) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.r2Service = r2Service;
    }

    @Transactional
    public AuthDto.AuthResponse register(AuthDto.RegisterRequest req) {
        if (userRepo.existsByEmail(req.email())) throw new IllegalArgumentException("Email already in use");
        if (userRepo.existsByUsername(req.username())) throw new IllegalArgumentException("Username already taken");
        var user = new UserEntity();
        user.setUsername(req.username());
        user.setDisplayName(req.displayName());
        user.setEmail(req.email());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user = userRepo.save(user);
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        return new AuthDto.AuthResponse(token, UserDto.UserResponse.from(user, null));
    }

    public AuthDto.AuthResponse login(AuthDto.LoginRequest req) {
        var user = userRepo.findByEmail(req.email())
            .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid credentials");
        }
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        String avatarUrl = r2Service.getPresignedUrl(user.getAvatarR2Key());
        return new AuthDto.AuthResponse(token, UserDto.UserResponse.from(user, avatarUrl));
    }
}
