package com.example.mioassistente

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Schermata: impostazioni, configurazione guidata del telefono, avvio/arresto e diagnostica. */
class MainActivity : Activity() {

    private lateinit var setupBox: LinearLayout
    private lateinit var diagBox: LinearLayout

    private val actionPermissions: List<String> get() = listOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fun label(t: String) = TextView(this).apply { text = t; textSize = 15f; setPadding(0, 32, 0, 4) }

        val frase = EditText(this).apply {
            setText(Settings.wakePhrase(this@MainActivity))
            hint = "ehi hermes"
        }
        val url = EditText(this).apply {
            setText(Settings.hermesUrl(this@MainActivity))
            hint = "100.x.x.x (indirizzo Tailscale del PC)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val key = EditText(this).apply {
            setText(Settings.hermesKey(this@MainActivity))
            hint = "password di Hermes (API_SERVER_KEY)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val start = Button(this).apply {
            text = "Salva e avvia assistente"
            setOnClickListener {
                Settings.save(this@MainActivity, frase.text.toString(), url.text.toString(), key.text.toString())
                if (Settings.hermesUrl(this@MainActivity).isEmpty()) {
                    Toast.makeText(this@MainActivity, "Inserisci l'indirizzo di Hermes", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                stopService(Intent(this@MainActivity, WakeWordService::class.java))
                askPermissionsThenStart()
                Toast.makeText(this@MainActivity, "Assistente avviato", Toast.LENGTH_SHORT).show()
            }
        }
        val stop = Button(this).apply {
            text = "Ferma"
            setOnClickListener { stopService(Intent(this@MainActivity, WakeWordService::class.java)) }
        }

        setupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        diagBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(TextView(this@MainActivity).apply { text = "Mio Assistente"; textSize = 24f })
            addView(TextView(this@MainActivity).apply { text = "Versione ${BuildConfig.VERSION_NAME}"; textSize = 13f })
            addView(label("Frase di attivazione")); addView(frase)
            addView(label("Indirizzo del PC con Hermes")); addView(url)
            addView(label("Password di Hermes")); addView(key)
            addView(start); addView(stop)
            addView(setupBox)
            addView(diagBox)
        }
        setContentView(ScrollView(this).apply { addView(layout) })

        val autoStart = intent?.action == Intent.ACTION_ASSIST || intent?.getBooleanExtra("avvia", false) == true
        if (autoStart && Settings.hermesUrl(this).isNotEmpty()) {
            askPermissionsThenStart()
        }
    }

    override fun onResume() {
        super.onResume()
        buildSetup()
        buildDiagnostics()
    }

    // ---------- Configurazione guidata ----------

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun notificationAccess(): Boolean {
        val flat = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return flat.contains(packageName)
    }

    private fun hasGoogleTts() = try { packageManager.getPackageInfo("com.google.android.tts", 0); true } catch (_: Throwable) { false }

    private fun isXiaomi() = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }

    private fun buildSetup() {
        setupBox.removeAllViews()
        setupBox.addView(TextView(this).apply {
            text = "Configura telefono"; textSize = 18f; setPadding(0, 56, 0, 4)
        })
        setupBox.addView(TextView(this).apply {
            text = "Servono perché l'assistente funzioni sempre e possa agire sul telefono."; textSize = 12f
        })
        val pm = getSystemService(PowerManager::class.java)

        row("Permessi per le azioni (contatti, chiamate, SMS, posizione, calendario)",
            actionPermissions.all { granted(it) }) {
            requestPermissions(actionPermissions.toTypedArray(), 2)
        }
        row("Batteria senza restrizioni", pm.isIgnoringBatteryOptimizations(packageName)) {
            open(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        row("Mostrarsi sopra le altre app (per aprire app, sveglie, chiamate)",
            android.provider.Settings.canDrawOverlays(this)) {
            open(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        row("Lettura delle notifiche", notificationAccess()) {
            open(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        row("Voce italiana di Google", hasGoogleTts()) {
            // apri proprio il Play Store di Google (sugli Xiaomi "market://" apre GetApps)
            val play = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts"))
                .setPackage("com.android.vending")
            if (!open(play, quiet = true))
                open(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts")))
        }
        if (isXiaomi()) {
            row("Xiaomi: avvio automatico (attivalo)", null) {
                val i = Intent().setComponent(ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                if (!open(i, quiet = true)) openAppDetails()
            }
            row("Xiaomi: \"Mostra finestre pop-up in background\" e \"Schermata di blocco\" (attivali)", null) {
                val i = Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", packageName)
                if (!open(i, quiet = true)) openAppDetails()
            }
        }
    }

    /** Una riga: stato (✓ / ✗ / •) + pulsante per sistemarlo. ok = null se non verificabile. */
    private fun row(title: String, ok: Boolean?, fix: () -> Unit) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }
        line.addView(TextView(this).apply {
            text = when (ok) { true -> "✓  $title"; false -> "✗  $title"; null -> "•  $title" }
            setTextColor(when (ok) { true -> Color.rgb(30, 130, 60); false -> Color.rgb(190, 40, 40); null -> Color.DKGRAY })
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (ok != true) line.addView(Button(this).apply {
            text = if (ok == null) "Apri" else "Attiva"
            setOnClickListener { fix() }
        })
        setupBox.addView(line)
    }

    private fun open(i: Intent, quiet: Boolean = false): Boolean = try {
        startActivity(i); true
    } catch (e: Throwable) {
        if (!quiet) Toast.makeText(this, "Impostazione non trovata su questo telefono", Toast.LENGTH_LONG).show()
        false
    }

    private fun openAppDetails() {
        open(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    // ---------- Diagnostica ----------

    private fun buildDiagnostics() {
        diagBox.removeAllViews()
        val crash = java.io.File(filesDir, "ultimo_errore.txt")
        val log = java.io.File(filesDir, "registro.txt")
        val text = buildString {
            if (crash.exists()) append("⚠️ ULTIMO ERRORE:\n").append(crash.readText()).append("\n\n")
            if (log.exists()) append("REGISTRO:\n").append(log.readLines().takeLast(30).joinToString("\n"))
        }
        diagBox.addView(TextView(this).apply { this.text = "Diagnostica"; textSize = 15f; setPadding(0, 48, 0, 4) })
        diagBox.addView(TextView(this).apply {
            this.text = text.ifBlank { "Nessun evento registrato finora." }
            textSize = 11f; setTextIsSelectable(true)
        })
        diagBox.addView(Button(this).apply {
            this.text = "Copia diagnostica"
            setOnClickListener {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("diagnostica", text))
                Toast.makeText(this@MainActivity, "Copiato: incollalo a Claude", Toast.LENGTH_SHORT).show()
            }
        })
        diagBox.addView(Button(this).apply {
            this.text = "Cancella diagnostica"
            setOnClickListener { crash.delete(); log.delete(); buildDiagnostics() }
        })
    }

    // ---------- Avvio ----------

    private fun askPermissionsThenStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { !granted(it) }
        if (missing.isEmpty()) startAssistant() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code == 1 && granted(Manifest.permission.RECORD_AUDIO)) startAssistant()
        if (code == 2) {
            buildSetup()
            // la posizione in background richiede di riavviare il servizio dopo il permesso
            stopService(Intent(this, WakeWordService::class.java))
            if (granted(Manifest.permission.RECORD_AUDIO) && Settings.hermesUrl(this).isNotEmpty()) startAssistant()
        }
    }

    private fun startAssistant() {
        startForegroundService(Intent(this, WakeWordService::class.java))
    }
}
