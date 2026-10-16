package com.example.mioassistente

/** Ponte verso whisper.cpp (trascrizione sul telefono, anche di parole inventate). */
object Whisper {
    val available: Boolean = try {
        System.loadLibrary("mioassistente_whisper"); true
    } catch (_: Throwable) { false }

    @JvmStatic external fun nativeInit(modelPath: String): Long
    @JvmStatic external fun nativeTranscribe(ctx: Long, audio: FloatArray, threads: Int, audioCtx: Int): String?
    @JvmStatic external fun nativeFree(ctx: Long)
}
