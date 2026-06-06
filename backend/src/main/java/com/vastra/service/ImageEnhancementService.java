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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.*;

/**
 * Produces clean e-commerce-quality wardrobe images via two strategies:
 *
 * 1. WEB_PRODUCT  — SerpAPI Google Lens reverse-image search on the crop;
 *                   applies domain allow/block filtering, color-mismatch
 *                   detection, and type-strict scoring so only clean,
 *                   correct-color, correct-type retailer product images appear.
 *                   Requires SERPAPI_KEY env var.
 *
 * 2. AI_RENDER    — OpenAI gpt-image-2 image-edit grounded in the crop.
 *                   Returns a specific failureReason on error.
 *                   Requires OPENAI_API_KEY env var.
 *
 * PRIVACY (SerpAPI): searchWebMatches() receives ONLY the detected garment
 * crop presigned URL (item-crops/…), never the full source selfie (scans/…).
 */
@Service
public class ImageEnhancementService {

    private static final Logger log = LoggerFactory.getLogger(ImageEnhancementService.class);

    // ── Domain quality tiers ─────────────────────────────────────────────────

    private static final Set<String> PREFERRED_DOMAINS = Set.of(
        "nike.com", "adidas.com", "puma.com", "newbalance.com", "reebok.com",
        "underarmour.com", "champion.com", "lululemon.com", "gymshark.com",
        "asics.com", "fila.com", "kappa.com", "umbro.com",
        "manutd.com", "store.manutd.com", "mancity.com", "arsenal.com",
        "chelseafc.com", "liverpoolfc.com", "tottenhamhotspur.com",
        "realmadrid.com", "fcbarcelona.com", "juventus.com",
        "fanatics.com", "fanatics.co.uk",
        "zara.com", "hm.com", "uniqlo.com", "gap.com", "levi.com", "levis.com",
        "ralphlauren.com", "tommyhilfiger.com", "calvinklein.com", "guess.com",
        "lacoste.com", "columbia.com", "patagonia.com", "arcteryx.com",
        "asos.com", "nordstrom.com", "ssense.com", "farfetch.com",
        "mrporter.com", "endclothing.com", "urbanoutfitters.com",
        "anthropologie.com", "freepeople.com", "revolve.com",
        "shopbop.com", "net-a-porter.com", "matchesfashion.com",
        "johnlewis.com", "marks-and-spencer.com", "marksandspencer.com",
        "myntra.com", "ajio.com", "nykaa.com",
        // Indian traditional / ethnic clothing
        "manyavar.com", "fabindia.com", "jaypore.com", "tasva.com",
        "houseofpataudi.com", "utsavfashion.com", "cbazaar.com",
        // Footwear-specific brand sites
        "vans.com", "converse.com", "timberland.com", "skechers.com",
        "salomon.com", "brooks.com", "hoka.com", "onrunning.com", "on-running.com",
        "mizuno.com", "merrell.com",
        "clarks.com", "drmartens.com", "ugg.com",
        "crocs.com", "birkenstock.com", "teva.com",
        // Footwear retailers
        "zappos.com", "dsw.com", "shoecarnival.com", "stevemadden.com",
        "aldoshoes.com", "aldoshoes.co.uk", "schuh.co.uk", "office.co.uk"
    );

    private static final Set<String> ACCEPTABLE_DOMAINS = Set.of(
        "amazon.com", "amazon.co.uk", "amazon.in", "target.com", "walmart.com",
        "macys.com", "bloomingdales.com", "saks.com", "saksfifthavenue.com",
        "google.com", "shopping.google.com",
        "kohls.com", "jcrew.com", "bananarepublic.com", "oldnavy.com",
        "forever21.com", "primark.com", "bershka.com",
        "stradivarius.com", "mango.com", "next.co.uk", "boohoo.com",
        "prettylittlething.com", "missguided.com", "topshop.com",
        "river-island.com", "riverisland.com", "acnestudios.com",
        "aritzia.com", "reiss.com", "allsaints.com",
        "stories.com", "weekday.com", "arket.com", "cosstores.com",
        "sportsdirect.com", "jdsports.com", "jdsports.co.uk", "footlocker.com",
        "finishline.com", "prodirectsport.com", "kitbag.com"
    );

    private static final Set<String> BLOCKED_DOMAINS = Set.of(
        "ebay.com", "ebay.co.uk", "ebay.ca", "ebay.com.au", "ebay.de",
        "poshmark.com", "depop.com", "mercari.com", "grailed.com",
        "vinted.com", "vinted.co.uk", "thredup.com", "therealreal.com",
        "tradesy.com", "vestiairecollective.com", "vestiaire.com",
        "pinterest.com", "pinterest.co.uk", "reddit.com", "imgur.com",
        "tumblr.com", "instagram.com", "facebook.com", "tiktok.com",
        "twitter.com", "x.com", "youtube.com",
        "aliexpress.com", "alibaba.com", "dhgate.com", "wish.com", "temu.com",
        "shein.com", "lightinthebox.com", "banggood.com",
        "footy.com", "footyheadlines.com", "footballshirtculture.com",
        "classicfootballshirts.com", "vintagefootballshirts.com",
        "kitbag-aggregator.com"
    );

    // Garment subtypes that must NOT be interchangeable when checking title.
    // Each entry is a set of terms that a title MUST contain at least one of,
    // given the confirmed subtype key. If the title contains terms from a
    // COMPETING group (that maps to a different type), penalise heavily.
    private static final Map<String, Set<String>> SUBTYPE_TITLE_REQUIRED = Map.of(
        "hoodie",         Set.of("hoodie", "hooded", "pullover hoodie"),
        "zip-up hoodie",  Set.of("zip", "full-zip", "half-zip", "hoodie"),
        "sweatshirt",     Set.of("sweatshirt", "crew", "crewneck", "pullover"),
        "sweater",        Set.of("sweater", "knitwear", "knit", "pullover", "jumper"),
        "jacket",         Set.of("jacket"),
        "track jacket",   Set.of("track jacket", "track top", "training jacket"),
        "jersey",         Set.of("jersey", "kit", "shirt")
    );

    // Color names → typical hex ranges (checked against item's palette)
    // Used to detect when a candidate title mentions a different color.
    private static final Map<String, int[][]> COLOR_RANGES = new LinkedHashMap<>();
    static {
        // [R_min,R_max, G_min,G_max, B_min,B_max]
        COLOR_RANGES.put("black",  new int[][]{{0,60},{0,60},{0,60}});
        COLOR_RANGES.put("white",  new int[][]{{195,255},{195,255},{195,255}});
        COLOR_RANGES.put("grey",   new int[][]{{60,195},{60,195},{60,195}});
        COLOR_RANGES.put("gray",   new int[][]{{60,195},{60,195},{60,195}});
        COLOR_RANGES.put("red",    new int[][]{{150,255},{0,80},{0,80}});
        COLOR_RANGES.put("blue",   new int[][]{{0,80},{0,100},{130,255}});
        COLOR_RANGES.put("navy",   new int[][]{{0,50},{0,60},{80,160}});
        COLOR_RANGES.put("green",  new int[][]{{0,100},{100,220},{0,100}});
        COLOR_RANGES.put("yellow", new int[][]{{180,255},{180,255},{0,80}});
        COLOR_RANGES.put("orange", new int[][]{{180,255},{80,180},{0,60}});
        COLOR_RANGES.put("pink",   new int[][]{{200,255},{100,200},{150,255}});
        COLOR_RANGES.put("purple", new int[][]{{80,180},{0,80},{130,220}});
        COLOR_RANGES.put("brown",  new int[][]{{100,180},{50,110},{0,70}});
        COLOR_RANGES.put("tan",    new int[][]{{170,220},{130,180},{80,140}});
        COLOR_RANGES.put("beige",  new int[][]{{180,240},{160,220},{120,190}});
        COLOR_RANGES.put("camel",  new int[][]{{180,230},{140,190},{80,140}});
        COLOR_RANGES.put("olive",  new int[][]{{80,140},{100,160},{0,80}});
        COLOR_RANGES.put("khaki",  new int[][]{{150,210},{150,200},{80,150}});
        COLOR_RANGES.put("teal",   new int[][]{{0,80},{130,200},{140,220}});
        COLOR_RANGES.put("burgundy", new int[][]{{100,180},{0,50},{0,60}});
        COLOR_RANGES.put("maroon",   new int[][]{{100,180},{0,50},{0,60}});
    }

    private static final int MIN_DIMENSION_PX = 150;
    private static final int WARDROBE_QUALITY_THRESHOLD = 500;
    private static final int MAX_WEB_CANDIDATES = 4;

    @Value("${vastra.serpapi.api-key:}")
    private String serpApiKey;

    @Value("${vastra.openai.api-key:}")
    private String openAiKey;

    @Value("${vastra.openai.image-model:gpt-image-2}")
    private String openAiImageModel;

    @Value("${vastra.cv-service.base-url:http://localhost:8001}")
    private String cvServiceUrl;

    @Value("${vastra.webmatch.visual-similarity-threshold:0.75}")
    private double visualSimilarityThreshold;

    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final R2Service r2Service;

    public ImageEnhancementService(R2Service r2Service) {
        this.r2Service = r2Service;
    }

    public boolean isWebMatchAvailable() { return !serpApiKey.isBlank(); }
    public boolean isAiRenderAvailable() { return !openAiKey.isBlank(); }

    /**
     * Run SerpAPI Google Lens on the crop with a text context hint (subtype +
     * brand + dominant color) to steer results, then apply domain-tier scoring,
     * color-mismatch detection, and type-strict filtering.
     *
     * Only wardrobe-quality candidates (same domain tier ≥ 500, correct type,
     * no conflicting color in title) are returned. The list is never padded:
     * if only one Fanatics result qualifies, exactly one is returned.
     * If nothing clears the bar, returns empty → client shows "No clean product
     * match found. Generate a clean wardrobe image instead."
     */
    public List<WebMatchCandidate> searchWebMatches(
            String cropPresignedUrl, String category, String subCategory,
            List<String> colorPalette, String brand) {
        return searchWebMatches(cropPresignedUrl, category, subCategory, colorPalette, brand, null);
    }

    /**
     * As above, with an optional {@code referenceEmbedding} (the original item
     * crop's FashionCLIP vector). When provided, each surviving candidate's
     * product image is embedded via the CV service and compared by cosine
     * similarity; candidates below {@code visualSimilarityThreshold} are dropped
     * because they are the same type/color but a visually different garment.
     * When the reference embedding is null (FashionCLIP was unavailable at scan
     * time), visual validation is skipped — we never fabricate a similarity.
     */
    public List<WebMatchCandidate> searchWebMatches(
            String cropPresignedUrl, String category, String subCategory,
            List<String> colorPalette, String brand, float[] referenceEmbedding) {

        if (serpApiKey.isBlank()) return List.of();

        try {
            // Text hint: confirmed subtype + brand + dominant color name.
            // Google Lens accepts a `q` text context that steers ranking.
            String textHint = buildTextHint(subCategory, brand, colorPalette);
            String subLower  = subCategory == null ? "" : subCategory.toLowerCase().trim();
            List<String> brandTokens = brandTokens(brand);
            List<String> detectedColorNames = colorNamesFromPalette(colorPalette);

            String url = "https://serpapi.com/search.json?engine=google_lens"
                + "&url=" + java.net.URLEncoder.encode(cropPresignedUrl, java.nio.charset.StandardCharsets.UTF_8)
                + "&api_key=" + serpApiKey
                + "&hl=en"
                + (textHint.isBlank() ? "" : "&q=" + java.net.URLEncoder.encode(textHint, java.nio.charset.StandardCharsets.UTF_8));

            String body = restClient.get().uri(url).retrieve().body(String.class);
            JsonNode root = objectMapper.readTree(body);

            List<ScoredCandidate> pool = new ArrayList<>();

            // Products array (shopping context — highest quality)
            JsonNode products = root.path("lens_results").path("products");
            if (!products.isArray()) products = root.path("products");
            if (products.isArray()) {
                for (JsonNode p : products) {
                    String title    = p.path("title").asText("");
                    String imageUrl = p.path("thumbnail").asText("");
                    if (imageUrl.isBlank()) imageUrl = p.path("image").asText("");
                    String sourceUrl = p.path("link").asText("");
                    if (sourceUrl.isBlank()) sourceUrl = p.path("product_link").asText("");
                    String siteName = p.path("source").asText("");
                    if (!imageUrl.isBlank() && !sourceUrl.isBlank()) {
                        int score = scoreCandidate(sourceUrl, title, subLower, brandTokens,
                                p.path("original_dimensions"), true, detectedColorNames);
                        if (score > Integer.MIN_VALUE) {
                            String warn = combinedWarning(title, detectedColorNames);
                            if (warn != null) score -= 200;  // demote below clean candidates
                            pool.add(new ScoredCandidate(title, imageUrl, sourceUrl,
                                    siteName.isBlank() ? null : siteName, score, warn));
                        }
                    }
                }
            }

            // Visual matches array (general Lens results)
            JsonNode visual = root.path("visual_matches");
            if (visual.isArray()) {
                for (JsonNode m : visual) {
                    String title    = m.path("title").asText("");
                    String imageUrl = m.path("thumbnail").asText("");
                    String sourceUrl = m.path("link").asText("");
                    String siteName = m.path("source").asText("");
                    if (!imageUrl.isBlank() && !sourceUrl.isBlank()) {
                        int score = scoreCandidate(sourceUrl, title, subLower, brandTokens,
                                m.path("original_dimensions"), false, detectedColorNames);
                        if (score > Integer.MIN_VALUE) {
                            String warn = combinedWarning(title, detectedColorNames);
                            if (warn != null) score -= 200;  // demote below clean candidates
                            pool.add(new ScoredCandidate(title, imageUrl, sourceUrl,
                                    siteName.isBlank() ? null : siteName, score, warn));
                        }
                    }
                }
            }

            // Sort: clean front-view, correct-color candidates first (no warning),
            // then by score. A warned candidate never outranks a clean one.
            pool.sort(Comparator
                .<ScoredCandidate, Boolean>comparing(c -> c.colorWarning() != null)
                .thenComparingInt(ScoredCandidate::score).reversed());

            boolean visualGateActive = referenceEmbedding != null && referenceEmbedding.length > 0;
            int visualRejects = 0;

            List<WebMatchCandidate> results = new ArrayList<>();
            Set<String> seenDomains = new HashSet<>();
            for (ScoredCandidate sc : pool) {
                if (results.size() >= MAX_WEB_CANDIDATES) break;
                if (sc.score() < WARDROBE_QUALITY_THRESHOLD) break;
                String domain = rootDomain(sc.sourceUrl());
                if (!seenDomains.add(domain)) continue;

                // ── Visual similarity gate ────────────────────────────────────
                // Same type + same color is not enough: the silhouette/details must
                // match. Embed the candidate product image via the CV service and
                // compare to the original crop embedding. Drop candidates that are
                // a visually different garment (random black jacket vs MY jacket).
                if (visualGateActive) {
                    float[] candEmb = embedImageUrl(sc.imageUrl());
                    if (candEmb != null) {
                        double sim = cosineSimilarity(referenceEmbedding, candEmb);
                        if (sim < visualSimilarityThreshold) {
                            visualRejects++;
                            log.debug("web-match: visual reject sim={} < {} title='{}'",
                                    String.format("%.3f", sim), visualSimilarityThreshold, sc.title());
                            continue;
                        }
                        log.debug("web-match: visual pass sim={} title='{}'",
                                String.format("%.3f", sim), sc.title());
                    }
                    // candEmb == null → CV couldn't embed it; fall through (don't fabricate).
                }

                // Strengthen the color check on the shortlist by inspecting the actual
                // thumbnail pixels (title words alone miss logo/base-color swaps).
                String warning = sc.colorWarning();
                if (warning == null && !detectedColorNames.isEmpty()) {
                    warning = thumbnailColorWarning(sc.imageUrl(), detectedColorNames);
                }
                results.add(new WebMatchCandidate(
                    sc.title(), sc.imageUrl(), sc.sourceUrl(), sc.siteName(), warning));
            }

            // Re-sort the final shortlist so any candidate that picked up a
            // thumbnail color/back-view warning drops below clean ones.
            results.sort(Comparator.comparing(c -> c.colorWarning() != null));

            if (results.isEmpty()) {
                log.info("web-match: no candidates passed for {}/{} (pool={}, visualGate={}, visualRejects={}, textHint='{}' — suggest AI render)",
                        category, subCategory, pool.size(), visualGateActive, visualRejects, textHint);
                return List.of();
            }

            log.info("web-match: {} candidates for {}/{} (pool={}, visualGate={}, visualRejects={}, textHint='{}')",
                    results.size(), category, subCategory, pool.size(), visualGateActive, visualRejects, textHint);
            return results;

        } catch (Exception e) {
            log.warn("web-match search failed ({}/{}): {}", category, subCategory, e.getMessage());
            return List.of();
        }
    }

    /**
     * Score a single candidate. Returns Integer.MIN_VALUE for hard rejection:
     *   - blocked domain (resale/social/aggregator)
     *   - thumbnail below MIN_DIMENSION_PX
     *   - unknown domain (not in preferred, acceptable, or brand-owned tiers)
     *   - title type conflicts with confirmed subtype (e.g. "zip-up" for "hoodie",
     *     "tracksuit" for "sweatshirt")
     *
     * Eligible scores: 1000 (brand-owned) / 900 (preferred) / 500 (acceptable)
     * + bonuses: +50 from products array, +15 subtype in title, +10 brand in title.
     */
    private int scoreCandidate(String sourceUrl, String title,
                               String subLower, List<String> brandTokens,
                               JsonNode dims, boolean fromProductsArray,
                               List<String> detectedColorNames) {
        String host = rootDomain(sourceUrl);

        if (isBlocked(host)) {
            log.debug("web-match: blocked {}", host);
            return Integer.MIN_VALUE;
        }

        if (dims != null && !dims.isMissingNode()) {
            int w = dims.path("width").asInt(0);
            int h = dims.path("height").asInt(0);
            if ((w > 0 && w < MIN_DIMENSION_PX) || (h > 0 && h < MIN_DIMENSION_PX)) {
                log.debug("web-match: thumbnail too small ({}x{}) — {}", w, h, host);
                return Integer.MIN_VALUE;
            }
        }

        boolean brandSite = isBrandOwnedDomain(host, brandTokens);
        int base;
        if (brandSite)             base = 1000;
        else if (isPreferred(host)) base = 900;
        else if (isAcceptable(host)) base = 500;
        else {
            log.debug("web-match: unknown domain {} — rejected", host);
            return Integer.MIN_VALUE;
        }

        // Type conflict: title clearly indicates a different garment type → hard-reject.
        if (!subLower.isBlank() && titleConflictsWithSubtype(title.toLowerCase(), subLower)) {
            log.debug("web-match: type conflict for subtype='{}' title='{}' — rejected", subLower, title);
            return Integer.MIN_VALUE;
        }

        // Hard color reject: title names a color that is completely absent from the
        // detected palette. Prevents a "Blue Jacket" from appearing for a tan/brown jacket.
        // Only fires when we have palette data (never blocks items with no palette).
        if (!detectedColorNames.isEmpty()
                && titleColorConflictsHard(title.toLowerCase(), detectedColorNames)) {
            log.debug("web-match: hard color conflict for palette={} title='{}' — rejected",
                    detectedColorNames, title);
            return Integer.MIN_VALUE;
        }

        int score = base;
        if (fromProductsArray) score += 50;
        String titleLower = title.toLowerCase();
        if (!subLower.isBlank() && titleLower.contains(subLower)) score += 15;
        for (String t : brandTokens) {
            if (t.length() >= 3 && titleLower.contains(t)) { score += 10; break; }
        }
        return score;
    }

    /**
     * Returns true when the title contains terms that contradict the confirmed
     * subtype — e.g. "zip-up" or "full-zip" for a plain "hoodie", or "tracksuit"
     * for a "sweatshirt". Prevents wrong-type candidates appearing in results.
     */
    /**
     * Returns true when the title contains terms that contradict the confirmed
     * subtype. This is a hard-reject: the candidate will never be shown even if
     * the domain score is high. Keeps the user from seeing a vest when they
     * confirmed "jacket", or a blue jacket for a brown jacket.
     *
     * Rule additions vs the original:
     *   - jacket: reject vest/gilet/bodywarmer/windbreaker/fleece/tracksuit/hoodie/sweatshirt
     *   - coat:   reject puffer that isn't a coat, jacket, hoodie, sweatshirt
     *   - vest:   reject jacket/coat/hoodie
     *   - trousers/chinos: reject shorts/skirt/jogger/sweatpant
     *   - shorts:  reject trousers/joggers
     *   - blazer:  reject hoodie/sweatshirt/tracksuit
     *   - sneakers/shoes: reject slippers/socks
     */
    private boolean titleConflictsWithSubtype(String titleLower, String subLower) {
        switch (subLower) {

            case "jacket": {
                // Must look like outerwear, not knitwear/trackwear/vests
                if (titleLower.contains("vest") || titleLower.contains("gilet")
                        || titleLower.contains("bodywarmer") || titleLower.contains("body warmer")
                        || titleLower.contains("fleece") && !titleLower.contains("fleece jacket")
                        || titleLower.contains("tracksuit") || titleLower.contains("track suit")
                        || titleLower.contains("hoodie") || titleLower.contains("sweatshirt")
                        || titleLower.contains("sweater") || titleLower.contains("jumper")
                        || titleLower.contains("knitwear") || titleLower.contains("knit")
                        || titleLower.contains("cardigan")
                        ) return true;
                break;
            }

            case "coat": {
                if (titleLower.contains("jacket") && !titleLower.contains("coat")
                        || titleLower.contains("hoodie") || titleLower.contains("sweatshirt")
                        || titleLower.contains("vest") || titleLower.contains("gilet")
                        ) return true;
                break;
            }

            case "blazer": {
                if (titleLower.contains("hoodie") || titleLower.contains("sweatshirt")
                        || titleLower.contains("tracksuit") || titleLower.contains("track suit")
                        || titleLower.contains("vest") || titleLower.contains("jersey")
                        ) return true;
                break;
            }

            case "vest": {
                // A vest candidate must not be a full jacket or coat
                if (titleLower.contains("jacket") || titleLower.contains("coat")
                        || titleLower.contains("hoodie") || titleLower.contains("sweatshirt")
                        ) return true;
                break;
            }

            case "hoodie": {
                if (titleLower.contains("full-zip") || titleLower.contains("half-zip")
                        || (titleLower.contains("zip") && !titleLower.contains("hoodie"))) return true;
                if (titleLower.contains("tracksuit") || titleLower.contains("track suit")) return true;
                if (titleLower.contains("jacket") && !titleLower.contains("hoodie")) return true;
                break;
            }

            case "zip-up hoodie": {
                if (titleLower.contains("sweatshirt") && !titleLower.contains("zip")) return true;
                if (titleLower.contains("jacket") && !titleLower.contains("hoodie")) return true;
                break;
            }

            case "sweatshirt":
            case "crewneck": {
                if (titleLower.contains("hooded") || titleLower.contains("hoodie")) return true;
                if (titleLower.contains("zip-up") || titleLower.contains("full-zip")) return true;
                if (titleLower.contains("jacket") && !titleLower.contains("sweatshirt")) return true;
                break;
            }

            case "sweater": {
                if (titleLower.contains("sweatshirt") || titleLower.contains("hoodie")) return true;
                if (titleLower.contains("jacket") && !titleLower.contains("sweater")) return true;
                break;
            }

            case "trousers":
            case "chinos": {
                if (titleLower.contains("shorts") || titleLower.contains("skirt")
                        || titleLower.contains("jogger") || titleLower.contains("sweatpant")
                        || titleLower.contains("legging")) return true;
                break;
            }

            case "shorts": {
                if (titleLower.contains("trouser") || titleLower.contains("chino")
                        || titleLower.contains("jogger") || titleLower.contains("sweatpant")
                        || titleLower.contains("legging")) return true;
                break;
            }

            case "joggers":
            case "sweatpants": {
                if (titleLower.contains("jean") || titleLower.contains("trouser")
                        || titleLower.contains("chino") || titleLower.contains("shorts")
                        || titleLower.contains("legging")) return true;
                break;
            }

            case "sneakers":
            case "running shoes": {
                if (titleLower.contains("slipper") || titleLower.contains("sandal")
                        || titleLower.contains("heel") || titleLower.contains("boot")
                        || titleLower.contains("loafer") || titleLower.contains("sock")) return true;
                break;
            }

            case "boots": {
                if (titleLower.contains("sneaker") || titleLower.contains("trainer")
                        || titleLower.contains("slipper") || titleLower.contains("sandal")
                        || titleLower.contains("loafer")) return true;
                break;
            }
        }
        return false;
    }

    /**
     * Combined display warning for a candidate, or null when it looks clean.
     * Checks (in priority order):
     *   1. Back/partial view in the title — not useful as a wardrobe front image.
     *   2. Title names a color that conflicts with the detected palette.
     */
    /**
     * Hard color reject: returns true when the title explicitly names a specific
     * color that is entirely absent from the detected palette AND is not adjacent
     * to any detected color (e.g. navy ≈ blue). Prevents blue jackets from
     * appearing for a tan/brown item, and avoids drowning good results in demotion
     * score math when the conflict is clear-cut.
     *
     * Only fires for colors that appear verbatim in the title (not inferred).
     * Does NOT fire when the detected palette is empty (no data → no hard reject).
     *
     * Colors excluded from hard-reject: "white", "black", "grey"/"gray" — these
     * appear in product titles as accent/contrast elements even for multi-color
     * items (e.g. "Black/Brown Bomber Jacket"), so we soft-warn instead.
     */
    private boolean titleColorConflictsHard(String titleLower, List<String> detectedColorNames) {
        // Hard-reject colors: primary/saturated hues only; neutrals handled by soft warn.
        Set<String> hardColors = Set.of("blue", "red", "green", "yellow", "orange",
                "purple", "pink", "navy", "teal", "burgundy", "maroon", "olive",
                "brown", "tan", "beige", "cream", "khaki", "camel");
        for (String color : hardColors) {
            if (!titleLower.contains(color)) continue;
            // Check that the detected palette has this color or an adjacent one.
            boolean present = detectedColorNames.stream()
                    .anyMatch(d -> d.equals(color) || relatedColors(d, color));
            if (!present) return true;
        }
        return false;
    }

    private String combinedWarning(String title, List<String> detectedColorNames) {
        String backWarn = backOrPartialViewWarning(title);
        if (backWarn != null) return backWarn;
        return colorWarning(title, detectedColorNames);
    }

    /**
     * Returns a warning when the title indicates a back or partial view, which
     * hides the front graphic/logo/color placement needed for wardrobe matching.
     */
    private String backOrPartialViewWarning(String title) {
        String t = title.toLowerCase();
        if (t.contains("back view") || t.contains("rear view") || t.contains("back of ")
            || t.contains("reverse view") || t.contains("(back)") || t.contains(" back print")) {
            return "Back/partial view — verify before using (front graphics may be missing).";
        }
        if (t.contains("close-up") || t.contains("closeup") || t.contains("detail view")
            || t.contains("cropped")) {
            return "Partial/detail view — verify the full item is shown before using.";
        }
        return null;
    }

    /**
     * Returns a color-mismatch warning string when the title clearly mentions a
     * color that conflicts with the item's detected dominant color, or null when
     * colors are consistent or unknown.
     */
    private String colorWarning(String title, List<String> detectedColorNames) {
        if (detectedColorNames.isEmpty()) return null;
        String titleLower = title.toLowerCase();
        for (Map.Entry<String, int[][]> e : COLOR_RANGES.entrySet()) {
            String colorName = e.getKey();
            if (titleLower.contains(colorName)) {
                boolean matchesDetected = detectedColorNames.stream()
                    .anyMatch(d -> d.equals(colorName) || relatedColors(d, colorName));
                if (!matchesDetected) {
                    return "Note: this image shows a " + colorName + " version — verify it matches your item.";
                }
            }
        }
        return null;
    }

    /**
     * Best-effort thumbnail color check: download the candidate thumbnail, compute
     * its dominant color name, and warn if it isn't present in the detected
     * palette. Catches base-color / logo-color swaps that the title never names.
     * Bounded to the small shortlist (≤ MAX_WEB_CANDIDATES) and fully guarded —
     * any failure returns null (no warning) rather than blocking the candidate.
     */
    private String thumbnailColorWarning(String imageUrl, List<String> detectedColorNames) {
        try {
            byte[] bytes = restClient.get().uri(imageUrl).retrieve().body(byte[].class);
            if (bytes == null || bytes.length == 0) return null;
            java.awt.image.BufferedImage img =
                javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (img == null) return null;

            // Sample the central 60% region (avoids white studio borders) and average.
            int w = img.getWidth(), h = img.getHeight();
            int x0 = (int) (w * 0.2), x1 = (int) (w * 0.8);
            int y0 = (int) (h * 0.2), y1 = (int) (h * 0.8);
            long rs = 0, gs = 0, bs = 0; int n = 0;
            int step = Math.max(1, (x1 - x0) / 40);  // subsample for speed
            for (int y = y0; y < y1; y += step) {
                for (int x = x0; x < x1; x += step) {
                    int rgb = img.getRGB(x, y);
                    rs += (rgb >> 16) & 0xFF; gs += (rgb >> 8) & 0xFF; bs += rgb & 0xFF; n++;
                }
            }
            if (n == 0) return null;
            String hex = String.format("#%02X%02X%02X", rs / n, gs / n, bs / n);
            String thumbColor = hexToColorName(hex);
            if (thumbColor == null) return null;  // can't classify → don't warn

            boolean matches = detectedColorNames.stream()
                .anyMatch(d -> d.equals(thumbColor) || relatedColors(d, thumbColor));
            if (!matches) {
                log.debug("web-match: thumbnail color {} not in detected {} — warning", thumbColor, detectedColorNames);
                return "This image looks " + thumbColor + ", which differs from your item's color — verify before using.";
            }
        } catch (Exception e) {
            log.debug("web-match: thumbnail color check skipped ({})", e.getMessage());
        }
        return null;
    }

    /**
     * Embed a candidate product image via the CV service's /embed/url endpoint.
     * Returns the normalized FashionCLIP vector, or null when the CV service is
     * unreachable, FashionCLIP is not loaded, or the image can't be fetched —
     * in which case visual validation is skipped for that candidate.
     */
    private float[] embedImageUrl(String imageUrl) {
        try {
            String reqBody = objectMapper.writeValueAsString(Map.of("image_url", imageUrl));
            String body = restClient.post()
                .uri(cvServiceUrl + "/embed/url")
                .header("Content-Type", "application/json")
                .body(reqBody)
                .retrieve()
                .body(String.class);
            JsonNode root = objectMapper.readTree(body);
            JsonNode emb = root.path("embedding");
            if (!emb.isArray() || emb.isEmpty()) return null;
            float[] vec = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) vec[i] = (float) emb.get(i).asDouble();
            return vec;
        } catch (Exception e) {
            log.debug("web-match: candidate embed failed ({}) — skipping visual gate for {}",
                    e.getMessage(), imageUrl);
            return null;
        }
    }

    /** Cosine similarity between two equal-length vectors; 0 when shapes differ or norm is 0. */
    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0.0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na  += (double) a[i] * a[i];
            nb  += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** Some colors are close enough that a mismatch warning is unnecessary. */
    private boolean relatedColors(String a, String b) {
        if (a.equals(b)) return true;
        // Grey/gray are the same word spelled differently.
        if (isGrey(a) && isGrey(b)) return true;
        // Navy and blue are close enough that a navy product is acceptable for a blue item.
        if ((isNavy(a) && isBlue(b)) || (isBlue(a) && isNavy(b))) return true;
        // Earth tones: tan, brown, beige, camel, khaki, cream are interchangeable enough
        // that a "beige" title is not a hard-conflict for a "tan" detected item.
        if (isEarthTone(a) && isEarthTone(b)) return true;
        // Olive and khaki overlap in practice.
        if (isOliveKhaki(a) && isOliveKhaki(b)) return true;
        return false;
    }

    private static boolean isGrey(String c)        { return "grey".equals(c) || "gray".equals(c); }
    private static boolean isNavy(String c)        { return "navy".equals(c); }
    private static boolean isBlue(String c)        { return "blue".equals(c); }
    private static boolean isEarthTone(String c)   {
        return Set.of("tan", "brown", "beige", "camel", "cream", "khaki", "sand").contains(c);
    }
    private static boolean isOliveKhaki(String c)  { return "olive".equals(c) || "khaki".equals(c); }

    /**
     * Build a short text hint for the SerpAPI `q` parameter.
     * The hint steers Google Lens ranking; it is NOT a filter (filtering is our job).
     * Include up to two dominant colors so the Lens shopping API returns
     * color-relevant products ahead of generic catalog images.
     */
    private String buildTextHint(String subCategory, String brand, List<String> colorPalette) {
        List<String> parts = new ArrayList<>();
        if (brand != null && !brand.isBlank()) parts.add(brand.trim());
        if (subCategory != null && !subCategory.isBlank()) parts.add(subCategory.trim());
        List<String> colorNames = colorNamesFromPalette(colorPalette);
        // Include up to 2 detected colors so Lens ranks color-matched products higher.
        colorNames.stream().limit(2).forEach(parts::add);
        return String.join(" ", parts);
    }

    /**
     * Convert hex color palette to human color names using the COLOR_RANGES table.
     * Returns names for the top colors (palette is already ordered by dominance).
     */
    private List<String> colorNamesFromPalette(List<String> hexPalette) {
        List<String> names = new ArrayList<>();
        for (String hex : hexPalette) {
            String name = hexToColorName(hex);
            if (name != null && !names.contains(name)) names.add(name);
            if (names.size() >= 3) break;
        }
        return names;
    }

    private String hexToColorName(String hex) {
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            int r = Integer.parseInt(h.substring(0, 2), 16);
            int g = Integer.parseInt(h.substring(2, 4), 16);
            int b = Integer.parseInt(h.substring(4, 6), 16);
            for (Map.Entry<String, int[][]> e : COLOR_RANGES.entrySet()) {
                int[][] ranges = e.getValue();
                if (r >= ranges[0][0] && r <= ranges[0][1]
                 && g >= ranges[1][0] && g <= ranges[1][1]
                 && b >= ranges[2][0] && b <= ranges[2][1]) {
                    return e.getKey();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean isBrandOwnedDomain(String host, List<String> brandTokens) {
        int dot = host.indexOf('.');
        String label = dot > 0 ? host.substring(0, dot) : host;
        for (String t : brandTokens) {
            if (t.length() >= 3 && label.contains(t)) return true;
        }
        return false;
    }

    private static List<String> brandTokens(String brand) {
        if (brand == null || brand.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        for (String raw : brand.toLowerCase().split("[^a-z0-9]+")) {
            if (raw.length() >= 3) tokens.add(raw);
        }
        return tokens;
    }

    private static String rootDomain(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return url.toLowerCase();
            host = host.toLowerCase();
            if (host.startsWith("www.")) host = host.substring(4);
            return host;
        } catch (Exception e) {
            return url.toLowerCase();
        }
    }

    private boolean isBlocked(String host)    { return BLOCKED_DOMAINS.stream().anyMatch(host::endsWith); }
    private boolean isPreferred(String host)  { return PREFERRED_DOMAINS.stream().anyMatch(host::endsWith); }
    private boolean isAcceptable(String host) { return ACCEPTABLE_DOMAINS.stream().anyMatch(host::endsWith); }

    private record ScoredCandidate(
        String title, String imageUrl, String sourceUrl,
        String siteName, int score, String colorWarning) {}

    // ── AI Render ─────────────────────────────────────────────────────────────

    /**
     * Generate a clean fashion product image grounded in the crop.
     * Returns the R2 key on success, or null on failure.
     * Logs a specific failure reason (model rejection, quota, bad image, etc.).
     */
    public String generateAiRender(
            String cropPresignedUrl, String category, String subCategory,
            List<String> colorPalette, String brand) {
        return generateAiRenderWithReason(cropPresignedUrl, category, subCategory, colorPalette, brand)[0];
    }

    /**
     * Like generateAiRender() but returns [key_or_null, failureReason_or_null].
     */
    public String[] generateAiRenderWithReason(
            String cropPresignedUrl, String category, String subCategory,
            List<String> colorPalette, String brand) {

        if (openAiKey.isBlank()) return new String[]{null, null};

        String colorDesc = colorPalette.isEmpty() ? ""
            : " in " + String.join(", ", colorPalette.subList(0, Math.min(2, colorPalette.size())));
        String brandDesc = (brand != null && !brand.isBlank()) ? brand + " " : "";
        String garment   = (subCategory == null || subCategory.isBlank())
            ? category.toLowerCase() : subCategory;
        String prompt = String.format(
            "Turn this into a realistic fashion e-commerce product photograph of the same " +
            "%s%s%s shown in the reference image. Keep the exact color, type, silhouette and " +
            "any visible logo or graphic faithful to the reference. Present the garment upright, " +
            "centered, isolated on a clean opaque neutral background. Remove any person, body " +
            "parts, hands, arms, phone, and background scene. Professional product shot, " +
            "Zara / H&M / ASOS online store style. High quality.",
            brandDesc, garment, colorDesc);

        try {
            byte[] cropBytes = restClient.get()
                .uri(cropPresignedUrl)
                .retrieve()
                .body(byte[].class);
            if (cropBytes == null || cropBytes.length == 0) {
                String reason = "Crop image could not be downloaded (URL may have expired).";
                log.warn("ai-render: {}", reason);
                return new String[]{null, reason};
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
            log.info("ai-render (model={}): {} bytes → R2 key={}", openAiImageModel, imgBytes.length, r2Key);
            return new String[]{r2Key, null};

        } catch (HttpClientErrorException e) {
            String reason = classifyOpenAiClientError(e);
            log.error("ai-render OpenAI client error [{}/{}]: {} — {}", category, subCategory, e.getStatusCode(), reason);
            return new String[]{null, reason};
        } catch (HttpServerErrorException e) {
            String reason = "OpenAI server error (" + e.getStatusCode() + "). Try again in a moment.";
            log.error("ai-render OpenAI server error [{}/{}]: {}", category, subCategory, e.getMessage());
            return new String[]{null, reason};
        } catch (Exception e) {
            String reason = classifyGenericError(e);
            log.error("ai-render failed [{}/{}]: {}", category, subCategory, e.getMessage());
            return new String[]{null, reason};
        }
    }

    private String classifyOpenAiClientError(HttpClientErrorException e) {
        int code = e.getStatusCode().value();
        String body = e.getResponseBodyAsString();
        if (code == 401) return "OpenAI authentication failed. Check OPENAI_API_KEY.";
        if (code == 429) return "OpenAI rate limit or billing quota reached. Please try again later.";
        if (code == 400) {
            if (body.contains("invalid_image") || body.contains("could not process image"))
                return "The crop image could not be processed by OpenAI (invalid format or too small).";
            if (body.contains("content_policy") || body.contains("safety"))
                return "Image generation was declined by OpenAI's content policy for this item.";
            return "OpenAI rejected the request: " + summarizeBody(body);
        }
        return "OpenAI error " + code + ": " + summarizeBody(body);
    }

    private String classifyGenericError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return "Unknown error generating clean image.";
        if (msg.contains("timeout") || msg.contains("SocketTimeout"))
            return "Request timed out generating clean image. Try again.";
        if (msg.contains("Connection refused") || msg.contains("UnknownHost"))
            return "Could not reach OpenAI (network error). Check server connectivity.";
        if (msg.contains("R2") || msg.contains("upload"))
            return "Clean image was generated but could not be saved to storage.";
        return "Failed to generate clean image: " + msg.substring(0, Math.min(msg.length(), 80));
    }

    private String summarizeBody(String body) {
        if (body == null || body.isBlank()) return "(no detail)";
        try {
            JsonNode n = objectMapper.readTree(body);
            String msg = n.path("error").path("message").asText("");
            return msg.isBlank() ? body.substring(0, Math.min(body.length(), 80)) : msg;
        } catch (Exception ignored) {
            return body.substring(0, Math.min(body.length(), 80));
        }
    }

    /**
     * Download an external image URL and store it in R2.
     * Used when confirming a web-match candidate.
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
