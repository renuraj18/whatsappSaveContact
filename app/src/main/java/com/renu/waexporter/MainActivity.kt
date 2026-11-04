package com.renu.waexporter

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var store: ContactStore
    private val createCsv = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = ContactStore(this)

        findViewById<Button>(R.id.enableBtn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Find \"WA Contact Exporter\" and turn it on", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.openWaBtn).setOnClickListener {
            val launch = ExporterService.WA_PACKAGES.firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
            if (launch != null) startActivity(launch)
            else Toast.makeText(this, "WhatsApp not found", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.exportBtn).setOnClickListener {
            if (store.count() == 0) { Toast.makeText(this, "Nothing to export yet", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_TITLE, "whatsapp-contacts-$stamp.csv")
            startActivityForResult(intent, createCsv)
        }
        findViewById<Button>(R.id.clearBtn).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Clear all ${store.count()} collected numbers?")
                .setPositiveButton("Clear") { _, _ -> store.clear(); render() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val enabled = isServiceEnabled()
        findViewById<TextView>(R.id.serviceStatus).text =
            if (enabled) "✅ Scanner is ON" else "⚠️ Scanner is OFF — tap step 1"
        val all = store.all()
        val named = all.count { it.value.isNotBlank() }
        findViewById<TextView>(R.id.count).text = "${all.size} unsaved numbers · $named with names"
        findViewById<TextView>(R.id.list).text = all.entries.joinToString("\n") { (n, name) ->
            if (name.isBlank()) n else "$n  $name"
        }
    }

    private fun isServiceEnabled(): Boolean {
        val me = ComponentName(this, ExporterService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != createCsv || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val csv = CsvExport.build(
            store.all(),
            findViewById<EditText>(R.id.prefix).text.toString(),
            findViewById<EditText>(R.id.label).text.toString()
        )
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "Saved ${store.count()} contacts", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
