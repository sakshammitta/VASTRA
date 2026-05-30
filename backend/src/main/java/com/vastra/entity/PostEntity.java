package com.vastra.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "posts")
public class PostEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "author_id")
    private UserEntity author;

    @Column(name = "r2_image_key")
    private String r2ImageKey;

    private String caption;

    @Enumerated(EnumType.STRING)
    private PostVisibility visibility = PostVisibility.PUBLIC;

    private int likeCount = 0;

    private int commentCount = 0;

    private int saveCount = 0;

    @Column(columnDefinition = "vector(512)")
    private String imageEmbedding;

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ShoppableTagEntity> shoppableTags = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "post_style_labels", joinColumns = @JoinColumn(name = "post_id"))
    @Column(name = "label")
    private List<String> styleLabels = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "post_dominant_colors", joinColumns = @JoinColumn(name = "post_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "hex_color")
    private List<String> dominantColors = new ArrayList<>();

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public PostEntity() {
    }

    public PostEntity(UUID id, UserEntity author, String r2ImageKey, String caption,
                      PostVisibility visibility, int likeCount, int commentCount, int saveCount,
                      String imageEmbedding, List<ShoppableTagEntity> shoppableTags,
                      List<String> styleLabels, List<String> dominantColors,
                      Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.author = author;
        this.r2ImageKey = r2ImageKey;
        this.caption = caption;
        this.visibility = visibility;
        this.likeCount = likeCount;
        this.commentCount = commentCount;
        this.saveCount = saveCount;
        this.imageEmbedding = imageEmbedding;
        this.shoppableTags = shoppableTags;
        this.styleLabels = styleLabels;
        this.dominantColors = dominantColors;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UserEntity getAuthor() {
        return author;
    }

    public void setAuthor(UserEntity author) {
        this.author = author;
    }

    public String getR2ImageKey() {
        return r2ImageKey;
    }

    public void setR2ImageKey(String r2ImageKey) {
        this.r2ImageKey = r2ImageKey;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public PostVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(PostVisibility visibility) {
        this.visibility = visibility;
    }

    public int getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(int likeCount) {
        this.likeCount = likeCount;
    }

    public int getCommentCount() {
        return commentCount;
    }

    public void setCommentCount(int commentCount) {
        this.commentCount = commentCount;
    }

    public int getSaveCount() {
        return saveCount;
    }

    public void setSaveCount(int saveCount) {
        this.saveCount = saveCount;
    }

    public String getImageEmbedding() {
        return imageEmbedding;
    }

    public void setImageEmbedding(String imageEmbedding) {
        this.imageEmbedding = imageEmbedding;
    }

    public List<ShoppableTagEntity> getShoppableTags() {
        return shoppableTags;
    }

    public void setShoppableTags(List<ShoppableTagEntity> shoppableTags) {
        this.shoppableTags = shoppableTags;
    }

    public List<String> getStyleLabels() {
        return styleLabels;
    }

    public void setStyleLabels(List<String> styleLabels) {
        this.styleLabels = styleLabels;
    }

    public List<String> getDominantColors() {
        return dominantColors;
    }

    public void setDominantColors(List<String> dominantColors) {
        this.dominantColors = dominantColors;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
