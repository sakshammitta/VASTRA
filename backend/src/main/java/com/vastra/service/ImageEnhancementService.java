package com.vastra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vastra.dto.ClothingItemDto.WebMatchCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Produces clean e-commerce-quality wardrobe images via two strategies:
 *
 * 1. WEB_PRODUCT  — SerpAPI Google Lens reverse-image search on the crop;
 *                   returns up to 5 visual-match candidates for user confirmation.
 *                   Requires SERPAPI_KEY env var.
 *
 * 2. AI_RENDER    — OpenAI gpt-image-2 text-prompt generation using detected
 *                   garment attributes (category, subtype, colors, brand).
 *                   Requires OPENAI_API_KEY env var.
 *
 * Both services degrade gracefully when API keys are absent: callers receive
 * empty candidate lists or null render keys, and the item stays PENDING (the
 * app shows a placeholder, never the raw crop).
 *
 * PRIVACY (SerpAPI input image): searchWebMatches() is passed ONLY the detected
 * garment crop's presigned URL (R2 key under "item-crops/"), never the full
 * source selfie. R2Service.getPresignedUrl produces a short-lived signed URL
 * (1-hour expiry) unless a public-url base is configured. The user's original
 * outfit photo (R2 key under "scans/") is never sent to SerpAPI.
 */
@Service
public class ImageEnhancementService {

    private static final Logger log = LoggerFactory.getLogger(ImageEnhancementService.class);

    @Value("${vastra.serpapi.api-key:}")
    private String serpApiKey;

    @Value("${vastra.openai.api-key:}")
    private String openAiKey;

    @Value("${vastra.openai.image-model:gpt-image-2}")
    private String openAiImageModel;

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
     * Generate a clean fashion product image, VISUALLY GROUNDED in the detected
     * garment crop. Uses the OpenAI images/EDITS endpoint (not text-only
     * generations): the internal crop is sent as the image input together with a
     * prompt built from the user-confirmed attributes, so the render preserves
     * the actual color / type / silhouette / visible brand graphic of the item
     * the user owns — not a generic stock garment.
     *
     * @param cropPresignedUrl short-lived R2 URL of the detected garment crop
     *                         (item-crops/…), used only as the edit reference.
     * @param category         user-CONFIRMED category (e.g. "BOTTOM")
     * @param subCategory      user-CONFIRMED subtype (e.g. "joggers", not the AI's "trousers")
     * Uploads the result to R2 and returns the R2 key, or null on failure.
     */
    public String generateAiRender(
            String cropPresignedUrl, String category, String subCategory,
            List<String> colorPalette, String brand) {

        if (openAiKey.isBlank()) return null;

        String colorDesc  = colorPalette.isEmpty() ? ""
            : " in " + String.join(", ", colorPalette.subList(0, Math.min(2, colorPalette.size())));
        String brandDesc  = (brand != null && !brand.isBlank()) ? brand + " " : "";
        String garment    = subCategory.isBlank() ? category.toLowerCase() : subCategory;
        String prompt     = String.format(
            "Turn this into a realistic fashion e-commerce product photograph of the same " +
            "%s%s%s shown in the reference image. Keep the exact color, type, silhouette and " +
            "any visible logo or graphic faithful to the reference. Present the garment upright, " +
            "centered, isolated on a clean opaque neutral background. Remove any person, body " +
            "parts, hands, arms, phone, and background scene. Professional product shot, " +
            "Zara / H&M / ASOS online store style. High quality.",
            brandDesc, garment, colorDesc
        );

        try {
            // Download the crop to send as the image-edit reference input.
            byte[] cropBytes = restClient.get()
                .uri(cropPresignedUrl)
                .retrieve()
                .body(byte[].class);
            if (cropBytes == null || cropBytes.length == 0) {
                log.warn("ai-render: crop download empty, cannot ground render");
                return null;
            }

            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("model", openAiImageModel);
            form.add("prompt", prompt);
            form.add("n", "1");
            form.add("size", "1024x1024");
            ByteArrayResource imagePart = new ByteArrayResource(cropBytes) {
                @Override public String getFilename() { return "reference.png"; }
            };
            form.add("image", imagePart);

            String responseJson = restClient.post()
                .uri("https://api.openai.com/v1/images/edits")
                .header("Authorization", "Bearer " + openAiKey)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(String.class);

            JsonNode root   = objectMapper.readTree(responseJson);
            String b64      = root.path("data").get(0).path("b64_json").asText();
            byte[] imgBytes = Base64.getDecoder().decode(b64);

            String r2Key = r2Service.uploadBytes(imgBytes, "wardrobe-renders", "image/png");
            log.info("ai-render (edit, model={}): {} bytes → R2 key={}", openAiImageModel, imgBytes.length, r2Key);
            return r2Key;
        } catch (Exception e) {
            log.error("ai-render failed for {}/{} (model={}): {}", category, subCategory, openAiImageModel, e.getMessage());
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
