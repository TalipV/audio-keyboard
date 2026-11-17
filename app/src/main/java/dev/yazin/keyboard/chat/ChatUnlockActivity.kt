package dev.yazin.keyboard.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager

/** Android evicts a Service's biometric prompt when another package owns the foreground task. */
class ChatUnlockActivity : Activity() {
    private lateinit var unlock: ChatUnlock
    private var id = 0
    private var requested = false
    private val onLocked = { complete(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        id = intent.getIntExtra(REQUEST, 0)
        unlock = ChatUnlock(this)
        if (id !in callbacks) return finishAndRemoveTask()
        ChatPrivacy.observeLock(onLocked)
    }

    override fun onPostResume() {
        super.onPostResume()
        if (isFinishing || requested) return
        requested = true
        unlock.request(onUnlocked = { complete(true) }, onDenied = { complete(false) })
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) complete(false)
    }

    override fun onDestroy() {
        unlock.cancel()
        ChatPrivacy.stopObserving(onLocked)
        if (!isChangingConfigurations) callbacks.remove(id)?.invoke(false)
        super.onDestroy()
    }

    private fun complete(success: Boolean) {
        unlock.cancel()
        val callback = callbacks.remove(id)
        finishAndRemoveTask()
        callback?.invoke(success)
    }

    companion object {
        private const val REQUEST = "request"
        private var next = 0
        private val callbacks = HashMap<Int, (Boolean) -> Unit>()

        /** Only this process can supply the continuation; the activity isn't exported. */
        fun open(context: Context, onResult: (Boolean) -> Unit): () -> Unit {
            val id = ++next
            callbacks[id] = onResult
            try {
                context.startActivity(Intent(context, ChatUnlockActivity::class.java)
                    .putExtra(REQUEST, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            } catch (e: RuntimeException) {
                callbacks.remove(id)
                throw e
            }
            return { callbacks.remove(id); Unit }
        }
    }
}
