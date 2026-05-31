package com.vastra.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.vastra.R

private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

// Cormorant Garamond — the Lovable serif. Used Light (300) for an editorial,
// luxury feel across headings and captions.
val CormorantGaramond = FontFamily(
    Font(googleFont = GoogleFont("Cormorant Garamond"), fontProvider = provider, weight = FontWeight.Light),
    Font(googleFont = GoogleFont("Cormorant Garamond"), fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("Cormorant Garamond"), fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Cormorant Garamond"), fontProvider = provider, weight = FontWeight.SemiBold),
)

// Kept as an alias so any lingering references compile.
val PlayfairDisplay = CormorantGaramond

val Inter = FontFamily(
    Font(googleFont = GoogleFont("Inter"), fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("Inter"), fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Inter"), fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = GoogleFont("Inter"), fontProvider = provider, weight = FontWeight.Bold),
)

val VastraTypography = Typography(
    displayLarge = TextStyle(fontFamily = CormorantGaramond, fontWeight = FontWeight.Light, fontSize = 57.sp, lineHeight = 60.sp),
    displayMedium = TextStyle(fontFamily = CormorantGaramond, fontWeight = FontWeight.Light, fontSize = 45.sp, lineHeight = 48.sp),
    displaySmall = TextStyle(fontFamily = CormorantGaramond, fontWeight = FontWeight.Light, fontSize = 36.sp, lineHeight = 40.sp),
    headlineLarge = TextStyle(fontFamily = CormorantGaramond, fontWeight = FontWeight.Light, fontSize = 32.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = CormorantGaramond, fontWeight = FontWeight.Light, fontSize = 28.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = CormorantGaramond, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)
