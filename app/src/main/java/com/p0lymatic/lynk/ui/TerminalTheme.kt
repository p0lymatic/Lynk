package com.p0lymatic.lynk.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.p0lymatic.lynk.R

/** A monospace family the terminal can use. */
data class TermFont(
    val id: String,
    val label: String,
    val family: FontFamily,
)

val terminalFonts: List<TermFont> = listOf(
    TermFont("system", "Системный моно", FontFamily.Monospace),
    TermFont(
        "dejavu",
        "DejaVu Sans Mono",
        FontFamily(Font(R.font.dejavu_mono, FontWeight.Normal), Font(R.font.dejavu_mono, FontWeight.Bold)),
    ),
    TermFont(
        "noto",
        "Noto Sans Mono",
        FontFamily(
            Font(R.font.noto_mono, FontWeight.Normal),
            Font(R.font.noto_mono_bold, FontWeight.Bold),
        ),
    ),
    TermFont(
        "ubuntu",
        "Ubuntu Mono",
        FontFamily(
            Font(R.font.ubuntu_mono, FontWeight.Normal),
            Font(R.font.ubuntu_mono_bold, FontWeight.Bold),
        ),
    ),
)

fun fontById(id: String): TermFont = terminalFonts.firstOrNull { it.id == id } ?: terminalFonts.first()

/** A full terminal palette: background, foreground, cursor and the 16 ANSI colours. */
data class TermScheme(
    val id: String,
    val label: String,
    val background: Color,
    val foreground: Color,
    val cursor: Color,
    val colors: List<Color>,
) {
    fun color(index: Int): Color = when {
        index in 0..15 -> colors[index]
        index in 16..231 -> {
            val i = index - 16
            val r = if (i / 36 == 0) 0 else 55 + (i / 36) * 40
            val g = if ((i % 36) / 6 == 0) 0 else 55 + ((i % 36) / 6) * 40
            val b = if (i % 6 == 0) 0 else 55 + (i % 6) * 40
            Color(0xFF000000.toInt() or (r shl 16) or (g shl 8) or b)
        }
        index in 232..255 -> {
            val level = 8 + (index - 232) * 10
            Color(0xFF000000.toInt() or (level shl 16) or (level shl 8) or level)
        }
        else -> foreground
    }
}

private fun scheme(
    id: String,
    label: String,
    bg: Long,
    fg: Long,
    cursor: Long,
    colors: LongArray,
) = TermScheme(
    id = id,
    label = label,
    background = Color(bg),
    foreground = Color(fg),
    cursor = Color(cursor),
    colors = colors.map { Color(it) },
)

val terminalSchemes: List<TermScheme> = listOf(
    scheme(
        "grey", "Строгий серый", 0xFF0F0F11, 0xFFE7E7EA, 0xFFE7E7EA,
        longArrayOf(
            0xFF1C1C1F, 0xFFB4716F, 0xFF7FA98C, 0xFFB49A5E,
            0xFF7C90B4, 0xFFA08BB5, 0xFF6FA3A8, 0xFFC9C9CF,
            0xFF5A5A64, 0xFFD08A87, 0xFF93C2A0, 0xFFCBB071,
            0xFF93A8CC, 0xFFB79FCB, 0xFF86BCC1, 0xFFE7E7EA,
        ),
    ),
    scheme(
        "solarized", "Solarized Dark", 0xFF002B36, 0xFF839496, 0xFF93A1A1,
        longArrayOf(
            0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900,
            0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
            0xFF002B36, 0xFFCB4B16, 0xFF586E75, 0xFF657B83,
            0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFFFDF6E3,
        ),
    ),
    scheme(
        "gruvbox", "Gruvbox Dark", 0xFF282828, 0xFFEBDBB2, 0xFFEBDBB2,
        longArrayOf(
            0xFF282828, 0xFFCC241D, 0xFF98971A, 0xFFD79921,
            0xFF458588, 0xFFB16286, 0xFF689D6A, 0xFFA89984,
            0xFF928374, 0xFFFB4934, 0xFFB8BB26, 0xFFFABD2F,
            0xFF83A598, 0xFFD3869B, 0xFF8EC07C, 0xFFEBDBB2,
        ),
    ),
    scheme(
        "nord", "Nord", 0xFF2E3440, 0xFFD8DEE9, 0xFFD8DEE9,
        longArrayOf(
            0xFF3B4252, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B,
            0xFF81A1C1, 0xFFB48EAD, 0xFF88C0D0, 0xFFE5E9F0,
            0xFF4C566A, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B,
            0xFF81A1C1, 0xFFB48EAD, 0xFF8FBCBB, 0xFFECEFF4,
        ),
    ),
    scheme(
        "dracula", "Dracula", 0xFF282A36, 0xFFF8F8F2, 0xFFF8F8F2,
        longArrayOf(
            0xFF21222C, 0xFFFF5555, 0xFF50FA7B, 0xFFF1FA8C,
            0xFFBD93F9, 0xFFFF79C6, 0xFF8BE9FD, 0xFFF8F8F2,
            0xFF6272A4, 0xFFFF6E6E, 0xFF69FF94, 0xFFFFFFA5,
            0xFFD6ACFF, 0xFFFF92DF, 0xFFA4FFFF, 0xFFFFFFFF,
        ),
    ),
    scheme(
        "matrix", "Matrix", 0xFF0B0F0B, 0xFF7FE08A, 0xFF7FE08A,
        longArrayOf(
            0xFF0B0F0B, 0xFF3FBF5F, 0xFF7FE08A, 0xFFA8E6A1,
            0xFF4FA3A8, 0xFF8FBF5F, 0xFF6FD0A8, 0xFFC8F0CC,
            0xFF1F3F27, 0xFF5FE07F, 0xFF9FFFA8, 0xFFC8FFC0,
            0xFF6FD0C8, 0xFFA8D08F, 0xFF8FE0B8, 0xFFE0FFE6,
        ),
    ),
)

fun schemeById(id: String): TermScheme = terminalSchemes.firstOrNull { it.id == id } ?: terminalSchemes.first()
