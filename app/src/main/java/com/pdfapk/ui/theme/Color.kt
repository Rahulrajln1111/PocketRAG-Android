package com.pdfapk.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ─── Brand colours ───────────────────────────────────────────────────────────
val Blue80 = Color(0xFFB3D4FF)
val Blue60 = Color(0xFF6BA3E8)
val Blue40 = Color(0xFF2979FF)
val Blue20 = Color(0xFF0D47A1)

val Teal80 = Color(0xFFB2DFDB)
val Teal60 = Color(0xFF80CBC4)
val Teal40 = Color(0xFF26A69A)
val Teal20 = Color(0xFF00695C)

val Neutral99 = Color(0xFFFBFDFA)
val Neutral90 = Color(0xFFE2E3DF)
val Neutral80 = Color(0xFFC6C7C3)
val Neutral30 = Color(0xFF454744)
val Neutral20 = Color(0xFF2F312E)
val Neutral10 = Color(0xFF1A1C19)

// ─── Light scheme ────────────────────────────────────────────────────────────
val LightColorScheme = lightColorScheme(
    primary              = Blue40,
    onPrimary            = Color.White,
    primaryContainer     = Blue80,
    onPrimaryContainer   = Blue20,
    secondary            = Teal40,
    onSecondary          = Color.White,
    secondaryContainer   = Teal80,
    onSecondaryContainer = Teal20,
    surface              = Neutral99,
    onSurface            = Neutral10,
    surfaceContainerHighest = Neutral90,
    background           = Neutral99,
    onBackground         = Neutral10,
    error                = Color(0xFFBA1A1A),
    onError              = Color.White,
)

// ─── Dark scheme ─────────────────────────────────────────────────────────────
val DarkColorScheme = darkColorScheme(
    primary              = Blue80,
    onPrimary            = Blue20,
    primaryContainer     = Blue40,
    onPrimaryContainer   = Blue80,
    secondary            = Teal80,
    onSecondary          = Teal20,
    secondaryContainer   = Teal40,
    onSecondaryContainer = Teal80,
    surface              = Neutral10,
    onSurface            = Neutral90,
    surfaceContainerHighest = Neutral30,
    background           = Neutral10,
    onBackground         = Neutral90,
    error                = Color(0xFFFFB4AB),
    onError              = Color(0xFF690005),
)
