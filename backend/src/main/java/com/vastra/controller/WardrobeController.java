package com.vastra.controller;

import com.vastra.dto.ClothingItemDto;
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

    public WardrobeController(WardrobeService wardrobeService, ScanJobService scanJobService, R2Service r2Service) {
        this.wardrobeService = wardrobeService;
        this.scanJobService = scanJobService;
        this.r2Service = r2Service;
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
            Authentication auth) throws IOException {
        UUID userId = (UUID) auth.getPrincipal();
        log.info("POST /api/wardrobe/scan user={} filename={} size={}B contentType={}",
                userId, image.getOriginalFilename(), image.getSize(), image.getContentType());
        String jobId = wardrobeService.initiateItemScan(userId, image);
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

    @PostMapping("/items")
    public ResponseEntity<ClothingItemDto.ClothingItemResponse> createItem(
            @RequestBody ClothingItemDto.CreateItemRequest req,
            Authentication auth) {
        return ResponseEntity.ok(wardrobeService.createItem((UUID) auth.getPrincipal(), req));
    }

    @DeleteMapping("/items/{id}")
    public ResponseEntity<Void> deleteItem(@PathVariable UUID id, Authentication auth) {
        wardrobeService.deleteItem((UUID) auth.getPrincipal(), id);
        return ResponseEntity.ok().build();
    }
}
