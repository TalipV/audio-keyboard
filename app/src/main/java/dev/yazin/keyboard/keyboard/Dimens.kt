package dev.yazin.keyboard.keyboard

/**
 * Gboard geometry in dp (sizes) and sp (text), measured from the user's Gboard screenshots
 * (900 px Gboard captures of a 411.4 dp screen = 2.1875 px/dp). These are calibration
 * knobs, tuned by overlaying screenshots against Gboard.
 */
object Dimens {
    const val SIDE_MARGIN = 4.6f
    const val KEY_GAP = 5.0f
    /** Gap above every row, including between the toolbar and the first row. */
    const val ROW_GAP = 8.7f
    const val NUMBER_ROW_HEIGHT = 45.7f
    const val KEY_HEIGHT = 48.9f
    const val BOTTOM_ROW_HEIGHT = 52.1f
    const val TOOLBAR_HEIGHT = 43.4f
    const val BOTTOM_PADDING = 20f
    const val CORNER_RADIUS = 7.3f
    /** Transparent space above the keyboard where previews and long-press panels draw over the app. */
    const val POPUP_HEADROOM = 64f
    const val POPUP_GAP = 4f
    /** Phone pad (measured): 58 dp pill rows, 5.9 dp apart. */
    const val PAD_ROW_HEIGHT = 58f
    const val PAD_ROW_GAP = 5.9f

    /** Landscape (measured on the user's Gboard): ~35 dp keys, 2.7 dp row gaps, wider 9.1 dp key gaps. */
    const val LANDSCAPE_KEY_SCALE = 0.7f
    const val LANDSCAPE_ROW_GAP = 2.7f
    const val LANDSCAPE_KEY_GAP = 9.1f
    const val LANDSCAPE_BOTTOM_PADDING = 4f

    /** Long-press panel (measured): 44.2 dp cells and 12.6 dp padding in one row; 40 dp / 10.1 dp when it wraps; 6.9 dp above the key. */
    const val ALT_CELL = 44.2f
    const val ALT_CELL_MULTI = 40.05f
    const val ALT_PADDING = 12.6f
    const val ALT_PADDING_MULTI = 10.1f
    const val ALT_GAP = 6.9f
    const val ALT_MULTI_RADIUS = 28f
    /** While a panel is open Gboard fades the keyboard ~60% toward its background. */
    const val SCRIM_ALPHA = 0.6f

    /** Glide typing (glide spec §2–3, measured on Gboard 12.4): how far a letter touch moves before it glides, and the trail. */
    const val GLIDE_START = 22f
    const val TRAIL_HEAD = 10f
    const val TRAIL_TAIL = 2f
    const val TRAIL_FADE_MS = 800L
    const val TRAIL_LIFT_FADE_MS = 300L

    const val LABEL_SP = 26f
    const val NUMBER_LABEL_SP = 27.5f
    const val FUNCTION_LABEL_SP = 18.5f
    const val SPACE_LABEL_SP = 14f
    /** Space-bar swipe: sideways travel (dp) before the language label moves, and the slide after lift. */
    const val SPACE_SWIPE_START = 12f
    const val SPACE_SLIDE_MS = 160L
    /** Cursor control: travel per character and per line (dp), the dimming over the keys, the caption size. */
    const val CURSOR_STEP = 9f
    const val CURSOR_LINE = 40f
    const val CURSOR_SCRIM_ALPHA = 0.85f
    const val CURSOR_CAPTION_SP = 18f
    const val HINT_SP = 11f
    const val PREVIEW_LABEL_SP = 34f
    const val ALT_LABEL_SP = 24f
    const val PAD_LABEL_SP = 31f
    const val PAD_SUBLABEL_SP = 13.5f
    const val PAD_SYMBOL_SP = 22.5f

    const val FUNCTION_ICON = 28f
    const val ICON = 24f
    const val TOOLBAR_ICON = 24f
    /** Gboard toolbar: first icon centre 22.4 dp from the leading edge, then one icon every 60.9 dp. */
    const val TOOLBAR_FIRST_CENTER = 22.4f
    const val TOOLBAR_SLOT = 60.9f

    /** Suggestion strip (measured): words between the leading icon and the trailing mic / undo chip. */
    const val STRIP_SP = 18f
    const val STRIP_START = 40.2f
    const val STRIP_END = 83.9f // +40 dp: ✦ sits before the mic (AI orb spec §2)
    const val STRIP_END_WITH_UNDO = 144f // +40 dp: ✦ sits before the mic (AI orb spec §2)
    const val STRIP_DIVIDER_HEIGHT = 23f
    const val STRIP_DIVIDER_WIDTH = 1.1f
    const val UNDO_WIDTH = 51.7f
    const val UNDO_HEIGHT = 33.8f
    const val UNDO_CENTER_FROM_END = 116.1f // +40 dp: ✦ sits before the mic (AI orb spec §2)

    /** Toolbar mic (measured on a Gboard screenshot): 32.9 dp white circle, centre 23.1 dp from the trailing edge. */
    const val MIC_DIAMETER = 32.9f
    const val MIC_CENTER_FROM_END = 23.1f
    const val MIC_ICON = 24f
    const val MIC_HIT = 48f
    const val MIC_BADGE = 16f
    const val MIC_BADGE_SP = 10f
    /** Pending-dictation chip in the strip (spec §7.3). */
    const val CHIP_SP = 14f
    const val CHIP_ICON = 18f


    /** Emoji panel (spec §8), measured on the emulator's Gboard 12. */
    const val EMOJI_COLUMNS = 9
    /** Wider screens (landscape) add columns rather than grow cells past this. */
    const val EMOJI_MAX_CELL = 48f
    const val EMOJI_SIDE_MARGIN = 5.1f
    const val EMOJI_SP = 30f
    const val EMOJI_HEADER_HEIGHT = 24f
    /** Header text centre within its row, and its leading inset. */
    const val EMOJI_HEADER_TEXT_Y = 15f
    const val EMOJI_HEADER_X = 12.8f
    const val EMOJI_HEADER_SP = 12f
    const val EMOJI_TAB_ICON = 22f
    const val EMOJI_TAB_UNDERLINE = 24f
    const val EMOJI_TAB_UNDERLINE_HEIGHT = 2f
    /** ABC and delete centres, from the bar's edges. */
    const val EMOJI_BAR_SIDE = 29f
    /** The corner mark on emoji that have skin tones. */
    const val EMOJI_MARK = 5f
    const val EMOJI_POPUP_CELL = 44f
    const val EMOJI_POPUP_PAD = 8f
    const val EMOJI_POPUP_RADIUS = 16f
    /**
     * The "Search emoji" pill leads the tab row, which scrolls sideways (measured on gboard12-emulator-emoji.png, 2.49
     * px/dp): inset, size, and its magnifier's centre and text start from its leading edge. Tabs then get 36 dp each.
     */
    const val EMOJI_SEARCH_PILL_INSET = 9.6f
    const val EMOJI_SEARCH_PILL_WIDTH = 205f
    const val EMOJI_SEARCH_PILL_HEIGHT = 32f
    const val EMOJI_SEARCH_ICON_X = 15.4f
    const val EMOJI_SEARCH_TEXT_X = 36.6f
    const val EMOJI_SEARCH_SP = 17f
    const val EMOJI_TAB_GAP = 4.6f
    const val EMOJI_TAB_SLOT = 36f
    /** Searching (design values): the query field takes the toolbar row; the results row below it grows the keyboard. */
    const val EMOJI_RESULTS_HEIGHT = 48f
    const val EMOJI_QUERY_PAD = 16f
    const val EMOJI_CARET_WIDTH = 2f
    const val EMOJI_CARET_HEIGHT = 20f

    /** Clipboard panel (spec §8), after the emulator's Gboard 12. */
    const val CLIP_TITLE_X = 56f
    const val CLIP_TITLE_SP = 18f
    const val CLIP_MARGIN = 10f
    const val CLIP_GAP = 8f
    const val CLIP_CARD_HEIGHT = 72f
    const val CLIP_CARD_RADIUS = 12f
    const val CLIP_CARD_PAD = 12f
    const val CLIP_SP = 15f
    const val CLIP_BADGE = 14f
    const val CLIP_LONG_PRESS_MS = 500L

    /** Gboard's leading grid while the strip is busy (measured on gboard-strip-midword-en.png). */
    const val GRID_SQUARE = 7.8f
    const val GRID_GAP = 2.7f
    const val GRID_RADIUS = 2.2f

    /** Pills in the clipboard panel (first measured for the old voice panel). */
    const val PANEL_PILL_HEIGHT = 36f
    const val PANEL_PILL_PAD = 18f
    const val PANEL_PILL_SP = 15f

    // AI orb (AI orb spec §2): ✦ in the toolbar, and the band that replaces the keys while voice runs.
    const val SPARK_CENTER_FROM_END = 63.1f
    const val SPARK_ICON = 22f
    /** The row holding the keyboard chip, the orb and ↶; the app ends at its centre line. */
    const val ORB_BAND = 96f
    /** From the orb's bottom edge to the caption card. */
    const val ORB_CAPTION_GAP = 8f
    /** Room below that for the caption card: two lines of transcript, snugly. */
    const val ORB_CAPTION_ZONE = 68f
    const val ORB_DIAMETER = 64f
    /** How much the orb swells at full voice level. */
    const val ORB_SWELL = 0.18f
    const val ORB_UNDO_DIAMETER = 44f
    /** From the orb's centre to ↶'s, on the trailing side. */
    const val ORB_UNDO_OFFSET = 64f
    /** The keyboard chip, clear of the screen edge. */
    const val ORB_CHIP_CENTER = 32f
    /** The caption card keeps this far from the screen edges. */
    const val ORB_CAPTION_MARGIN = 16f
    const val ORB_CAPTION_SP = 15f
    const val ORB_CAPTION_PAD = 14f
    const val ORB_CAPTION_RADIUS = 20f
    /** A card with pills packs one line, the pills and its padding into [ORB_CAPTION_ZONE]: 8 + 20 + 4 + 28 + 8. */
    const val ORB_PILL_HEIGHT = 28f
    const val ORB_PILL_CARD_PAD = 8f
    const val ORB_PILL_GAP = 4f
    const val ORB_PILL_SP = 14f
    /** A drag down this far on the band dismisses it. */
    const val ORB_SWIPE = 40f

    // Chat memory (chat memory spec §7): the chip in the idle toolbar and the panel.
    /** The chip's text inset from each end of its pill (it is [UNDO_HEIGHT] tall, in [CHIP_SP]). */
    const val CHAT_CHIP_PAD = 12f
    /** The panel's inset from the screen edges, and the space above each section title. */
    const val CHAT_MARGIN = 16f
    const val CHAT_SECTION_GAP = 8f
    /** Between lines of a section, and between pills. */
    const val CHAT_LINE_GAP = 6f
    const val CHAT_SP = 15f
    /** A full-width tappable row: the switch and Forget. */
    const val CHAT_ROW_HEIGHT = 48f
    const val CHAT_SWITCH_WIDTH = 36f
    const val CHAT_SWITCH_HEIGHT = 20f
    const val CHAT_SWITCH_THUMB = 16f
}
