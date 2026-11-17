package dev.yazin.keyboard.keyboard

data class KeyboardColors(
    val background: Int,
    val letterKey: Int,
    val letterKeyPressed: Int,
    val functionKey: Int,
    val functionKeyPressed: Int,
    val label: Int,
    val hint: Int,
    val accent: Int,
    val accentPressed: Int,
    val onAccent: Int,
    val popup: Int,
    val popupShadow: Int,
    val toolbarIcon: Int,
    val divider: Int,
)

/** Both palettes are measured from the user's Gboard screenshots. Pressed shades are estimates. */
object Themes {
    val LIGHT = KeyboardColors(
        background = 0xFFF0F4F9.toInt(),
        letterKey = 0xFFFFFFFF.toInt(),
        letterKeyPressed = 0xFFE1E3E1.toInt(),
        functionKey = 0xFFE1E3E1.toInt(),
        functionKeyPressed = 0xFFC4C7C5.toInt(),
        label = 0xFF1F1F1F.toInt(),
        hint = 0xFF444746.toInt(),
        accent = 0xFF0B57D0.toInt(),
        accentPressed = 0xFF0842A0.toInt(),
        onAccent = 0xFFFFFFFF.toInt(),
        popup = 0xFFFFFFFF.toInt(),
        popupShadow = 0x40000000,
        toolbarIcon = 0xFF1F1F1F.toInt(),
        divider = 0xFFC9CDCE.toInt(),
    )

    val DARK = KeyboardColors(
        background = 0xFF1E1F20.toInt(),
        letterKey = 0xFF37393B.toInt(),
        letterKeyPressed = 0xFF454746.toInt(),
        functionKey = 0xFF454746.toInt(),
        functionKeyPressed = 0xFF5E5E5E.toInt(),
        label = 0xFFE3E3E3.toInt(),
        hint = 0xFFC4C7C5.toInt(),
        accent = 0xFFAEC6F6.toInt(),
        accentPressed = 0xFF8FB0F0.toInt(),
        onAccent = 0xFF132D6B.toInt(),
        popup = 0xFF37393B.toInt(),
        popupShadow = 0x66000000,
        toolbarIcon = 0xFFE3E3E3.toInt(),
        divider = 0xFF444746.toInt(),
    )
}
