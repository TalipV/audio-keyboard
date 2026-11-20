package dev.yazin.keyboard.settings

import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import dev.yazin.keyboard.R
import dev.yazin.keyboard.engine.text.Shortcut
import dev.yazin.keyboard.engine.text.Shortcuts
import dev.yazin.keyboard.text.ShortcutFile

/**
 * Text shortcuts, like Samsung's: a trigger ("mfg", "017") and the text the strip offers for it. Tap one to change or
 * delete it; the menu imports and exports them as plain text (trigger, tab, text).
 */
class ShortcutsActivity : AppCompatActivity() {
    private lateinit var adapter: ArrayAdapter<Shortcut>
    private lateinit var empty: TextView
    private val shortcuts get() = ShortcutFile.get(this)

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { shortcuts.export(it) }
    }

    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val n = contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { shortcuts.import(it) } ?: 0
        saveAndRefresh()
        Toast.makeText(this, getString(R.string.shortcuts_imported, n), Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shortcuts)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        empty = findViewById(R.id.empty)
        adapter = object : ArrayAdapter<Shortcut>(this, android.R.layout.simple_list_item_2, android.R.id.text1, ArrayList()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val s = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).text = s.trigger
                v.findViewById<TextView>(android.R.id.text2).apply {
                    text = s.text
                    maxLines = 2
                }
                return v
            }
        }
        findViewById<ListView>(R.id.shortcuts).apply {
            adapter = this@ShortcutsActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> edit(this@ShortcutsActivity.adapter.getItem(pos)) }
        }
        findViewById<Button>(R.id.add).setOnClickListener { edit(null) }
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.dictionary_import)
        menu.add(0, 2, 0, R.string.dictionary_export)
        menu.add(0, 3, 0, R.string.shortcuts_clear)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> importFile.launch(arrayOf("text/plain"))
            2 -> exportFile.launch("keyboard-shortcuts.txt")
            3 -> AlertDialog.Builder(this).setMessage(R.string.shortcuts_clear_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ -> shortcuts.clear(); saveAndRefresh() }
                .setNegativeButton(android.R.string.cancel, null).show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /** Adds a shortcut ([old] null) or changes or deletes [old]. */
    private fun edit(old: Shortcut?) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val trigger = EditText(this).apply {
            hint = getString(R.string.shortcuts_trigger_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            setText(old?.trigger.orEmpty())
        }
        val text = EditText(this).apply {
            hint = getString(R.string.shortcuts_text_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(old?.text.orEmpty())
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(trigger)
            addView(text)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (old == null) R.string.shortcuts_add else R.string.shortcuts_edit)
            .setView(form)
            .setPositiveButton(android.R.string.ok, null) // set below, so a bad trigger keeps the dialog open
            .setNegativeButton(android.R.string.cancel, null)
            .apply { if (old != null) setNeutralButton(R.string.clip_delete) { _, _ -> shortcuts.remove(old); saveAndRefresh() } }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val t = trigger.text.toString().trim()
                if (!Shortcuts.isTrigger(t)) {
                    trigger.error = getString(R.string.shortcuts_bad_trigger)
                    return@setOnClickListener
                }
                if (text.text.isBlank()) {
                    text.error = getString(R.string.shortcuts_no_text)
                    return@setOnClickListener
                }
                shortcuts.put(t, text.text.toString(), old)
                saveAndRefresh()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun saveAndRefresh() {
        ShortcutFile.save(this)
        refresh()
    }

    private fun refresh() {
        val all = shortcuts.all()
        adapter.clear()
        adapter.addAll(all)
        empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
    }
}
