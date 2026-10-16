package com.example.mioassistente

/**
 * Valori predefiniti. Indirizzo e password di Hermes si inseriscono
 * direttamente nell'app, non serve modificare questo file.
 */
object Config {
    const val WAKE_PHRASE = "ehi hermes"

    // Il modello vocale italiano viene scaricato automaticamente al primo avvio.
    const val VOSK_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip"
    const val VOSK_MODEL_ASSET_DIR = "model-it"
}
