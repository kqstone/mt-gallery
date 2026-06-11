package com.kqstone.mtphotos.ui.util

import androidx.compose.ui.graphics.Color

/**
 * Centralized gradient colour presets used as fallback backgrounds in [CoverCard]
 * and [com.kqstone.mtphotos.ui.discovery.PersonCircleItem] across the app.
 *
 * Keeping them in one place makes it easy to tweak the palette without hunting
 * through individual screen files.
 */
object GradientPresets {
    /** Albums — warm orange-to-yellow. */
    val Album = listOf(Color(0xFFFDA085), Color(0xFFF6D365))

    /** Folders — fresh green. */
    val Folder = listOf(Color(0xFF38EF7D), Color(0xFF11998E))

    /** Locations — soft purple-to-pink. */
    val Location = listOf(Color(0xFF8E9DFB), Color(0xFFEDACF7))
}
