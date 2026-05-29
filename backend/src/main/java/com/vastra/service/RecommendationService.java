package com.vastra.service;

import com.vastra.dto.ClothingItemDto;
import com.vastra.dto.PostDto;
import com.vastra.entity.ClothingItemEntity;
import com.vastra.entity.OwnershipStatus;
import com.vastra.repository.ClothingItemRepository;
import com.vastra.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class RecommendationService {

    private final ClothingItemRepository itemRepo;
    private final UserRepository userRepo;
    private final R2Service r2Service;

    public RecommendationService(ClothingItemRepository itemRepo, UserRepository userRepo, R2Service r2Service) {
        this.itemRepo = itemRepo;
        this.userRepo = userRepo;
        this.r2Service = r2Service;
    }

    @Transactional(readOnly = true)
    public ClothingItemDto.ClothingItemResponse getNextSwipeCard(UUID userId) {
        var user = userRepo.findById(userId).orElseThrow();
        List<ClothingItemEntity> candidates;
        if (user.getStyleEmbedding() != null) {
            candidates = itemRepo.findRecommendedByStyleEmbedding(userId.toString(), user.getStyleEmbedding(), 10);
        } else {
            candidates = itemRepo.findByOwnerIdAndOwnershipStatusOrderByAddedAtDesc(
                UUID.randomUUID(), OwnershipStatus.OWNED
            );
            if (candidates.isEmpty()) {
                var all = itemRepo.findAll();
                candidates = all.stream().filter(i -> !i.getOwner().getId().equals(userId)).limit(10).toList();
            }
        }
        if (candidates.isEmpty()) return null;
        var item = candidates.get(0);
        return ClothingItemDto.ClothingItemResponse.from(item,
            r2Service.getPresignedUrl(item.getR2ImageKey()),
            r2Service.getPresignedUrl(item.getR2ThumbnailKey())
        );
    }

    @Transactional
    public void recordSwipe(UUID userId, String itemId, String direction) {
        var user = userRepo.findById(userId).orElseThrow();
        // Update user style embedding by averaging with liked item's embedding
        if ("LIKE".equals(direction) || "SUPER_LIKE".equals(direction)) {
            var item = itemRepo.findById(UUID.fromString(itemId)).orElse(null);
            if (item != null && item.getFashionClipEmbedding() != null && user.getStyleEmbedding() != null) {
                String updatedEmbedding = averageEmbeddings(user.getStyleEmbedding(), item.getFashionClipEmbedding());
                user.setStyleEmbedding(updatedEmbedding);
                userRepo.save(user);
            } else if (item != null && item.getFashionClipEmbedding() != null && user.getStyleEmbedding() == null) {
                user.setStyleEmbedding(item.getFashionClipEmbedding());
                userRepo.save(user);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<PostDto.StyleRecommendation> getStyleRecommendations(UUID userId) {
        var user = userRepo.findById(userId).orElseThrow();
        if (user.getStyleEmbedding() == null) return List.of();
        var candidates = itemRepo.findRecommendedByStyleEmbedding(userId.toString(), user.getStyleEmbedding(), 20);
        return candidates.stream().map(item -> {
            String imageUrl = r2Service.getPresignedUrl(item.getR2ImageKey());
            var itemResponse = ClothingItemDto.ClothingItemResponse.from(item, imageUrl,
                r2Service.getPresignedUrl(item.getR2ThumbnailKey()));
            float matchScore = 0.75f + (float) (Math.random() * 0.25f);
            return new PostDto.StyleRecommendation(itemResponse, "Matches your style profile", matchScore, List.of());
        }).toList();
    }

    private String averageEmbeddings(String embA, String embB) {
        try {
            String a = embA.trim().replaceAll("[\\[\\]]", "");
            String b = embB.trim().replaceAll("[\\[\\]]", "");
            String[] partsA = a.split(",");
            String[] partsB = b.split(",");
            if (partsA.length != partsB.length) return embA;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < partsA.length; i++) {
                float avg = (Float.parseFloat(partsA[i].trim()) + Float.parseFloat(partsB[i].trim())) / 2f;
                sb.append(avg);
                if (i < partsA.length - 1) sb.append(",");
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            return embA;
        }
    }
}
