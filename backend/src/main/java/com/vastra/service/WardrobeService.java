package com.vastra.service;

import com.vastra.dto.ClothingItemDto;
import com.vastra.entity.*;
import com.vastra.repository.ClothingItemRepository;
import com.vastra.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
public class WardrobeService {

    private final ClothingItemRepository itemRepo;
    private final UserRepository userRepo;
    private final R2Service r2Service;
    private final ScanJobService scanJobService;

    public WardrobeService(ClothingItemRepository itemRepo, UserRepository userRepo,
                           R2Service r2Service, ScanJobService scanJobService) {
        this.itemRepo = itemRepo;
        this.userRepo = userRepo;
        this.r2Service = r2Service;
        this.scanJobService = scanJobService;
    }

    @Transactional(readOnly = true)
    public List<ClothingItemDto.ClothingItemResponse> getWardrobe(UUID userId, String category, String status) {
        OwnershipStatus ownershipStatus = status != null ? OwnershipStatus.valueOf(status) : null;
        ClothingCategory clothingCategory = category != null ? ClothingCategory.valueOf(category) : null;

        List<ClothingItemEntity> items;
        if (clothingCategory != null && ownershipStatus != null) {
            items = itemRepo.findByOwnerIdAndCategoryAndOwnershipStatus(userId, clothingCategory, ownershipStatus);
        } else if (ownershipStatus != null) {
            items = itemRepo.findByOwnerIdAndOwnershipStatusOrderByAddedAtDesc(userId, ownershipStatus);
        } else {
            items = itemRepo.findByOwnerIdOrderByAddedAtDesc(userId);
        }
        return items.stream().map(i -> ClothingItemDto.ClothingItemResponse.from(i,
            r2Service.getPresignedUrl(i.getR2ImageKey()),
            r2Service.getPresignedUrl(i.getR2ThumbnailKey())
        )).toList();
    }

    @Transactional
    public String initiateItemScan(UUID userId, MultipartFile image) throws IOException {
        String imageKey = r2Service.upload(image, "scans/" + userId);
        String jobId = UUID.randomUUID().toString();
        scanJobService.processScanAsync(jobId, userId, imageKey);
        return jobId;
    }

    @Transactional
    public ClothingItemDto.ClothingItemResponse createItem(UUID userId, ClothingItemDto.CreateItemRequest req) {
        var user = userRepo.findById(userId).orElseThrow();
        var item = new ClothingItemEntity();
        item.setOwner(user);
        item.setCatalogId(req.catalogId());
        item.setOwnershipStatus(OwnershipStatus.valueOf(req.ownershipStatus()));
        item.setCategory(ClothingCategory.valueOf(req.category()));
        item.setSubCategory(req.subCategory() != null ? req.subCategory() : "");
        item.setTags(req.tags() != null ? req.tags() : List.of());
        item.setBrand(req.brand());
        item.setPriceUsd(req.priceUsd());
        item = itemRepo.save(item);
        user.setItemCount(user.getItemCount() + 1);
        userRepo.save(user);
        return ClothingItemDto.ClothingItemResponse.from(item, null, null);
    }

    @Transactional
    public void deleteItem(UUID userId, UUID itemId) {
        var item = itemRepo.findById(itemId).orElseThrow();
        if (!item.getOwner().getId().equals(userId)) throw new SecurityException("Not your item");
        itemRepo.delete(item);
        var user = userRepo.findById(userId).orElseThrow();
        user.setItemCount(Math.max(0, user.getItemCount() - 1));
        userRepo.save(user);
    }
}
