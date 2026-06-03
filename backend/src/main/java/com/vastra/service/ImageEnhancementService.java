package com.vastra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vastra.dto.ClothingItemDto.WebMatchCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Produces clean e-commerce-quality wardrobe images via two strategies:
 *
 * 1. WEB_PRODUCT  — SerpAPI Google Lens reverse-image search on the crop;
 *                   returns up to 5 visual-match candidates for user confirmation.
 *                   Requires SERPAPI_KEY env var.
 *
 * 2. AI_RENDER    — OpenAI gpt-image-1 text-prompt generation using detected
 *                   garment attributes (category, subtype, colors, brand).
 *                   Requires OPENAI_API_KEY env var.
 *
 * Both services degrade gracefully when API keys are absent: callers receive
 * empty candidate lists or null render keys and fall back to CROP display.
 */
@Service
public class ImageEnhancementService {

    private static final Logger log = LoggerFactory.getLogger(ImageEnhancementService.class);

    @Value("${vastra.serpapi.api-key:}")
    private String serpApiKey;

    @Value("${vastra.openai.api-key:}")
    private String openAiKey;

    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final R2Service r2Service;

    public ImageEnhancementService(R2Service r2Service) {
        this.r2Service = r2Service;
    }

    public boolean isWebMatchAvailable() { return !serpApiKey.isBlank(); }
    public boolean isAiRenderAvailable() { return !openAiKey.isBlank(); }

    /**
     * Run SerpAPI Google Lens visual search on the crop's presigned URL.
     * Returns up to 5 visual-match candidates with thumbnail + source URLs.
     * Returns empty list if SerpAPI key is absent or the search fails.
     */
    public List<WebMatchCandidate> searchWebMatches(
            String cropPresignedUrl, String category, String subCategory,
            List<String> colorPalette, String brand) {

        if (serpApiKey.isBlank()) return List.of();

        try {
            String body = restClient.get()
                .uri("https://serpapi.com/search.json?engine=google_lens&url={url}&api_key={key}&hl=en",
                    cropPresignedUrl, serpApiKey)
                .retrieve()
                .body(String.class);

            JsonNode root = objectMapper.readTree(body);
            JsonNode matches = root.path("visual_matches");
            List<WebMatchCandidate> results = new ArrayList<>();
            if (matches.isArray()) {
                for (JsonNode m : matches) {
                    if (results.size() >= 5) break;
                    String title     = m.path("title").asText("");
                    String imageUrl  = m.path("thumbnail").asText("");
                    String sourceUrl = m.path("link").asText("");
                    String siteName  = m.path("source").asText("");
                    if (!imageUrl.isBlank() && !sourceUrl.isBlank()) {
                        results.add(new WebMatchCandidate(title, imageUrl, sourceUrl,
                            siteName.isBlank() ? null : siteName));
                    }
                }
            }
            log.info("web-match: {} candidates for {}/{}", results.size(), category, subCategory);
            return results;
        } catch (Exception e) {
            log.warn("web-match search failed ({}): {}", category, e.getMessage());
            return List.of();
        }
    }

    /**
     * Generate a clean fashion product image with gpt-image-1.
     * Uploads the result to R2 and returns the R2 key, or null on failure.
     */
    public String generateAiRender(
            String category, String subCategory,
            List<String> colorPalette, String brand) {

        if (openAiKey.isBlank()) return null;

        String colorDesc  = colorPalette.isEmpty() ? ""
            : " in " + String.join(", ", colorPalette.subList(0, Math.min(2, colorPalette.size())));
        String brandDesc  = (brand != null && !brand.isBlank()) ? brand + " " : "";
        String garment    = subCategory.isBlank() ? category.toLowerCase() : subCategory;
        String prompt     = String.format(
            "Clean fashion e-commerce product photograph of a %s%s%s. " +
            "Upright, centered, isolated on plain white background. " +
            "No person, no body parts, no hands, no arms, no background. " +
            "Professional product shot, Zara or H&M online store style. High quality.",
            brandDesc, garment, colorDesc
        );

        try {
            String requestJson = objectMapper.writeValueAsString(Map.of(
                "model", "gpt-image-1",
                "prompt", prompt,
                "n", 1,
                "size", "1024x1024",
                "response_format", "b64_json"
            ));

            String responseJson = restClient.post()
                .uri("https://api.openai.com/v1/images/generations")
                .header("Authorization", "Bearer " + openAiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestJson)
                .retrieve()
                .body(String.class);

            JsonNode root   = objectMapper.readTree(responseJson);
            String b64      = root.path("data").get(0).path("b64_json").asText();
            byte[] imgBytes = Base64.getDecoder().decode(b64);

            String r2Key = r2Service.uploadBytes(imgBytes, "wardrobe-renders", "image/png");
            log.info("ai-render: {} bytes → R2 key={}", imgBytes.length, r2Key);
            return r2Key;
        } catch (Exception e) {
            log.error("ai-render failed for {}/{}: {}", category, subCategory, e.getMessage());
            return null;
        }
    }

    /**
     * Download an external image URL and store it in R2.
     * Used when confirming a web-match candidate — the candidate thumbnail URL
     * is downloaded so the stored image is under VASTRA's control and won't expire.
     * Returns the R2 key, or null on failure.
     */
    public String downloadAndStoreWebMatchImage(String externalImageUrl) {
        try {
            byte[] bytes = restClient.get()
                .uri(externalImageUrl)
                .retrieve()
                .body(byte[].class);
            if (bytes == null || bytes.length == 0) return null;
            String key = r2Service.uploadBytes(bytes, "web-matches", "image/jpeg");
            log.info("web-match download: {}B → R2 key={}", bytes.length, key);
            return key;
        } catch (Exception e) {
            log.warn("web-match download failed [{}]: {}", externalImageUrl, e.getMessage());
            return null;
        }
    }
}
