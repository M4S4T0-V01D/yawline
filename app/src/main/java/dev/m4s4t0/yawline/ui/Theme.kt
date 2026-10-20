package dev.m4s4t0.yawline.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

data class Palette(
    val id: String,
    val name: String,
    val blurb: String,
    val light: Boolean,
    val bg: Color,
    val panel: Color,
    val panelAlt: Color,
    val border: Color,
    val text: Color,
    val dim: Color,
    val accent: Color,
    val accent2: Color,
    val ok: Color,
    val warn: Color,
    val bad: Color,
)

private fun c(hex: Long) = Color(0xFF000000 or hex)

val THEMES = listOf(
    Palette("carbon", "Carbon", "True black, orange and ice. Easy on OLED.", false,
        c(0x000000), c(0x0D0E11), c(0x16181D), c(0x23262D), c(0xF2F4F7), c(0x8A93A3), c(0xFF5A1F), c(0x22D3EE), c(0x34D399), c(0xFBBF24), c(0xFF3B47)),
    Palette("night", "Night Stage", "Red only, to keep your night vision on dark stages.", false,
        c(0x000000), c(0x0B0202), c(0x150404), c(0x2A0808), c(0xFF5A4F), c(0x99302A), c(0xFF2A1F), c(0xFF8A5C), c(0xD9564A), c(0xFF9A3D), c(0xFF1010)),
    Palette("gravel", "Gravel", "Warm amber on dark earth.", false,
        c(0x0E0B07), c(0x18130D), c(0x221B13), c(0x342A1D), c(0xF7EBDA), c(0xA8957C), c(0xFFB020), c(0xE0703A), c(0x9BD15A), c(0xFFD15C), c(0xFF4E3A)),
    Palette("neon", "Tarmac Neon", "Neon green and magenta, city-night style.", false,
        c(0x06060B), c(0x0F0F1A), c(0x171726), c(0x26263D), c(0xEDEBFF), c(0x8C89B3), c(0x39FF88), c(0xFF2BD6), c(0x39FF88), c(0xFFE14D), c(0xFF3B6B)),
    Palette("rally", "Rally Blue", "Classic blue and gold livery.", false,
        c(0x020615), c(0x09112B), c(0x0F1A3D), c(0x1C2A57), c(0xEEF2FF), c(0x8D9CC9), c(0xFFD400), c(0x3B82F6), c(0x4ADE80), c(0xFFD400), c(0xFF4D4D)),
    Palette("ice", "Ice", "High-contrast white on deep blue, for snow stages.", false,
        c(0x03070D), c(0x0A121C), c(0x111C2A), c(0x1F3047), c(0xFFFFFF), c(0xA9BCD4), c(0x7DD3FC), c(0xE0F2FE), c(0x6EE7B7), c(0xFDE047), c(0xFB7185)),
    Palette("cvd", "Colour-safe", "Okabe–Ito colours that read with any colour vision.", false,
        c(0x050505), c(0x101010), c(0x1A1A1A), c(0x2C2C2C), c(0xF5F5F5), c(0x9A9A9A), c(0xE69F00), c(0x56B4E9), c(0x009E73), c(0xF0E442), c(0xD55E00)),
    Palette("glare", "Sun Glare", "Bright and bold, for midday sun on the screen.", true,
        c(0xF4F4F1), c(0xFFFFFF), c(0xE9E9E4), c(0xC9C9C2), c(0x0A0A0A), c(0x55554F), c(0xE11D48), c(0x0369A1), c(0x15803D), c(0xB45309), c(0xDC2626)),
)

/** The colours in use right now; reading them in composition recolours everything on a theme change. */
object Yaw {
    var palette by mutableStateOf(THEMES.first())
    val bg get() = palette.bg
    val panel get() = palette.panel
    val panelAlt get() = palette.panelAlt
    val border get() = palette.border
    val text get() = palette.text
    val dim get() = palette.dim
    val accent get() = palette.accent
    val accent2 get() = palette.accent2
    val ok get() = palette.ok
    val warn get() = palette.warn
    val bad get() = palette.bad
    fun mix(a: Color, b: Color, f: Float) = lerp(a, b, f)

    /** Green → amber → red along 0..1, in the theme's own colours. */
    fun heat(f: Float): Color = if (f < 0.5f) lerp(ok, warn, f * 2) else lerp(warn, bad, (f - 0.5f) * 2)
}

@Composable
fun YawTheme(content: @Composable () -> Unit) {
    val p = Yaw.palette
    val on = if (p.light) Color.White else Color.Black
    val scheme = if (p.light) {
        lightColorScheme(
            primary = p.accent, onPrimary = on, secondary = p.accent2, onSecondary = on,
            primaryContainer = lerp(p.panelAlt, p.accent, 0.25f), onPrimaryContainer = p.text,
            secondaryContainer = lerp(p.panelAlt, p.accent, 0.16f), onSecondaryContainer = p.text,
            background = p.bg, onBackground = p.text, surface = p.panel, onSurface = p.text,
            surfaceVariant = p.panelAlt, onSurfaceVariant = p.dim, surfaceContainer = p.panel,
            surfaceContainerHigh = p.panelAlt, surfaceContainerHighest = p.panelAlt, outline = p.border, error = p.bad,
        )
    } else {
        darkColorScheme(
            primary = p.accent, onPrimary = on, secondary = p.accent2, onSecondary = on,
            primaryContainer = lerp(p.panel, p.accent, 0.7f), onPrimaryContainer = Color.White,
            secondaryContainer = lerp(p.panel, p.accent, 0.3f), onSecondaryContainer = p.text,
            background = p.bg, onBackground = p.text, surface = p.panel, onSurface = p.text,
            surfaceVariant = p.panelAlt, onSurfaceVariant = p.dim, surfaceContainer = p.panel,
            surfaceContainerHigh = p.panelAlt, surfaceContainerHighest = p.panelAlt, outline = p.border, error = p.bad,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
