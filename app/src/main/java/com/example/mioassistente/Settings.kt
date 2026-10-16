package com.example.mioassistente

import android.content.Context

/** Impostazioni salvate sul telefono (inserite dalla schermata dell'app). */
object Settings {
    private const val PREFS = "impostazioni"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun wakePhrase(c: Context) =
        p(c).getString("frase", null)?.takeIf { it.isNotBlank() } ?: Config.WAKE_PHRASE

    fun hermesUrl(c: Context) = p(c).getString("url", "") ?: ""
    fun hermesKey(c: Context) = p(c).getString("key", "") ?: ""

    fun save(c: Context, frase: String, url: String, key: String) {
        var u = url.trim()
        if (u.isNotEmpty() && !u.startsWith("http")) u = "http://$u"
        // se manca la porta, usa quella predefinita di Hermes
        if (u.isNotEmpty() && Regex("^https?://[^/:]+/?$").matches(u)) u = u.trimEnd('/') + ":8642"
        p(c).edit()
            .putString("frase", frase.trim().lowercase())
            .putString("url", u)
            .putString("key", key.trim())
            .apply()
    }
}
