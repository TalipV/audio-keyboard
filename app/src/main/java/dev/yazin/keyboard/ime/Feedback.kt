package dev.yazin.keyboard.ime

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dev.yazin.keyboard.keyboard.KeyAction

/** Key-down feedback. Haptic levels: 0 off, 1 light (Gboard default), 2 medium, 3 strong. */
class Feedback(context: Context) {
    var hapticLevel = 1
    var sound = false

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)

    fun keyDown(action: KeyAction) {
        vibrate(when (hapticLevel) {
            1 -> VibrationEffect.EFFECT_TICK
            2 -> VibrationEffect.EFFECT_CLICK
            3 -> VibrationEffect.EFFECT_HEAVY_CLICK
            else -> -1
        })
        if (sound) {
            val fx = when (action) {
                KeyAction.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                KeyAction.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyAction.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio.playSoundEffect(fx, -1f)
        }
    }

    /** Spec §7.1: a tick when dictation starts and stops (off when key vibration is off). */
    fun voiceTick() = vibrate(if (hapticLevel > 0) VibrationEffect.EFFECT_TICK else -1)

    private fun vibrate(effect: Int) {
        if (effect < 0) return
        val e = VibrationEffect.createPredefined(effect)
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            vibrator.vibrate(e)
        }
    }
}
