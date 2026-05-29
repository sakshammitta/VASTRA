package com.vastra.service;

import com.vastra.dto.ClothingItemDto;
import com.vastra.dto.PostDto;
import com.vastra.dto.UserDto;
import com.vastra.entity.*;
import com.vastra.repository.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@Service
public class FeedService {

    private final PostRepository postRepo;
    private final UserRepository userRepo;
    private final CommentRepository commentRepo;
    private final ClothingItemRepository itemRepo;
    private final R2Service r2Service;

    public FeedService(PostRepository postRepo, UserRepository userRepo, CommentRepository commentRepo,
                       ClothingItemRepository itemRepo, R2Service r2Service) {
        this.postRepo = postRepo;
        this.userRepo = userRepo;
        this.commentRepo = commentRepo;
        this.itemRepo = itemRepo;
        this.r2Service = r2Service;
    }

    @Transactional(readOnly = true)
    public PostDto.FeedPage getFeed(UUID userId, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        var postPage = postRepo.findFeedForUser(userId, pageable);
        var responses = postPage.getContent().stream().map(p -> toPostResponse(p, userId)).toList();
        return new PostDto.FeedPage(responses, postPage.getTotalPages(), page, postPage.hasNext());
    }

    @Transactional
    public PostDto.PostResponse createPost(UUID userId, MultipartFile image, String caption, String visibility) throws IOException {
        var user = userRepo.findById(userId).orElseThrow();
        String imageKey = r2Service.upload(image, "posts/" + userId);
        var post = new PostEntity();
        post.setAuthor(user);
        post.setR2ImageKey(imageKey);
        post.setCaption(caption);
        post.setVisibility(PostVisibility.valueOf(visibility.toUpperCase()));
        post = postRepo.save(post);
        user.setPostCount(user.getPostCount() + 1);
        userRepo.save(user);
        return toPostResponse(post, userId);
    }

    @Transactional
    public void likePost(UUID userId, UUID postId) {
        var post = postRepo.findById(postId).orElseThrow();
        post.setLikeCount(post.getLikeCount() + 1);
        postRepo.save(post);
    }

    @Transactional
    public void savePost(UUID userId, UUID postId) {
        var post = postRepo.findById(postId).orElseThrow();
        post.setSaveCount(post.getSaveCount() + 1);
        postRepo.save(post);
    }

    @Transactional(readOnly = true)
    public List<PostDto.CommentResponse> getComments(UUID postId) {
        return commentRepo.findByPostIdOrderByCreatedAtAsc(postId).stream().map(c -> new PostDto.CommentResponse(
            c.getId().toString(),
            UserDto.UserResponse.from(c.getAuthor(), r2Service.getPresignedUrl(c.getAuthor().getAvatarR2Key())),
            c.getText(),
            c.getCreatedAt().toString()
        )).toList();
    }

    @Transactional
    public PostDto.CommentResponse addComment(UUID userId, UUID postId, String text) {
        var post = postRepo.findById(postId).orElseThrow();
        var user = userRepo.findById(userId).orElseThrow();
        var comment = new CommentEntity();
        comment.setPost(post);
        comment.setAuthor(user);
        comment.setText(text);
        comment = commentRepo.save(comment);
        post.setCommentCount(post.getCommentCount() + 1);
        postRepo.save(post);
        return new PostDto.CommentResponse(
            comment.getId().toString(),
            UserDto.UserResponse.from(user, r2Service.getPresignedUrl(user.getAvatarR2Key())),
            comment.getText(),
            comment.getCreatedAt().toString()
        );
    }

    @Transactional(readOnly = true)
    public PostDto.RecreateStyleResponse recreateStyle(UUID userId, UUID postId) {
        var post = postRepo.findById(postId).orElseThrow();
        if (post.getImageEmbedding() == null) {
            return new PostDto.RecreateStyleResponse(List.of(), List.of(), 0f);
        }
        var owned = itemRepo.findWardrobeMatchesForPost(userId.toString(), post.getImageEmbedding(), 5);
        var gaps = itemRepo.findGapItemsForPost(userId.toString(), post.getImageEmbedding(), 5);
        float matchScore = owned.isEmpty() ? 0f : Math.min(1f, owned.size() / 5f);
        return new PostDto.RecreateStyleResponse(
            owned.stream().map(i -> ClothingItemDto.ClothingItemResponse.from(i,
                r2Service.getPresignedUrl(i.getR2ImageKey()), r2Service.getPresignedUrl(i.getR2ThumbnailKey()))).toList(),
            gaps.stream().map(i -> ClothingItemDto.ClothingItemResponse.from(i,
                r2Service.getPresignedUrl(i.getR2ImageKey()), r2Service.getPresignedUrl(i.getR2ThumbnailKey()))).toList(),
            matchScore
        );
    }

    private PostDto.PostResponse toPostResponse(PostEntity p, UUID viewerId) {
        String imageUrl = r2Service.getPresignedUrl(p.getR2ImageKey());
        var authorAvatarUrl = r2Service.getPresignedUrl(p.getAuthor().getAvatarR2Key());
        return new PostDto.PostResponse(
            p.getId().toString(),
            UserDto.UserResponse.from(p.getAuthor(), authorAvatarUrl),
            imageUrl, p.getCaption(),
            p.getShoppableTags().stream().map(PostDto.ShoppableTagResponse::from).toList(),
            p.getStyleLabels(), p.getDominantColors(),
            p.getLikeCount(), p.getCommentCount(), p.getSaveCount(),
            false, false,
            p.getVisibility().name(),
            p.getCreatedAt().toString()
        );
    }
}
