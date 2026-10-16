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

        val start = Button(this).apply {
            text = "Salva e avvia assistente"
            setOnClickListener {
                Settings.save(this@MainActivity, frase.text.toString(), url.text.toString(), key.text.toString())
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
            addView(label("Frase di attivazione")); addView(frase)
            addView(label("Indirizzo del PC con Hermes")); addView(url)
            addView(label("Password di Hermes")); addView(key)
            addView(start); addView(stop)
        }
        setContentView(ScrollView(this).apply { addView(layout) })

        // Aperta come assistente predefinito (tasto accensione lungo): avvia subito.
        if (intent?.action == Intent.ACTION_ASSIST && Settings.hermesUrl(this).isNotEmpty()) {
            askPermissionsThenStart()
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
