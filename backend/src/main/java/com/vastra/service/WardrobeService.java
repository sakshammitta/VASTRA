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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class WardrobeService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(WardrobeService.class);

    // Identifies the CV models that produced AI provenance. Bump when the
    // detector/classifier changes so analytics can segment by model version.
    private static final String AI_MODEL_SOURCE = "grounding-dino-base+fashion-clip";

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
            r2Service.getPresignedUrl(i.getEffectiveImageKey()),
            r2Service.getPresignedUrl(i.getR2ThumbnailKey())
        )).toList();
    }

    @Transactional
    public String initiateItemScan(UUID userId, MultipartFile image) throws IOException {
        long t0 = System.currentTimeMillis();
        String imageKey = r2Service.upload(image, "scans/" + userId);
        log.info("timing r2-upload: {}ms  key={}", System.currentTimeMillis() - t0, imageKey);
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
    @SuppressWarnings("unchecked")
    public ClothingItemDto.ClothingItemResponse confirmScanItem(
            UUID userId, String jobId, ClothingItemDto.ConfirmScanItemRequest req) {

        Map<String, Object> job = scanJobService.getJobStatus(jobId);
        if (job == null) throw new java.util.NoSuchElementException("Scan job not found: " + jobId);

        String status = (String) job.get("status");
        if (!"COMPLETE".equals(status)) {
            throw new IllegalArgumentException("Scan job is not complete yet (status=" + status + ")");
        }

        List<Map<String, Object>> detectedItems =
                (List<Map<String, Object>>) job.getOrDefault("detectedItems", List.of());
        if (req.itemIndex() < 0 || req.itemIndex() >= detectedItems.size()) {
            throw new IllegalArgumentException(
                    "itemIndex " + req.itemIndex() + " out of range (detected " + detectedItems.size() + " items)");
        }

        Map<String, Object> detected = detectedItems.get(req.itemIndex());
        String cropKey = (String) detected.get("crop_key");

        // CV service stores embedding as list[float] in Redis, or null when FashionCLIP
        // is not loaded. Convert to pgvector text format "[v1,v2,...]", or leave null
        // so the column stores NULL rather than a misleading zero vector.
        Object rawEmbedding = detected.get("embedding");
        String embedding = toVectorString(rawEmbedding);  // returns null when rawEmbedding is null

        List<String> colors = (List<String>) detected.getOrDefault("color_palette", List.of());

        // CV service category is already the uppercase enum name (e.g. "TOP", "BOTTOM").
        String cvCategory = (String) detected.getOrDefault("category", "OTHER");

        var user = userRepo.findById(userId).orElseThrow();
        var item = new ClothingItemEntity();
        item.setOwner(user);
        item.setR2ImageKey(cropKey);
        item.setFashionClipEmbedding(embedding);
        item.setColorPalette(colors);
        item.setOwnershipStatus(req.ownershipStatus() != null
                ? OwnershipStatus.valueOf(req.ownershipStatus()) : OwnershipStatus.OWNED);
        item.setCategory(req.category() != null
                ? ClothingCategory.valueOf(req.category()) : mapCvCategory(cvCategory));
        String cvSubCategory = (String) detected.getOrDefault("sub_category", "");
        item.setSubCategory(req.subCategory() != null ? req.subCategory() : cvSubCategory);
        item.setTags(req.tags() != null ? req.tags() : List.of());
        item.setBrand(req.brand());
        item.setPriceUsd(req.priceUsd());

        // AI provenance — record what the CV pipeline predicted, regardless of
        // whether the user accepted or corrected it. Debugging/analytics only;
        // the canonical type stays category/subCategory set above.
        item.setAiPredictedCategory(cvCategory);
        item.setAiPredictedSubCategory(cvSubCategory.isBlank() ? null : cvSubCategory);
        Object cvConf = detected.get("subtype_confidence");
        if (cvConf instanceof Number n) {
            item.setAiSubtypeConfidence(n.floatValue());
        }
        item.setAiModelSource(AI_MODEL_SOURCE);

        item = itemRepo.save(item);
        itemRepo.flush();
        item = itemRepo.findById(item.getId()).orElseThrow();
        user.setItemCount(user.getItemCount() + 1);
        userRepo.save(user);

        String imageUrl = r2Service.getPresignedUrl(cropKey);
        return ClothingItemDto.ClothingItemResponse.from(item, imageUrl, null);
    }

    /** Convert a CV embedding (List<Double> from Redis JSON) to pgvector text format "[v1,v2,...]". */
    @SuppressWarnings("unchecked")
    private static String toVectorString(Object raw) {
        if (raw == null) return null;
        if (raw instanceof String s) return s;
        if (raw instanceof List<?> list) {
            return list.stream().map(Object::toString).collect(Collectors.joining(",", "[", "]"));
        }
        return raw.toString();
    }

    /**
     * Map a CV category string to the ClothingCategory enum.
     * The CV service stores the uppercase enum name directly ("TOP", "BOTTOM", etc.),
     * but also handles lowercase sub_category labels as a fallback.
     */
    private static ClothingCategory mapCvCategory(String cv) {
        if (cv == null) return ClothingCategory.OTHER;
        // Try direct enum match first (CV service stores "TOP", "BOTTOM", etc.)
        try {
            return ClothingCategory.valueOf(cv.toUpperCase().trim());
        } catch (IllegalArgumentException ignored) {}
        // Fallback for sub_category label strings
        return switch (cv.toLowerCase().trim()) {
            case "shirt", "top", "blouse", "tshirt", "t-shirt", "tank", "polo" -> ClothingCategory.TOP;
            case "pants", "jeans", "shorts", "trousers", "skirt", "leggings"   -> ClothingCategory.BOTTOM;
            case "jacket", "coat", "hoodie", "sweater", "outerwear", "blazer"  -> ClothingCategory.OUTERWEAR;
            case "shoes", "sneakers", "boots", "sandals", "footwear", "heels"  -> ClothingCategory.FOOTWEAR;
            case "bag", "backpack", "purse", "handbag", "tote"                 -> ClothingCategory.BAG;
            case "dress", "gown", "jumpsuit"                                   -> ClothingCategory.DRESS;
            case "suit"                                                         -> ClothingCategory.SUIT;
            case "hat", "scarf", "belt", "accessory", "accessories",
                 "watch", "jewelry", "sunglasses", "gloves"                    -> ClothingCategory.ACCESSORY;
            default                                                             -> ClothingCategory.OTHER;
        };
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
