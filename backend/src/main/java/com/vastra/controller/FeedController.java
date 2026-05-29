package com.vastra.controller;

import com.vastra.dto.PostDto;
import com.vastra.service.FeedService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/feed")
public class FeedController {

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping
    public ResponseEntity<PostDto.FeedPage> getFeed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return ResponseEntity.ok(feedService.getFeed(userId, page, size));
    }

    @PostMapping("/posts")
    public ResponseEntity<PostDto.PostResponse> createPost(
            @RequestPart("image") MultipartFile image,
            @RequestPart("caption") String caption,
            @RequestPart(value = "visibility", required = false) String visibility,
            Authentication auth) throws IOException {
        UUID userId = (UUID) auth.getPrincipal();
        return ResponseEntity.ok(feedService.createPost(userId, image, caption,
            visibility != null ? visibility : "PUBLIC"));
    }

    @PostMapping("/posts/{id}/like")
    public ResponseEntity<Void> likePost(@PathVariable UUID id, Authentication auth) {
        feedService.likePost((UUID) auth.getPrincipal(), id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/posts/{id}/save")
    public ResponseEntity<Void> savePost(@PathVariable UUID id, Authentication auth) {
        feedService.savePost((UUID) auth.getPrincipal(), id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/posts/{id}/comments")
    public ResponseEntity<List<PostDto.CommentResponse>> getComments(@PathVariable UUID id) {
        return ResponseEntity.ok(feedService.getComments(id));
    }

    @PostMapping("/posts/{id}/comments")
    public ResponseEntity<PostDto.CommentResponse> addComment(
            @PathVariable UUID id,
            @RequestBody PostDto.AddCommentRequest req,
            Authentication auth) {
        return ResponseEntity.ok(feedService.addComment((UUID) auth.getPrincipal(), id, req.text()));
    }

    @PostMapping("/posts/{id}/recreate")
    public ResponseEntity<PostDto.RecreateStyleResponse> recreateStyle(
            @PathVariable UUID id, Authentication auth) {
        return ResponseEntity.ok(feedService.recreateStyle((UUID) auth.getPrincipal(), id));
    }
}
