package com.vastra.ui.theme

import androidx.compose.ui.graphics.Color

// ── Vastra warm editorial palette (Lovable design source of truth) ──────────
// Warm cream / sand / ink system. No pure white, no pure black, no gold accent.

val VastraInk = Color(0xFF211E1A)        // near-black, warm-tinted — text & primary CTAs
val VastraCream = Color(0xFFF8F4EC)      // warm cream — app background
val VastraCard = Color(0xFFFBF8F2)       // slightly lighter cream — cards
val VastraSand = Color(0xFFEBE5D9)       // warm beige — chips/tags/secondary
val VastraMuted = Color(0xFFEFEBE2)      // very light cream — muted surfaces
val VastraMutedText = Color(0xFF6F665B)  // warm brown-gray — labels/subtext
val VastraAccent = Color(0xFFD9C4A6)     // warm beige accent
val VastraBorderColor = Color(0xFFE3DDD2) // warm light border

// ── Backwards-compatible aliases (existing screens reference these names) ───
// Repointed onto the warm system so the gold accent disappears immediately.
val VastraCharcoal = VastraInk
val VastraGold = VastraInk               // CTA/accent text → ink (per Lovable)
val VastraGoldLight = VastraSand         // chip background → sand
val VastraGoldDark = VastraMutedText     // chip label → muted
val VastraSurface = VastraCard
val VastraSurfaceVariant = VastraMuted
val VastraOutline = VastraBorderColor
val VastraSubtext = VastraMutedText

val VastraError = Color(0xFFBA4A33)      // burnt red
val VastraSuccess = Color(0xFF6F8B6A)    // muted sage

// Legacy swipe colors (Discover screen removed; kept to avoid breaking refs)
val SwipeLikeGreen = Color(0xFF6F8B6A)
val SwipeDislikeRed = Color(0xFFBA4A33)
val SwipeSuperLikeBlue = Color(0xFF5C6B7A)
