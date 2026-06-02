package com.vastra.domain;

import com.vastra.entity.ClothingCategory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Normalized clothing-subtype vocabulary and styling metadata.
 *
 * <p>This is the single source of truth for the controlled subtype names a
 * confirmed wardrobe item should use, the broad {@link ClothingCategory} each
 * maps to, and a coarse formality score used by future outfit/recommendation
 * logic. It is intentionally ADDITIVE and not yet enforced: the confirm flow
 * still accepts free-text {@code subCategory} so no existing item is rejected.
 * Wire it into the review-UI suggestions and the recommender when ready.
 *
 * <p>Why subtype matters beyond category: {@code trousers} and {@code joggers}
 * are both {@code BOTTOM} but are not interchangeable for styling — tailored
 * trousers suit smart-casual/semi-formal looks, while joggers are casual /
 * lounge / gym. Matching on color alone would produce bad suggestions, so the
 * recommender must consider subtype + formality (and later material + fit).
 */
public final class SubtypeVocabulary {

    private SubtypeVocabulary() {}

    /**
     * Formality on a 1–5 scale used to gate occasion-appropriate matching:
     * <ul>
     *   <li>1 = athletic / lounge (joggers, sweatpants, leggings)</li>
     *   <li>2 = very casual (shorts, t-shirt)</li>
     *   <li>3 = casual / smart-casual (jeans, chinos, polo)</li>
     *   <li>4 = smart-casual / business-casual (trousers, blouse, blazer)</li>
     *   <li>5 = formal (suit, dress shirt, gown)</li>
     * </ul>
     */
    public record Subtype(String name, ClothingCategory category, int formality) {}

    // ── BOTTOMS (the vocabulary requested) ────────────────────────────────────
    public static final List<Subtype> BOTTOMS = List.of(
            new Subtype("jeans",       ClothingCategory.BOTTOM, 3),
            new Subtype("trousers",    ClothingCategory.BOTTOM, 4),
            new Subtype("chinos",      ClothingCategory.BOTTOM, 3),
            new Subtype("joggers",     ClothingCategory.BOTTOM, 1),
            new Subtype("sweatpants",  ClothingCategory.BOTTOM, 1),
            new Subtype("cargo pants", ClothingCategory.BOTTOM, 2),
            new Subtype("shorts",      ClothingCategory.BOTTOM, 2),
            new Subtype("skirt",       ClothingCategory.BOTTOM, 3),
            new Subtype("leggings",    ClothingCategory.BOTTOM, 1)
    );

    // ── Other categories (seed values; extend as worn-outfit testing matures) ─
    public static final List<Subtype> TOPS = List.of(
            new Subtype("t-shirt",   ClothingCategory.TOP, 2),
            new Subtype("shirt",     ClothingCategory.TOP, 4),
            new Subtype("polo shirt", ClothingCategory.TOP, 3),
            new Subtype("blouse",    ClothingCategory.TOP, 4),
            new Subtype("tank top",  ClothingCategory.TOP, 2),
            new Subtype("sweater",   ClothingCategory.TOP, 3),
            new Subtype("hoodie",    ClothingCategory.TOP, 1)
    );

    public static final List<Subtype> OUTERWEAR = List.of(
            new Subtype("jacket", ClothingCategory.OUTERWEAR, 3),
            new Subtype("coat",   ClothingCategory.OUTERWEAR, 4),
            new Subtype("blazer", ClothingCategory.OUTERWEAR, 5)
    );

    public static final List<Subtype> FOOTWEAR = List.of(
            new Subtype("sneakers", ClothingCategory.FOOTWEAR, 2),
            new Subtype("shoes",    ClothingCategory.FOOTWEAR, 4),
            new Subtype("boots",    ClothingCategory.FOOTWEAR, 3),
            new Subtype("sandals",  ClothingCategory.FOOTWEAR, 2),
            new Subtype("clogs",    ClothingCategory.FOOTWEAR, 1)
    );

    /** All known subtypes across categories, keyed by normalized lowercase name. */
    public static final Map<String, Subtype> ALL;
    static {
        var builder = new java.util.HashMap<String, Subtype>();
        for (var group : List.of(BOTTOMS, TOPS, OUTERWEAR, FOOTWEAR)) {
            for (var s : group) builder.put(s.name().toLowerCase(), s);
        }
        ALL = Map.copyOf(builder);
    }

    /** Suggested subtype options for a given category, for the review-UI picker. */
    public static List<String> suggestionsFor(ClothingCategory category) {
        return ALL.values().stream()
                .filter(s -> s.category() == category)
                .map(Subtype::name)
                .sorted()
                .toList();
    }

    /** Look up styling metadata for a (possibly user-edited) subtype name. */
    public static Optional<Subtype> lookup(String subtypeName) {
        if (subtypeName == null) return Optional.empty();
        return Optional.ofNullable(ALL.get(subtypeName.trim().toLowerCase()));
    }

    /**
     * Formality for a subtype name, or a neutral 3 when unknown. Future outfit
     * matching should avoid mixing items whose formality differs by more than
     * one step (e.g. olive joggers=1 must not be matched into a dinner look
     * that expects olive trousers=4).
     */
    public static int formalityOf(String subtypeName) {
        return lookup(subtypeName).map(Subtype::formality).orElse(3);
    }
}
