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

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Produces clean e-commerce-quality wardrobe images via two strategies:
 *
 * 1. WEB_PRODUCT  — SerpAPI Google Lens reverse-image search on the crop;
 *                   applies domain allow/block filtering and quality scoring so
 *                   only clean brand/retailer product images are returned.
 *                   Requires SERPAPI_KEY env var.
 *
 * 2. AI_RENDER    — OpenAI gpt-image-2 image-edit grounded in the crop.
 *                   Requires OPENAI_API_KEY env var.
 *
 * Both services degrade gracefully when API keys are absent.
 *
 * PRIVACY (SerpAPI): searchWebMatches() receives ONLY the detected garment
 * crop presigned URL (item-crops/…), never the full source selfie (scans/…).
 */
@Service
public class ImageEnhancementService {

    private static final Logger log = LoggerFactory.getLogger(ImageEnhancementService.class);

    // ── Domain quality tiers ─────────────────────────────────────────────────
    // +3: official brand / top-tier fashion retailers — clean product photography
    private static final Set<String> PREFERRED_DOMAINS = Set.of(
        "nike.com", "adidas.com", "zara.com", "hm.com", "uniqlo.com",
        "gap.com", "levi.com", "levis.com", "ralphlauren.com",
        "tommyhilfiger.com", "calvinklein.com", "guess.com",
        "lacoste.com", "champion.com", "puma.com", "newbalance.com",
        "underarmour.com", "columbia.com", "patagonia.com", "arc'teryx.com",
        "asos.com", "nordstrom.com", "ssense.com", "farfetch.com",
        "mrporter.com", "endclothing.com", "urbanoutfitters.com",
        "anthropologie.com", "freepeople.com", "revolve.com",
        "shopbop.com", "net-a-porter.com", "matchesfashion.com",
        "johnlewis.com", "marks-and-spencer.com", "marksandspencer.com",
        "myntra.com", "ajio.com", "nykaa.com"
    );

    // +1: broader fashion / department retail — usually has clean product images
    private static final Set<String> ACCEPTABLE_DOMAINS = Set.of(
        "amazon.com", "target.com", "walmart.com", "macys.com",
        "bloomingdales.com", "saks.com", "saksfifthavenue.com",
        "google.com",           // Google Shopping passes through google.com
        "shopping.google.com",
        "kohls.com", "jcrew.com", "bananarepublic.com", "oldnavy.com",
        "forever21.com", "primark.com", "pull&bear.com", "bershka.com",
        "stradivarius.com", "mango.com", "next.co.uk", "boohoo.com",
        "prettylittlething.com", "missguided.com", "topshop.com",
        "river-island.com", "riverisland.com", "acnestudios.com",
        "aritzia.com", "reiss.com", "allsaints.com", "& other stories",
        "stories.com", "weekday.com", "arket.com", "cosstores.com"
    );

    // Hard-blocked: resale / social / blog — never clean product photos
    private static final Set<String> BLOCKED_DOMAINS = Set.of(
        "ebay.com", "ebay.co.uk", "ebay.ca", "ebay.au",
        "poshmark.com", "depop.com", "mercari.com", "grailed.com",
        "vinted.com", "thredup.com", "therealreal.com",
        "tradesy.com", "vestiaire.com",
        "pinterest.com", "pinterest.co.uk",
        "reddit.com", "imgur.com", "tumblr.com",
        "instagram.com", "facebook.com", "tiktok.com",
        "twitter.com", "x.com",
        "aliexpress.com", "alibaba.com", "dhgate.com", "wish.com", "temu.com",
        "shein.com"
    );

    // Minimum acceptable thumbnail width/height in pixels (SerpAPI provides
    // original_dimensions for visual_matches; skip tiny/badly-cropped thumbs).
    private static final int MIN_DIMENSION_PX = 200;

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
     * Run SerpAPI Google Lens on the crop and return up to 5 ranked clean
     * product-image candidates. Applies three-tier domain scoring, dimension
     * filtering, and a quality floor: if no candidate scores above zero the
     * list is returned empty, which the client treats as "no clean match found
     * — generate a clean image instead."
     *
     * Source strategy:
     *   1. Lens "products" array  — shopping-context results, highest quality
     *   2. Lens "visual_matches"  — general visual results, filtered+scored
     * Both are merged, de-duplicated by domain, then sorted by score desc.
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

            // Scoring keywords: user-confirmed subtype and brand
            String subLower   = subCategory == null ? "" : subCategory.toLowerCase();
            String brandLower = brand == null ? "" : brand.toLowerCase();

            List<ScoredCandidate> pool = new ArrayList<>();

            // ── 1. Products array (Google Lens shopping context) ───────────────
            JsonNode products = root.path("lens_results").path("products");
            if (!products.isArray()) products = root.path("products");
            if (products.isArray()) {
                for (JsonNode p : products) {
                    String title     = p.path("title").asText("");
                    String imageUrl  = p.path("thumbnail").asText("");
                    if (imageUrl.isBlank()) imageUrl = p.path("image").asText("");
                    String sourceUrl = p.path("link").asText("");
                    if (sourceUrl.isBlank()) sourceUrl = p.path("product_link").asText("");
                    String siteName  = p.path("source").asText("");
                    if (!imageUrl.isBlank() && !sourceUrl.isBlank()) {
                        int score = scoreCandidate(sourceUrl, title, subLower, brandLower,
                                p.path("original_dimensions"),
                                /* fromProductsArray= */ true);
                        if (score > Integer.MIN_VALUE) {
                            pool.add(new ScoredCandidate(title, imageUrl, sourceUrl,
                                    siteName.isBlank() ? null : siteName, score));
                        }
                    }
                }
            }

            // ── 2. Visual matches array (general Lens results) ────────────────
            JsonNode visual = root.path("visual_matches");
            if (visual.isArray()) {
                for (JsonNode m : visual) {
                    String title     = m.path("title").asText("");
                    String imageUrl  = m.path("thumbnail").asText("");
                    String sourceUrl = m.path("link").asText("");
                    String siteName  = m.path("source").asText("");
                    if (!imageUrl.isBlank() && !sourceUrl.isBlank()) {
                        int score = scoreCandidate(sourceUrl, title, subLower, brandLower,
                                m.path("original_dimensions"),
                                /* fromProductsArray= */ false);
                        if (score > Integer.MIN_VALUE) {
                            pool.add(new ScoredCandidate(title, imageUrl, sourceUrl,
                                    siteName.isBlank() ? null : siteName, score));
                        }
                    }
                }
            }

            // Sort by score descending, de-duplicate by eTLD+1, take top 5.
            pool.sort(Comparator.comparingInt(ScoredCandidate::score).reversed());
            List<WebMatchCandidate> results = new ArrayList<>();
            Set<String> seenDomains = new java.util.HashSet<>();
            for (ScoredCandidate sc : pool) {
                if (results.size() >= 5) break;
                String domain = rootDomain(sc.sourceUrl());
                if (!seenDomains.add(domain)) continue;  // one result per domain
                results.add(new WebMatchCandidate(sc.title(), sc.imageUrl(), sc.sourceUrl(), sc.siteName()));
            }

            // Quality floor: if the best candidate scored ≤ 0, return empty —
            // there are no clean product images worth showing. The client will
            // present "No clean product match found" and offer Generate clean image.
            boolean hasQualityResults = !pool.isEmpty() && pool.get(0).score() > 0;
            if (!hasQualityResults) {
                log.info("web-match: no quality candidates for {}/{} (pool={}, all scored ≤ 0 — suggest AI render)",
                        category, subCategory, pool.size());
                return List.of();
            }

            log.info("web-match: {} quality candidates for {}/{} (pool={}, top-score={})",
                    results.size(), category, subCategory, pool.size(),
                    pool.isEmpty() ? 0 : pool.get(0).score());
            return results;

        } catch (Exception e) {
            log.warn("web-match search failed ({}): {}", category, e.getMessage());
            return List.of();
        }
    }

    /**
     * Score a single candidate. Returns {@code Integer.MIN_VALUE} to signal
     * "hard reject" (blocked domain or image too small). Otherwise returns a
     * score ≥ −1 that reflects how suitable this image is as a wardrobe display:
     *
     *  +3  preferred brand / top retailer domain
     *  +1  acceptable fashion / department store domain
     *   0  unknown domain (neutral — user can decide)
     *  −1  negative indicator (no domain match, no product context)
     *  +2  extra: from the products array (shopping-context signals quality)
     *  +1  extra: confirmed subtype appears in the title
     *  +1  extra: confirmed brand appears in the title
     */
    private int scoreCandidate(String sourceUrl, String title,
                               String subLower, String brandLower,
                               JsonNode dims, boolean fromProductsArray) {
        String host = rootDomain(sourceUrl);

        // Hard block resale / social / low-trust sites.
        if (isBlocked(host)) {
            log.debug("web-match: blocked domain {} — skipping", host);
            return Integer.MIN_VALUE;
        }

        // Dimension filter: skip thumbnails too small to be clean product shots.
        if (dims != null && !dims.isMissingNode()) {
            int w = dims.path("width").asInt(0);
            int h = dims.path("height").asInt(0);
            if ((w > 0 && w < MIN_DIMENSION_PX) || (h > 0 && h < MIN_DIMENSION_PX)) {
                log.debug("web-match: thumbnail too small ({}x{}) — skipping {}", w, h, host);
                return Integer.MIN_VALUE;
            }
        }

        int score = 0;
        if (isPreferred(host))   score += 3;
        else if (isAcceptable(host)) score += 1;
        else score -= 1;   // unknown domain: slight negative

        if (fromProductsArray)   score += 2;  // shopping-context signal

        String titleLower = title.toLowerCase();
        if (!subLower.isBlank() && titleLower.contains(subLower))     score += 1;
        if (!brandLower.isBlank() && titleLower.contains(brandLower)) score += 1;

        return score;
    }

    /** Extract registrable domain (e.g. "ebay.com") from a URL for blocklist checks. */
    private static String rootDomain(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return url.toLowerCase();
            host = host.toLowerCase();
            if (host.startsWith("www.")) host = host.substring(4);
            // Keep only last two labels (e.g. ebay.co.uk → ebay.co.uk is intentional)
            return host;
        } catch (Exception e) {
            return url.toLowerCase();
        }
    }

    private boolean isBlocked(String host) {
        return BLOCKED_DOMAINS.stream().anyMatch(host::endsWith);
    }

    private boolean isPreferred(String host) {
        return PREFERRED_DOMAINS.stream().anyMatch(host::endsWith);
    }

    private boolean isAcceptable(String host) {
        return ACCEPTABLE_DOMAINS.stream().anyMatch(host::endsWith);
    }

    /** Internal scored tuple used during ranking, discarded before returning. */
    private record ScoredCandidate(
        String title, String imageUrl, String sourceUrl, String siteName, int score) {}

    /**
     * Generate a clean fashion product image, VISUALLY GROUNDED in the detected
     * garment crop. Uses the OpenAI images/EDITS endpoint: the internal crop is
     * sent as the image input so the render preserves the actual color / type /
     * silhouette / visible brand graphic of the item the user owns.
     *
     * @param cropPresignedUrl short-lived R2 URL of the detected garment crop
     *                         (item-crops/…), used only as the edit reference.
     * Uploads the result to R2 and returns the R2 key, or null on failure.
     */
    public String generateAiRender(
            String cropPresignedUrl, String category, String subCategory,
            List<String> colorPalette, String brand) {

        if (openAiKey.isBlank()) return null;

        String colorDesc = colorPalette.isEmpty() ? ""
            : " in " + String.join(", ", colorPalette.subList(0, Math.min(2, colorPalette.size())));
        String brandDesc = (brand != null && !brand.isBlank()) ? brand + " " : "";
        String garment   = subCategory.isBlank() ? category.toLowerCase() : subCategory;
        String prompt    = String.format(
            "Turn this into a realistic fashion e-commerce product photograph of the same " +
            "%s%s%s shown in the reference image. Keep the exact color, type, silhouette and " +
            "any visible logo or graphic faithful to the reference. Present the garment upright, " +
            "centered, isolated on a clean opaque neutral background. Remove any person, body " +
            "parts, hands, arms, phone, and background scene. Professional product shot, " +
            "Zara / H&M / ASOS online store style. High quality.",
            brandDesc, garment, colorDesc
        );

        try {
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
     * Used when confirming a web-match candidate so the display image is under
     * VASTRA's control and won't expire. Returns the R2 key, or null on failure.
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
