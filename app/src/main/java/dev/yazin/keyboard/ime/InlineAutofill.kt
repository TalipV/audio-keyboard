package dev.yazin.keyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import dev.yazin.keyboard.R
import dev.yazin.keyboard.keyboard.Dimens
import dev.yazin.keyboard.keyboard.KeyboardColors
import kotlin.math.roundToInt

/**
 * Android 11+ inline autofill: the keyboard asks for chips styled like its strip, the provider (1Password, Google)
 * renders them in its own process, and tapping one fills the field. The keyboard never sees what a chip holds.
 */
@RequiresApi(Build.VERSION_CODES.R)
object InlineAutofill {
    // The style builders' setters are public API inherited from a library-private base class, which lint misreports.
    @SuppressLint("RestrictedApi")
    fun request(context: Context, colors: KeyboardColors, rtl: Boolean): InlineSuggestionsRequest {
        val dp = context.resources.displayMetrics.density
        fun px(v: Float) = (v * dp).roundToInt()
        // Paddings and margins here are left/right, not start/end, so keep them symmetric for Arabic.
        fun chip(padding: Float) = ViewStyle.Builder()
            .setBackground(Icon.createWithResource(context, R.drawable.autofill_chip).setTint(colors.letterKey))
            .setPadding(px(padding), 0, px(padding), 0)
            .build()
        fun icon(size: Float) = ImageViewStyle.Builder().setMaxWidth(px(size)).setMaxHeight(px(size)).build()
        fun text(color: Int, sp: Float) = TextViewStyle.Builder()
            .setTextColor(color)
            .setTextSize(sp)
            .setTypeface(FONT, Typeface.NORMAL)
            .setLayoutMargin(px(4f), 0, px(4f), 0)
            .build()
        val style = InlineSuggestionUi.newStyleBuilder()
            .setLayoutDirection(if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR)
            .setChipStyle(chip(12f))
            .setTitleStyle(text(colors.label, Dimens.CHIP_SP))
            .setSubtitleStyle(text(colors.hint, Dimens.CHIP_SP - 2))
            .setStartIconStyle(icon(Dimens.CHIP_ICON))
            .setEndIconStyle(icon(Dimens.CHIP_ICON))
            // The pinned chip: the provider's icon alone (e.g. a key).
            .setSingleIconChipStyle(chip(8f))
            .setSingleIconChipIconStyle(icon(Dimens.ICON))
            .build()
        val height = px(Dimens.UNDO_HEIGHT)
        val spec = InlinePresentationSpec.Builder(Size(0, height), Size(context.resources.displayMetrics.widthPixels, height))
            .setStyle(UiVersions.newStylesBuilder().addStyle(style).build())
            .build()
        return InlineSuggestionsRequest.Builder(listOf(spec)).build()
    }

    /** Inflates every chip, then hands back the pinned ones and the rest in the provider's order (none to clear). */
    fun inflate(context: Context, response: InlineSuggestionsResponse, done: (pinned: List<View>, chips: List<View>) -> Unit) {
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) return done(emptyList(), emptyList())
        val views = arrayOfNulls<View>(suggestions.size)
        var left = suggestions.size
        val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        suggestions.forEachIndexed { i, s ->
            s.inflate(context, size, context.mainExecutor) { v ->
                views[i] = v
                if (--left == 0) {
                    val (pinned, chips) = suggestions.indices.filter { views[it] != null }.partition { suggestions[it].info.isPinned }
                    done(pinned.map { views[it]!! }, chips.map { views[it]!! })
                }
            }
        }
    }

    /** KeyboardView's face; the provider resolves it, falling back to the system sans. */
    private const val FONT = "google-sans"
}
