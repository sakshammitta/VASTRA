package com.vastra.controller;

import com.vastra.dto.UserDto;
import com.vastra.repository.UserRepository;
import com.vastra.service.R2Service;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final UserRepository userRepo;
    private final R2Service r2Service;

    public ProfileController(UserRepository userRepo, R2Service r2Service) {
        this.userRepo = userRepo;
        this.r2Service = r2Service;
    }

    @GetMapping("/{username}")
    public ResponseEntity<UserDto.UserResponse> getProfile(@PathVariable String username) {
        var user = userRepo.findByUsername(username).orElseThrow();
        return ResponseEntity.ok(UserDto.UserResponse.from(user, r2Service.getPresignedUrl(user.getAvatarR2Key())));
    }

    @PutMapping
    public ResponseEntity<UserDto.UserResponse> updateProfile(
            @RequestBody UserDto.UpdateProfileRequest req,
            Authentication auth) {
        var user = userRepo.findById((UUID) auth.getPrincipal()).orElseThrow();
        user.setDisplayName(req.displayName());
        user.setBio(req.bio());
        user.setPriceMin(req.priceMin());
        user.setPriceMax(req.priceMax());
        user = userRepo.save(user);
        return ResponseEntity.ok(UserDto.UserResponse.from(user, r2Service.getPresignedUrl(user.getAvatarR2Key())));
    }

    @PostMapping("/follow/{userId}")
    public ResponseEntity<Void> followUser(@PathVariable UUID userId, Authentication auth) {
        var me = userRepo.findById((UUID) auth.getPrincipal()).orElseThrow();
        var target = userRepo.findById(userId).orElseThrow();
        me.getFriends().add(target);
        me.setFollowingCount(me.getFollowingCount() + 1);
        target.setFollowerCount(target.getFollowerCount() + 1);
        userRepo.save(me);
        userRepo.save(target);
        return ResponseEntity.ok().build();
    }
}
