package com.vastra.dto;

import com.vastra.entity.UserEntity;
import java.util.List;

public class UserDto {
    public record UserResponse(
        String id, String username, String displayName, String avatarUrl,
        String bio, int followerCount, int followingCount, int postCount, int itemCount
    ) {
        public static UserResponse from(UserEntity u, String avatarUrl) {
            return new UserResponse(u.getId().toString(), u.getUsername(), u.getDisplayName(),
                avatarUrl, u.getBio(), u.getFollowerCount(), u.getFollowingCount(), u.getPostCount(), u.getItemCount());
        }
    }
    public record UpdateProfileRequest(String displayName, String bio, int priceMin, int priceMax) {}
}
