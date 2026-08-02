package com.omnidocs.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * App-specific shape tokens — used by MaterialTheme.shapes throughout the app.
 *
 * Radii follow a consistent scale: small chips → cards → sheets → dialogs.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),   // chips, badges
    small = RoundedCornerShape(8.dp),        // buttons, small cards, import chip
    medium = RoundedCornerShape(12.dp),      // note cards, bottom sheets
    large = RoundedCornerShape(16.dp),       // dialogs, full-width cards
    extraLarge = RoundedCornerShape(24.dp)   // modals, search bars
)
