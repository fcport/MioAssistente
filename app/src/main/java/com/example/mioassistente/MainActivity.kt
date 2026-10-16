package com.example.mioassistente

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Schermata: impostazioni (frase, indirizzo Hermes, password) + avvia/ferma. */
class MainActivity : Activity() {

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

        val stt = EditText(this).apply {
            setText(Settings.sttUrl(this@MainActivity))
            hint = "facoltativo, es. 100.x.x.x:3901"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }

        val start = Button(this).apply {
            text = "Salva e avvia assistente"
            setOnClickListener {
                Settings.save(this@MainActivity, frase.text.toString(), url.text.toString(), key.text.toString())
                Settings.saveStt(this@MainActivity, stt.text.toString())
                if (Settings.hermesUrl(this@MainActivity).isEmpty()) {
                    Toast.makeText(this@MainActivity, "Inserisci l'indirizzo di Hermes", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                // riavvia il servizio per applicare le nuove impostazioni
                stopService(Intent(this@MainActivity, WakeWordService::class.java))
                askPermissionsThenStart()
                Toast.makeText(this@MainActivity, "Assistente avviato", Toast.LENGTH_SHORT).show()
            }
        }
        val stop = Button(this).apply {
            text = "Ferma"
            setOnClickListener { stopService(Intent(this@MainActivity, WakeWordService::class.java)) }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(TextView(this@MainActivity).apply { text = "Mio Assistente"; textSize = 24f })
            addView(TextView(this@MainActivity).apply {
                text = "Versione ${BuildConfig.VERSION_NAME}"; textSize = 13f
            })
            addView(label("Frase di attivazione")); addView(frase)
            addView(label("Indirizzo del PC con Hermes")); addView(url)
            addView(label("Password di Hermes")); addView(key)
            addView(label("Trascrizione con VoiceStudio sul PC")); addView(stt)
            addView(start); addView(stop)
            addView(diagnostics())
        }
        setContentView(ScrollView(this).apply { addView(layout) })

        // Aperta come assistente predefinito (tasto accensione lungo): avvia subito.
        if (intent?.action == Intent.ACTION_ASSIST && Settings.hermesUrl(this).isNotEmpty()) {
            askPermissionsThenStart()
        }
    }

    /** Mostra l'ultimo errore e il registro, con un pulsante per copiarli. */
    private fun diagnostics(): LinearLayout {
        val crash = java.io.File(filesDir, "ultimo_errore.txt")
        val log = java.io.File(filesDir, "registro.txt")
        val text = buildString {
            if (crash.exists()) append("⚠️ ULTIMO ERRORE:\n").append(crash.readText()).append("\n\n")
            if (log.exists()) append("REGISTRO:\n").append(log.readLines().takeLast(15).joinToString("\n"))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                this.text = "Diagnostica"; textSize = 15f; setPadding(0, 48, 0, 4)
            })
            addView(TextView(this@MainActivity).apply {
                this.text = text.ifBlank { "Nessun evento registrato finora." }
                textSize = 11f; setTextIsSelectable(true)
            })
            addView(Button(this@MainActivity).apply {
                this.text = "Copia diagnostica"
                setOnClickListener {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("diagnostica", text))
                    Toast.makeText(this@MainActivity, "Copiato: incollalo a Claude", Toast.LENGTH_SHORT).show()
                }
            })
            addView(Button(this@MainActivity).apply {
                this.text = "Cancella diagnostica"
                setOnClickListener { crash.delete(); log.delete(); recreate() }
            })
        }
    }

    private fun askPermissionsThenStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startAssistant() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAssistant()
        }
    }

    private fun startAssistant() {
        startForegroundService(Intent(this, WakeWordService::class.java))
    }
}
