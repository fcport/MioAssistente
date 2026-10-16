package com.example.mioassistente

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** All'accensione del telefono. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Settings.hermesUrl(context).isEmpty()) return
        // Da Android 14 il microfono non può partire da solo all'accensione: chiedi un tocco.
        if (Build.VERSION.SDK_INT >= 34) { WakeWordService.notifyTapToStart(context); return }
        try {
            context.startForegroundService(Intent(context, WakeWordService::class.java))
        } catch (e: Throwable) {
            App.log(context, "Avvio all'accensione non consentito: $e")
            WakeWordService.notifyTapToStart(context)
        }
    }
}
