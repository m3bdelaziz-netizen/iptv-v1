package com.iptv.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** App-wide look: background style (Midnight / Graphite / Super AMOLED) + accent color. Changes apply instantly. */
object Theme {
    val modes = listOf("Midnight", "Graphite", "Super AMOLED")
    val accents = listOf(
        "Red" to 0xFFE50914L, "Blue" to 0xFF2F80FFL, "Green" to 0xFF1DB954L, "Purple" to 0xFF9B5CFFL,
        "Orange" to 0xFFFF8A00L, "Teal" to 0xFF00BFA5L, "Pink" to 0xFFFF4081L, "Gold" to 0xFFFFC107L
    )

    var mode by mutableStateOf(0)
    var accentIdx by mutableStateOf(0)

    fun load() {
        mode = when {
            Store.sp.contains("theme") -> Store.int("theme", 0).coerceIn(0, modes.size - 1)
            Store.bool("black", false) -> 2          // migrate the old "Pure black background" switch
            else -> 0
        }
        accentIdx = Store.int("accent", 0).coerceIn(0, accents.size - 1)
    }

    fun chooseMode(i: Int) { mode = i; Store.put("theme", i) }
    fun setAccent(i: Int) { accentIdx = i; Store.put("accent", i) }

    val accent: Color get() = Color(accents[accentIdx].second)
    /** Text/icon color that stays readable on top of the accent color. */
    val onAccent: Color get() = if (accent.luminance() > 0.45f) Color.Black else Color.White

    val bg: Color get() = when (mode) { 2 -> Color(0xFF000000); 1 -> Color(0xFF121212); else -> Color(0xFF0A0E1A) }
    val card: Color get() = when (mode) { 2 -> Color(0xFF0C0C0C); 1 -> Color(0xFF1E1E1E); else -> Color(0xFF1A1F30) }
    val chip: Color get() = when (mode) { 2 -> Color(0xFF1A1A1A); 1 -> Color(0xFF2C2C2C); else -> Color(0xFF262B40) }
    val sidebar: Color get() = if (mode == 2) Color(0xFF000000) else Color(0x55000000)
    val panel: Color get() = if (mode == 2) Color(0xFF000000) else Color(0x66000000)
}
