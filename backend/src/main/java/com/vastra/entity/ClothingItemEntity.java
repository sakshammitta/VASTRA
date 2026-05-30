package com.vastra.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "clothing_items")
public class ClothingItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    private String catalogId;

    @ManyToOne
    @JoinColumn(name = "owner_id")
    private UserEntity owner;

    @Enumerated(EnumType.STRING)
    private OwnershipStatus ownershipStatus = OwnershipStatus.OWNED;

    @Column(name = "r2_image_key")
    private String r2ImageKey;

    @Column(name = "r2_thumbnail_key")
    private String r2ThumbnailKey;

    @Enumerated(EnumType.STRING)
    private ClothingCategory category;

    private String subCategory = "";

    private String brand;

    private String purchasePlatform;

    private String purchaseUrl;

    private BigDecimal priceUsd;

    private int styleMatchPercent = 0;

    @Column(columnDefinition = "vector(512)")
    @ColumnTransformer(write = "CAST(? AS vector)")
    private String fashionClipEmbedding;

    @ElementCollection
    @CollectionTable(name = "item_tags", joinColumns = @JoinColumn(name = "item_id"))
    @Column(name = "tag")
    private List<String> tags = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "item_colors", joinColumns = @JoinColumn(name = "item_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "hex_color")
    private List<String> colorPalette = new ArrayList<>();

    @CreationTimestamp
    private Instant addedAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public ClothingItemEntity() {
    }

    public ClothingItemEntity(UUID id, String catalogId, UserEntity owner, OwnershipStatus ownershipStatus,
                              String r2ImageKey, String r2ThumbnailKey, ClothingCategory category,
                              String subCategory, String brand, String purchasePlatform, String purchaseUrl,
                              BigDecimal priceUsd, int styleMatchPercent, String fashionClipEmbedding,
                              List<String> tags, List<String> colorPalette, Instant addedAt, Instant updatedAt) {
        this.id = id;
        this.catalogId = catalogId;
        this.owner = owner;
        this.ownershipStatus = ownershipStatus;
        this.r2ImageKey = r2ImageKey;
        this.r2ThumbnailKey = r2ThumbnailKey;
        this.category = category;
        this.subCategory = subCategory;
        this.brand = brand;
        this.purchasePlatform = purchasePlatform;
        this.purchaseUrl = purchaseUrl;
        this.priceUsd = priceUsd;
        this.styleMatchPercent = styleMatchPercent;
        this.fashionClipEmbedding = fashionClipEmbedding;
        this.tags = tags;
        this.colorPalette = colorPalette;
        this.addedAt = addedAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getCatalogId() {
        return catalogId;
    }

    public void setCatalogId(String catalogId) {
        this.catalogId = catalogId;
    }

    public UserEntity getOwner() {
        return owner;
    }

    public void setOwner(UserEntity owner) {
        this.owner = owner;
    }

    public OwnershipStatus getOwnershipStatus() {
        return ownershipStatus;
    }

    public void setOwnershipStatus(OwnershipStatus ownershipStatus) {
        this.ownershipStatus = ownershipStatus;
    }

    public String getR2ImageKey() {
        return r2ImageKey;
    }

    public void setR2ImageKey(String r2ImageKey) {
        this.r2ImageKey = r2ImageKey;
    }

    public String getR2ThumbnailKey() {
        return r2ThumbnailKey;
    }

    public void setR2ThumbnailKey(String r2ThumbnailKey) {
        this.r2ThumbnailKey = r2ThumbnailKey;
    }

    public ClothingCategory getCategory() {
        return category;
    }

    public void setCategory(ClothingCategory category) {
        this.category = category;
    }

    public String getSubCategory() {
        return subCategory;
    }

    public void setSubCategory(String subCategory) {
        this.subCategory = subCategory;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public String getPurchasePlatform() {
        return purchasePlatform;
    }

    public void setPurchasePlatform(String purchasePlatform) {
        this.purchasePlatform = purchasePlatform;
    }

    public String getPurchaseUrl() {
        return purchaseUrl;
    }

    public void setPurchaseUrl(String purchaseUrl) {
        this.purchaseUrl = purchaseUrl;
    }

    public BigDecimal getPriceUsd() {
        return priceUsd;
    }

    public void setPriceUsd(BigDecimal priceUsd) {
        this.priceUsd = priceUsd;
    }

    public int getStyleMatchPercent() {
        return styleMatchPercent;
    }

    public void setStyleMatchPercent(int styleMatchPercent) {
        this.styleMatchPercent = styleMatchPercent;
    }

    public String getFashionClipEmbedding() {
        return fashionClipEmbedding;
    }

    public void setFashionClipEmbedding(String fashionClipEmbedding) {
        this.fashionClipEmbedding = fashionClipEmbedding;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public List<String> getColorPalette() {
        return colorPalette;
    }

    public void setColorPalette(List<String> colorPalette) {
        this.colorPalette = colorPalette;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public void setAddedAt(Instant addedAt) {
        this.addedAt = addedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
