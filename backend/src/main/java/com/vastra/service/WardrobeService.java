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
    private final ImageEnhancementService imageEnhancementService;

    public WardrobeService(ClothingItemRepository itemRepo, UserRepository userRepo,
                           R2Service r2Service, ScanJobService scanJobService,
                           ImageEnhancementService imageEnhancementService) {
        this.itemRepo = itemRepo;
        this.userRepo = userRepo;
        this.r2Service = r2Service;
        this.scanJobService = scanJobService;
        this.imageEnhancementService = imageEnhancementService;
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
    public String initiateItemScan(UUID userId, MultipartFile image, String scanMode) throws IOException {
        long t0 = System.currentTimeMillis();
        String imageKey = r2Service.upload(image, "scans/" + userId);
        log.info("timing r2-upload: {}ms  key={}", System.currentTimeMillis() - t0, imageKey);
        String jobId = UUID.randomUUID().toString();
        scanJobService.processScanAsync(jobId, userId, imageKey, scanMode);
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

        // ── Display image selection ───────────────────────────────────────────
        // Only a user-confirmed web product match or a user-approved AI render
        // may become the wardrobe display image. If neither was confirmed, the
        // item is saved as PENDING — the app shows a placeholder, NEVER the raw
        // crop. The truth crop stays in r2ImageKey (reference/evidence only).
        String webMatchImageUrl = req.webMatchImageUrl();
        String aiRenderKey      = req.aiRenderKey();
        item.setDisplayImageSource(DisplayImageSource.PENDING);
        if (webMatchImageUrl != null && !webMatchImageUrl.isBlank()) {
            // Download the confirmed web product thumbnail and store it in R2
            // so the display image is under VASTRA's control and won't expire.
            String displayKey = imageEnhancementService.downloadAndStoreWebMatchImage(webMatchImageUrl);
            if (displayKey != null) {
                item.setDisplayImageKey(displayKey);
                item.setDisplayImageSource(DisplayImageSource.WEB_PRODUCT);
                item.setWebMatchUrl(req.webMatchSourceUrl());
            }
        } else if (aiRenderKey != null && !aiRenderKey.isBlank()) {
            item.setDisplayImageKey(aiRenderKey);
            item.setDisplayImageSource(DisplayImageSource.AI_RENDER);
        }

        item = itemRepo.save(item);
        itemRepo.flush();
        item = itemRepo.findById(item.getId()).orElseThrow();
        user.setItemCount(user.getItemCount() + 1);
        userRepo.save(user);

        // Use the effective display image (web match or AI render if set, else crop)
        String imageUrl = r2Service.getPresignedUrl(item.getEffectiveImageKey());
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

    // ── Enhance an already-saved wardrobe item ────────────────────────────────
    // These let a PENDING item saved before API keys were configured be enhanced
    // later, directly from the Wardrobe page — without rescanning. They reuse the
    // item's permanently-stored crop (r2ImageKey) as the reference/grounding
    // input, and the item's user-confirmed attributes (overridable). All rules
    // are unchanged: explicit user action only, no auto-confirm, no raw crop as
    // the final display image.

    /** Load an item and verify the caller owns it. */
    private ClothingItemEntity ownedItem(UUID userId, UUID itemId) {
        var item = itemRepo.findById(itemId).orElseThrow();
        if (!item.getOwner().getId().equals(userId)) throw new SecurityException("Not your item");
        return item;
    }

    private static String override(String corrected, String fallback) {
        return (corrected != null && !corrected.isBlank()) ? corrected : fallback;
    }

    /**
     * Run SerpAPI Google Lens on a saved item's stored crop. Returns up to 5
     * visual-match candidates. available=false when SERPAPI_KEY is unset or the
     * item has no crop reference. Candidates are never auto-confirmed.
     */
    @Transactional(readOnly = true)
    public ClothingItemDto.WebMatchResponse webMatchForSavedItem(
            UUID userId, UUID itemId, ClothingItemDto.EnhanceImageRequest req) {
        var item = ownedItem(userId, itemId);
        String cropKey = item.getR2ImageKey();
        if (!imageEnhancementService.isWebMatchAvailable()) {
            log.info("web-match unavailable for item {}: SERPAPI_KEY not configured", itemId);
            return new ClothingItemDto.WebMatchResponse(List.of(), false);
        }
        if (cropKey == null || cropKey.isBlank()) {
            log.warn("web-match skipped for item {}: no crop reference stored (r2ImageKey is null)", itemId);
            return new ClothingItemDto.WebMatchResponse(List.of(), true);
        }
        String cropUrl  = r2Service.getPresignedUrl(cropKey);
        String category = override(req != null ? req.category() : null,
                item.getCategory() != null ? item.getCategory().name() : "OTHER");
        String subCat   = override(req != null ? req.subCategory() : null, item.getSubCategory());
        String brand    = (req != null && req.brand() != null) ? req.brand() : item.getBrand();
        var candidates  = imageEnhancementService.searchWebMatches(
                cropUrl, category, subCat,
                item.getColorPalette() != null ? item.getColorPalette() : List.of(), brand);
        return new ClothingItemDto.WebMatchResponse(candidates, true);
    }

    /**
     * Generate an AI clean render grounded in a saved item's stored crop.
     * available=false when OPENAI_API_KEY is unset or the item has no crop.
     * renderKey/renderUrl are null on failure. Never auto-saved — the client must
     * call setItemDisplayImage to approve it.
     */
    @Transactional(readOnly = true)
    public ClothingItemDto.AiRenderResponse aiRenderForSavedItem(
            UUID userId, UUID itemId, ClothingItemDto.EnhanceImageRequest req) {
        var item = ownedItem(userId, itemId);
        String cropKey = item.getR2ImageKey();
        if (!imageEnhancementService.isAiRenderAvailable()) {
            log.info("ai-render unavailable for item {}: OPENAI_API_KEY not configured", itemId);
            return new ClothingItemDto.AiRenderResponse(null, null, false, null);
        }
        if (cropKey == null || cropKey.isBlank()) {
            log.warn("ai-render skipped for item {}: no crop reference stored (r2ImageKey is null)", itemId);
            return new ClothingItemDto.AiRenderResponse(null, null, true,
                "No crop reference stored for this item. Try rescanning.");
        }
        String cropUrl  = r2Service.getPresignedUrl(cropKey);
        String category = override(req != null ? req.category() : null,
                item.getCategory() != null ? item.getCategory().name() : "OTHER");
        String subCat   = override(req != null ? req.subCategory() : null, item.getSubCategory());
        String brand    = (req != null && req.brand() != null) ? req.brand() : item.getBrand();
        String[] result = imageEnhancementService.generateAiRenderWithReason(
                cropUrl, category, subCat,
                item.getColorPalette() != null ? item.getColorPalette() : List.of(), brand);
        String renderKey = result[0];
        String failureReason = result[1];
        if (renderKey == null) return new ClothingItemDto.AiRenderResponse(null, null, true, failureReason);
        return new ClothingItemDto.AiRenderResponse(r2Service.getPresignedUrl(renderKey), renderKey, true, null);
    }

    /**
     * Persist a user-confirmed clean display image onto an already-saved item.
     * Mirrors the display-image selection in confirmScanItem: a web match is
     * downloaded + stored (source=WEB_PRODUCT), or an approved AI render key is
     * adopted (source=AI_RENDER). The truth crop in r2ImageKey is never changed.
     */
    @Transactional
    public ClothingItemDto.ClothingItemResponse setItemDisplayImage(
            UUID userId, UUID itemId, ClothingItemDto.SetDisplayImageRequest req) {
        var item = ownedItem(userId, itemId);
        String webMatchImageUrl = req != null ? req.webMatchImageUrl() : null;
        String aiRenderKey      = req != null ? req.aiRenderKey() : null;

        if (webMatchImageUrl != null && !webMatchImageUrl.isBlank()) {
            String displayKey = imageEnhancementService.downloadAndStoreWebMatchImage(webMatchImageUrl);
            if (displayKey != null) {
                item.setDisplayImageKey(displayKey);
                item.setDisplayImageSource(DisplayImageSource.WEB_PRODUCT);
                item.setWebMatchUrl(req.webMatchSourceUrl());
            }
        } else if (aiRenderKey != null && !aiRenderKey.isBlank()) {
            item.setDisplayImageKey(aiRenderKey);
            item.setDisplayImageSource(DisplayImageSource.AI_RENDER);
        }

        item = itemRepo.save(item);
        itemRepo.flush();
        item = itemRepo.findById(item.getId()).orElseThrow();
        String imageUrl = r2Service.getPresignedUrl(item.getEffectiveImageKey());
        return ClothingItemDto.ClothingItemResponse.from(item, imageUrl,
                r2Service.getPresignedUrl(item.getR2ThumbnailKey()));
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
