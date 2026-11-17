package dev.yazin.keyboard.setup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.yazin.keyboard.R
import dev.yazin.keyboard.settings.Prefs
import dev.yazin.keyboard.settings.SettingsActivity
import dev.yazin.keyboard.settings.imeId
import dev.yazin.keyboard.settings.languagesIntent
import dev.yazin.keyboard.voice.PermissionActivity

/** Gboard-style first-run steps plus a field to try the keyboard. Extra "focus"=true opens it with the keyboard up. */
class SetupActivity : Activity() {
    private lateinit var enable: Button
    private lateinit var select: Button
    private lateinit var mic: Button
    private lateinit var ai: Button
    private lateinit var field: EditText
    private var showKeyboard = false
    private val imm by lazy { getSystemService(InputMethodManager::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        enable = findViewById(R.id.enable)
        select = findViewById(R.id.select)
        enable.setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        select.setOnClickListener { imm.showInputMethodPicker() }
        mic = findViewById(R.id.mic)
        mic.setOnClickListener { startActivity(Intent(this, PermissionActivity::class.java)) }
        findViewById<Button>(R.id.languages).setOnClickListener { startActivity(languagesIntent(this)) }
        findViewById<Button>(R.id.settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        ai = findViewById(R.id.ai)
        ai.setOnClickListener { startActivity(SettingsActivity.screenIntent(this, "screen_ai")) }
        field = findViewById(R.id.field)
        showKeyboard = intent.getBooleanExtra("focus", false)
        if (showKeyboard) field.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // The input-method picker is a dialog: refresh when it closes and focus returns.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        refresh()
        // The keyboard can only be requested once the window has focus.
        if (showKeyboard) {
            showKeyboard = false
            WindowCompat.getInsetsController(window, field).show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun refresh() {
        val id = imeId(this)
        val enabled = imm.enabledInputMethodList.any { it.id == id }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == id
        enable.text = getString(if (enabled) R.string.setup_enabled else R.string.setup_enable)
        enable.isEnabled = !enabled
        select.text = getString(if (selected) R.string.setup_selected else R.string.setup_select)
        select.isEnabled = enabled && !selected
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        mic.text = getString(if (micOk) R.string.setup_mic_done else R.string.setup_mic)
        mic.isEnabled = !micOk
        // The AI features that learn from you are off until turned on; this is the way to them.
        ai.text = getString(if (Prefs(this).style) R.string.setup_ai_on else R.string.setup_ai)
    }
}
