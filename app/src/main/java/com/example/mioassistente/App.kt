package com.example.mioassistente

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Salva su file qualsiasi errore che fa chiudere l'app, per mostrarlo alla riapertura. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(filesDir, "ultimo_errore.txt").writeText(
                    "Thread: ${thread.name}\n${sw.toString().take(4000)}"
                )
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, e)
        }
    }

    companion object {
        private val fmt = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.ITALIAN)

        @Synchronized
        fun log(ctx: android.content.Context, msg: String) {
            try {
                val f = File(ctx.filesDir, "registro.txt")
                // non far crescere il registro all'infinito
                if (f.length() > 200_000) f.writeText(f.readText().takeLast(100_000))
                f.appendText("${fmt.format(java.util.Date())}  $msg\n")
            } catch (_: Throwable) {}
        }
    }
}
