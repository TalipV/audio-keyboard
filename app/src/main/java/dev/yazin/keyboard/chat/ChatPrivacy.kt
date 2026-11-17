package dev.yazin.keyboard.chat

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat
import dev.yazin.keyboard.R
import dev.yazin.keyboard.settings.Prefs

/** A fixed unlock window, measured with a monotonic clock and never persisted. */
internal class ChatUnlockWindow(private val now: () -> Long) {
    private var until = 0L
    val unlocked: Boolean get() = until != 0L && now() < until
    fun unlock() { until = now() + DURATION_MS }
    fun lock() { until = 0L }

    companion object { const val DURATION_MS = 5 * 60_000L }
}

/**
 * Settings → AI writing → Lock chat memory, shared by the keyboard, Settings → Chats and the Debug log. While the lock is
 * on, only a successful system prompt grants access; while it is off (the default), chat memory is always open.
 */
object ChatPrivacy {
    private val window = ChatUnlockWindow(SystemClock::elapsedRealtime)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    private var app: Context? = null
    private val expire = Runnable { lock() }

    fun initialize(context: Context) {
        if (app != null) return
        val application = context.applicationContext
        app = application
        ContextCompat.registerReceiver(application, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { lock() }
        }, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Whether the user turned the lock on. */
    fun required(context: Context): Boolean = Prefs(context).lockChats

    /** The phone has a strong biometric (fingerprint or face) to ask for. */
    @Suppress("DEPRECATION")
    fun available(context: Context): Boolean {
        val manager = context.getSystemService(BiometricManager::class.java)
        val status = if (Build.VERSION.SDK_INT >= 30) manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            else manager.canAuthenticate()
        return status == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun unlocked(): Boolean {
        val context = app ?: return false
        if (!required(context)) return true
        val available = context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        return available && window.unlocked
    }

    internal fun grant(): Boolean {
        val context = app ?: return false
        if (!context.getSystemService(PowerManager::class.java).isInteractive ||
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        window.unlock()
        main.removeCallbacks(expire)
        main.postDelayed(expire, ChatUnlockWindow.DURATION_MS)
        return true
    }

    fun lock() {
        window.lock()
        main.removeCallbacks(expire)
        if (app?.let(::required) == true) listeners.toList().forEach { it() } // nothing to hide while the lock is off
    }

    fun observeLock(listener: () -> Unit) { listeners += listener }
    fun stopObserving(listener: () -> Unit) { listeners -= listener }
}

/** Run from a foreground Activity; Android evicts prompts owned by a background Service. */
class ChatUnlock(private val context: Activity) {
    private var pending: CancellationSignal? = null

    init { ChatPrivacy.initialize(context) }

    /** Asks unless chat memory is already open; [always] asks anyway (turning the lock itself on or off). */
    @Suppress("DEPRECATION")
    fun request(onUnlocked: () -> Unit, onDenied: () -> Unit = {}, always: Boolean = false) {
        if (!always && ChatPrivacy.unlocked()) return onUnlocked()
        if (pending != null) return
        if (!ChatPrivacy.available(context)) {
            Toast.makeText(context, R.string.chat_unlock_unavailable, Toast.LENGTH_LONG).show()
            onDenied()
            return
        }
        val signal = CancellationSignal()
        pending = signal
        val builder = BiometricPrompt.Builder(context)
            .setTitle(context.getString(R.string.chat_unlock_title))
            .setSubtitle(context.getString(R.string.chat_unlock_subtitle))
            .setNegativeButton(context.getString(android.R.string.cancel), context.mainExecutor) { _, _ ->
                if (pending === signal) {
                    pending = null
                    onDenied()
                }
            }
        if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        else builder.setDeviceCredentialAllowed(false)
        builder.build().authenticate(signal, context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (pending !== signal) return
                pending = null
                if (ChatPrivacy.grant()) onUnlocked() else onDenied()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (pending !== signal) return
                pending = null
                if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED &&
                    errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                    Toast.makeText(context, errString, Toast.LENGTH_LONG).show()
                }
                onDenied()
            }
        })
    }

    fun cancel() {
        val signal = pending
        pending = null // a late success from a cancelled prompt must never grant access
        signal?.cancel()
    }
}
