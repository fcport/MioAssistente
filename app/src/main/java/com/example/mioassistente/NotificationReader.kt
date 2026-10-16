package com.example.mioassistente

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Legge le notifiche del telefono (se l'utente lo consente) per poterle riassumere a Hermes. */
class NotificationReader : NotificationListenerService() {

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        describe(this, sbn)?.let { line ->
            synchronized(recent) {
                recent.removeAll { it == line }
                recent.addLast(line)
                while (recent.size > 30) recent.removeFirst()
            }
        }
    }

    companion object {
        @Volatile private var instance: NotificationReader? = null
        private val recent = ArrayDeque<String>()

        private fun describe(ctx: Context, sbn: StatusBarNotification): String? {
            val ex = sbn.notification.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
            if (title.isBlank() && text.isBlank()) return null
            val app = try {
                ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
            } catch (_: Throwable) { sbn.packageName }
            val time = SimpleDateFormat("HH:mm", Locale.ITALIAN).format(Date(sbn.postTime))
            return "$time · $app · $title: ${text.take(300)}"
        }

        fun snapshot(ctx: Context): String {
            val svc = instance ?: return "Accesso alle notifiche non concesso: l'utente deve attivarlo nell'app (Configura telefono)."
            val active = try {
                svc.activeNotifications.filter { it.packageName != ctx.packageName }
                    .sortedByDescending { it.postTime }.mapNotNull { describe(ctx, it) }
            } catch (_: Throwable) { emptyList() }
            val older = synchronized(recent) { recent.toList().reversed() }
            val all = (active + older).distinct().take(25)
            return if (all.isEmpty()) "Nessuna notifica recente." else all.joinToString("\n")
        }
    }
}
