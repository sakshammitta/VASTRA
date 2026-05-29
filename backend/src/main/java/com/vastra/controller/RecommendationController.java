package com.vastra.controller;

import com.vastra.dto.ClothingItemDto;
import com.vastra.dto.PostDto;
import com.vastra.service.RecommendationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private final RecommendationService recommendationService;

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @GetMapping("/swipe")
    public ResponseEntity<ClothingItemDto.ClothingItemResponse> getNextSwipeCard(Authentication auth) {
        var item = recommendationService.getNextSwipeCard((UUID) auth.getPrincipal());
        if (item == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(item);
    }

    @PostMapping("/swipe")
    public ResponseEntity<Void> recordSwipe(@RequestBody PostDto.SwipeRequest req, Authentication auth) {
        recommendationService.recordSwipe((UUID) auth.getPrincipal(), req.itemId(), req.direction());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/style")
    public ResponseEntity<List<PostDto.StyleRecommendation>> getStyleRecommendations(Authentication auth) {
        return ResponseEntity.ok(recommendationService.getStyleRecommendations((UUID) auth.getPrincipal()));
    }
}
