package com.example.mioassistente

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Riavvia l'assistente quando accendi il telefono. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Android 14+ può vietare di avviare il microfono all'accensione: in quel caso si ignora.
            try {
                if (Settings.hermesUrl(context).isNotEmpty())
                    context.startForegroundService(Intent(context, WakeWordService::class.java))
            } catch (e: Throwable) {
                App.log(context, "Avvio all'accensione non consentito: $e")
            }
        }
    }
}
