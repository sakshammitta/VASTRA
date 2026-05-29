package com.vastra.controller;

import com.vastra.dto.ClothingItemDto;
import com.vastra.service.ScanJobService;
import com.vastra.service.WardrobeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/wardrobe")
public class WardrobeController {

    private final WardrobeService wardrobeService;
    private final ScanJobService scanJobService;

    public WardrobeController(WardrobeService wardrobeService, ScanJobService scanJobService) {
        this.wardrobeService = wardrobeService;
        this.scanJobService = scanJobService;
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
        String jobId = wardrobeService.initiateItemScan((UUID) auth.getPrincipal(), image);
        return ResponseEntity.ok(Map.of("jobId", jobId, "status", "QUEUED"));
    }

    @GetMapping("/scan/{jobId}")
    public ResponseEntity<?> getScanStatus(@PathVariable String jobId) {
        Map<String, Object> job = scanJobService.getJobStatus(jobId);
        if (job == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(job);
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
