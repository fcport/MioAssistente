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
        fun log(ctx: android.content.Context, msg: String) {
            try {
                File(ctx.filesDir, "registro.txt").appendText("${java.util.Date()}  $msg\n")
            } catch (_: Throwable) {}
        }
    }
}
