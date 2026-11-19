package dev.yazin.keyboard.settings

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import dev.yazin.keyboard.R
import dev.yazin.keyboard.ime.HistoryStore
import dev.yazin.keyboard.keyboard.Lang

/** Lists, adds, removes, imports and exports the user's words for each language (plain text, one word per line). */
class DictionaryActivity : AppCompatActivity() {
    private var lang = Lang.EN
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var empty: TextView
    private val history get() = HistoryStore.get(this, lang)

    private val exportWords = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w -> history.userWords().forEach { w.write(it); w.newLine() } }
    }

    private val importWords = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && ' ' !in it }.forEach { history.add(it) }
        }
        saveAndRefresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dictionary)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        empty = findViewById(R.id.empty)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        findViewById<ListView>(R.id.words).apply {
            adapter = this@DictionaryActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> confirmRemove(this@DictionaryActivity.adapter.getItem(pos)!!) }
        }
        findViewById<Spinner>(R.id.language).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf(getString(R.string.subtype_en), getString(R.string.subtype_ar), getString(R.string.subtype_de)))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    lang = listOf(Lang.EN, Lang.AR, Lang.DE)[pos]
                    refresh()
                }
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
        }
        findViewById<Button>(R.id.add).setOnClickListener { promptAdd() }
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.dictionary_import)
        menu.add(0, 2, 0, R.string.dictionary_export)
        menu.add(0, 3, 0, R.string.dictionary_clear)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> importWords.launch(arrayOf("text/plain"))
            2 -> exportWords.launch("keyboard-words-${lang.name.lowercase()}.txt")
            3 -> AlertDialog.Builder(this).setMessage(R.string.dictionary_clear_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ -> history.clear(); saveAndRefresh() }
                .setNegativeButton(android.R.string.cancel, null).show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun promptAdd() {
        val input = EditText(this)
        AlertDialog.Builder(this).setTitle(R.string.dictionary_add).setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() && ' ' !in it }?.let { history.add(it); saveAndRefresh() }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun confirmRemove(word: String) {
        AlertDialog.Builder(this).setMessage(getString(R.string.dictionary_remove, word))
            .setPositiveButton(android.R.string.ok) { _, _ -> history.remove(word); saveAndRefresh() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun saveAndRefresh() {
        HistoryStore.save(this)
        refresh()
    }

    private fun refresh() {
        val words = history.userWords()
        adapter.clear()
        adapter.addAll(words)
        empty.visibility = if (words.isEmpty()) View.VISIBLE else View.GONE
    }
}
