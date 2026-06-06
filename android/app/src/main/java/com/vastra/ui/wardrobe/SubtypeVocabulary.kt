package com.vastra.ui.wardrobe

import com.vastra.data.model.ClothingCategory

/**
 * Normalized subtype suggestions for the review-UI Type field, mirroring the
 * backend com.vastra.domain.SubtypeVocabulary. These are SUGGESTIONS only — the
 * Type field stays free-text so the user can enter a custom value if the right
 * option is not listed. Whatever the user confirms is the saved truth.
 */
object SubtypeVocabulary {

    private val byCategory: Map<ClothingCategory, List<String>> = mapOf(
        ClothingCategory.BOTTOM to listOf(
            "Jeans", "Trousers", "Chinos", "Joggers", "Sweatpants",
            "Cargo Pants", "Shorts", "Skirt", "Leggings"
        ),
        ClothingCategory.TOP to listOf(
            "T-Shirt", "Shirt", "Button-up Shirt", "Polo Shirt", "Blouse",
            "Tank Top", "Long Sleeve", "Jersey",
            "Sweater", "Sweatshirt", "Crewneck",
            "Hoodie", "Zip-up Hoodie",
            "Vest"
        ),
        ClothingCategory.OUTERWEAR to listOf(
            "Jacket", "Coat", "Blazer"
        ),
        ClothingCategory.FOOTWEAR to listOf(
            "Sneakers", "Shoes", "Boots", "Sandals", "Clogs"
        ),
        ClothingCategory.DRESS to listOf(
            "Dress", "Jumpsuit"
        ),
        ClothingCategory.BAG to listOf(
            "Bag", "Backpack", "Handbag"
        ),
        ClothingCategory.ACCESSORY to listOf(
            "Hat", "Scarf", "Belt"
        ),
        ClothingCategory.SUIT to listOf(
            "Suit"
        )
    )

    /** Suggested type names for a category, or empty when none are defined. */
    fun suggestionsFor(category: ClothingCategory): List<String> =
        byCategory[category] ?: emptyList()
}
