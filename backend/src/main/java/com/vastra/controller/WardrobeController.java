package com.vastra.controller;

import com.vastra.dto.ClothingItemDto;
import com.vastra.service.ImageEnhancementService;
import com.vastra.service.R2Service;
import com.vastra.service.ScanJobService;
import com.vastra.service.WardrobeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/wardrobe")
public class WardrobeController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(WardrobeController.class);

    private final WardrobeService wardrobeService;
    private final ScanJobService scanJobService;
    private final R2Service r2Service;
    private final ImageEnhancementService imageEnhancementService;

    public WardrobeController(WardrobeService wardrobeService, ScanJobService scanJobService,
                               R2Service r2Service, ImageEnhancementService imageEnhancementService) {
        this.wardrobeService = wardrobeService;
        this.scanJobService = scanJobService;
        this.r2Service = r2Service;
        this.imageEnhancementService = imageEnhancementService;
    }

    @GetMapping
    public ResponseEntity<List<ClothingItemDto.ClothingItemResponse>> getWardrobe(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            Authentication auth) {
        return ResponseEntity.ok(wardrobeService.getWardrobe((UUID) auth.getPrincipal(), category, status));
    }

    @PostMapping("/scan")
    public ResponseEntity<Map<String, String>> scanItem(
            @RequestPart("image") MultipartFile image,
            @RequestParam(value = "scan_mode", required = false) String scanMode,
            Authentication auth) throws IOException {
        UUID userId = (UUID) auth.getPrincipal();
        log.info("POST /api/wardrobe/scan user={} filename={} size={}B contentType={} scan_mode={}",
                userId, image.getOriginalFilename(), image.getSize(), image.getContentType(), scanMode);
        String jobId = wardrobeService.initiateItemScan(userId, image, scanMode);
        log.info("POST /api/wardrobe/scan accepted user={} jobId={}", userId, jobId);
        return ResponseEntity.ok(Map.of("jobId", jobId, "status", "QUEUED"));
    }

    @SuppressWarnings("unchecked")
    @GetMapping("/scan/{jobId}")
    public ResponseEntity<?> getScanStatus(@PathVariable String jobId) {
        Map<String, Object> job = scanJobService.getJobStatus(jobId);
        if (job == null) return ResponseEntity.notFound().build();

        // Enrich each detected item with a cropUrl so clients can display the crop image.
        List<Map<String, Object>> rawItems =
                (List<Map<String, Object>>) job.getOrDefault("detectedItems", List.of());
        List<Map<String, Object>> enriched = new ArrayList<>(rawItems.size());
        for (Map<String, Object> item : rawItems) {
            Map<String, Object> copy = new HashMap<>(item);
            String cropKey = (String) item.get("crop_key");
            copy.put("cropUrl", r2Service.getPresignedUrl(cropKey));
            enriched.add(copy);
        }
        Map<String, Object> response = new HashMap<>(job);
        response.put("detectedItems", enriched);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/scan/{jobId}/confirm")
    public ResponseEntity<ClothingItemDto.ClothingItemResponse> confirmScanItem(
            @PathVariable String jobId,
            @RequestBody ClothingItemDto.ConfirmScanItemRequest req,
            Authentication auth) {
        return ResponseEntity.ok(wardrobeService.confirmScanItem((UUID) auth.getPrincipal(), jobId, req));
    }

    /**
     * POST /api/wardrobe/scan/{jobId}/items/{itemIndex}/web-match
     *
     * Runs SerpAPI Google Lens reverse-image search on the detected item's crop.
     * Returns up to 5 visual-match candidates for display in the review flow.
     * Returns available=false when SERPAPI_KEY is not configured.
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/scan/{jobId}/items/{itemIndex}/web-match")
    public ResponseEntity<ClothingItemDto.WebMatchResponse> requestWebMatch(
            @PathVariable String jobId,
            @PathVariable int itemIndex,
            @RequestBody(required = false) ClothingItemDto.EnhanceImageRequest req) {

        if (!imageEnhancementService.isWebMatchAvailable()) {
            return ResponseEntity.ok(new ClothingItemDto.WebMatchResponse(List.of(), false));
        }

        Map<String, Object> job = scanJobService.getJobStatus(jobId);
        if (job == null) return ResponseEntity.notFound().build();

        List<Map<String, Object>> items =
            (List<Map<String, Object>>) job.getOrDefault("detectedItems", List.of());
        if (itemIndex < 0 || itemIndex >= items.size()) return ResponseEntity.badRequest().build();

        Map<String, Object> detected = items.get(itemIndex);
        String cropKey    = (String) detected.get("crop_key");
        String cropUrl    = r2Service.getPresignedUrl(cropKey);
        // Prefer the user-corrected identity (e.g. "joggers") over the CV guess.
        String category   = override(req != null ? req.category() : null,
                                     (String) detected.getOrDefault("category", "OTHER"));
        String subCat     = override(req != null ? req.subCategory() : null,
                                     (String) detected.getOrDefault("sub_category", ""));
        String brand      = req != null ? req.brand() : null;
        List<String> colors = (List<String>) detected.getOrDefault("color_palette", List.of());

        List<ClothingItemDto.WebMatchCandidate> candidates =
            imageEnhancementService.searchWebMatches(cropUrl, category, subCat, colors, brand);

        return ResponseEntity.ok(new ClothingItemDto.WebMatchResponse(candidates, true));
    }

    private static String override(String corrected, String fallback) {
        return (corrected != null && !corrected.isBlank()) ? corrected : fallback;
    }

    /**
     * POST /api/wardrobe/scan/{jobId}/items/{itemIndex}/ai-render
     *
     * Generates a clean fashion e-commerce product image using gpt-image-1.
     * Uploads the result to R2 and returns the presigned URL + R2 key.
     * Returns available=false when OPENAI_API_KEY is not configured.
     * renderUrl/renderKey are null on generation failure.
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/scan/{jobId}/items/{itemIndex}/ai-render")
    public ResponseEntity<ClothingItemDto.AiRenderResponse> requestAiRender(
            @PathVariable String jobId,
            @PathVariable int itemIndex,
            @RequestBody(required = false) ClothingItemDto.EnhanceImageRequest req) {

        if (!imageEnhancementService.isAiRenderAvailable()) {
            return ResponseEntity.ok(new ClothingItemDto.AiRenderResponse(null, null, false, null));
        }

        Map<String, Object> job = scanJobService.getJobStatus(jobId);
        if (job == null) return ResponseEntity.notFound().build();

        List<Map<String, Object>> items =
            (List<Map<String, Object>>) job.getOrDefault("detectedItems", List.of());
        if (itemIndex < 0 || itemIndex >= items.size()) return ResponseEntity.badRequest().build();

        Map<String, Object> detected = items.get(itemIndex);
        String cropKey    = (String) detected.get("crop_key");
        String cropUrl    = r2Service.getPresignedUrl(cropKey);
        String category   = override(req != null ? req.category() : null,
                                     (String) detected.getOrDefault("category", "OTHER"));
        String subCat     = override(req != null ? req.subCategory() : null,
                                     (String) detected.getOrDefault("sub_category", ""));
        String brand      = req != null ? req.brand() : null;
        List<String> colors = (List<String>) detected.getOrDefault("color_palette", List.of());

        String[] result = imageEnhancementService.generateAiRenderWithReason(cropUrl, category, subCat, colors, brand);
        String renderKey = result[0];
        String failureReason = result[1];
        if (renderKey == null) {
            return ResponseEntity.ok(new ClothingItemDto.AiRenderResponse(null, null, true, failureReason));
        }

        String renderUrl = r2Service.getPresignedUrl(renderKey);
        return ResponseEntity.ok(new ClothingItemDto.AiRenderResponse(renderUrl, renderKey, true, null));
    }

    /**
     * Enhance an ALREADY-SAVED wardrobe item (e.g. a PENDING item saved before
     * API keys were configured) directly from the Wardrobe page — no rescan.
     * Runs SerpAPI Google Lens on the item's stored crop. Candidates are not
     * auto-confirmed; the client calls /display-image to commit a choice.
     */
    @PostMapping("/items/{id}/web-match")
    public ResponseEntity<ClothingItemDto.WebMatchResponse> webMatchForItem(
            @PathVariable UUID id,
            @RequestBody(required = false) ClothingItemDto.EnhanceImageRequest req,
            Authentication auth) {
        return ResponseEntity.ok(
            wardrobeService.webMatchForSavedItem((UUID) auth.getPrincipal(), id, req));
    }

    /**
     * Generate an AI clean render grounded in a saved item's stored crop.
     * Not auto-saved; the client calls /display-image to approve the render.
     */
    @PostMapping("/items/{id}/ai-render")
    public ResponseEntity<ClothingItemDto.AiRenderResponse> aiRenderForItem(
            @PathVariable UUID id,
            @RequestBody(required = false) ClothingItemDto.EnhanceImageRequest req,
            Authentication auth) {
        return ResponseEntity.ok(
            wardrobeService.aiRenderForSavedItem((UUID) auth.getPrincipal(), id, req));
    }

    /**
     * Commit a user-confirmed clean display image (web match or approved AI
     * render) onto a saved item. The raw crop is never set as the display image.
     */
    @PostMapping("/items/{id}/display-image")
    public ResponseEntity<ClothingItemDto.ClothingItemResponse> setItemDisplayImage(
            @PathVariable UUID id,
            @RequestBody ClothingItemDto.SetDisplayImageRequest req,
            Authentication auth) {
        return ResponseEntity.ok(
            wardrobeService.setItemDisplayImage((UUID) auth.getPrincipal(), id, req));
    }

    @PostMapping("/items")
    public ResponseEntity<ClothingItemDto.ClothingItemResponse> createItem(
            @RequestBody ClothingItemDto.CreateItemRequest req,
            Authentication auth) {
        return ResponseEntity.ok(wardrobeService.createItem((UUID) auth.getPrincipal(), req));
    }

    @PutMapping("/items/{id}")
    public ResponseEntity<ClothingItemDto.ClothingItemResponse> updateItem(
            @PathVariable UUID id,
            @RequestBody ClothingItemDto.UpdateItemRequest req,
            Authentication auth) {
        return ResponseEntity.ok(
            wardrobeService.updateItem((UUID) auth.getPrincipal(), id, req));
    }

    @DeleteMapping("/items/{id}")
    public ResponseEntity<Void> deleteItem(@PathVariable UUID id, Authentication auth) {
        wardrobeService.deleteItem((UUID) auth.getPrincipal(), id);
        return ResponseEntity.ok().build();
    }
}
