package com.example.mioassistente

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Riavvia l'assistente quando accendi il telefono. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, WakeWordService::class.java))
        }
    }
}
