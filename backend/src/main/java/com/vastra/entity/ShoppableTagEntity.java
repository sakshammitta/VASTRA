package com.vastra.entity;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "shoppable_tags")
public class ShoppableTagEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "post_id")
    private PostEntity post;

    private UUID itemId;

    private float xNorm;

    private float yNorm;

    private String label;

    public ShoppableTagEntity() {
    }

    public ShoppableTagEntity(UUID id, PostEntity post, UUID itemId, float xNorm, float yNorm, String label) {
        this.id = id;
        this.post = post;
        this.itemId = itemId;
        this.xNorm = xNorm;
        this.yNorm = yNorm;
        this.label = label;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public PostEntity getPost() {
        return post;
    }

    public void setPost(PostEntity post) {
        this.post = post;
    }

    public UUID getItemId() {
        return itemId;
    }

    public void setItemId(UUID itemId) {
        this.itemId = itemId;
    }

    public float getXNorm() {
        return xNorm;
    }

    public void setXNorm(float xNorm) {
        this.xNorm = xNorm;
    }

    public float getYNorm() {
        return yNorm;
    }

    public void setYNorm(float yNorm) {
        this.yNorm = yNorm;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
