package com.example.mioassistente

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Servizio sempre attivo:
 * 1. ascolta la TUA parola di attivazione (Vosk, offline, senza account)
 * 2. quando la sente, ascolta la tua frase (SpeechRecognizer di Android)
 * 3. la manda al TUO agente via HTTP
 * 4. legge ad alta voce la risposta e torna in ascolto
 */
class WakeWordService : Service() {

    private var model: Model? = null
    private var wakeListener: SpeechService? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val main = Handler(Looper.getMainLooper())
    private val wakePhrase by lazy { Settings.wakePhrase(this).lowercase().trim() }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        // Il callback può arrivare subito (anche prima che "tts" sia assegnato): lo gestiamo dopo.
        tts = TextToSpeech(this) { status ->
            main.post { onTtsInit(status) }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { main.post { startWakeWord() } }
            override fun onDone(id: String?) { main.post { startWakeWord() } }
        })
        loadModel(attempt = 1)
    }

    private var modelResetDone = false
    private val modelDir by lazy { File(filesDir, "vosk-model") }

    /** Il modello è completo solo se ci sono i file essenziali. */
    private fun modelLooksComplete(dir: File): Boolean =
        File(dir, "am/final.mdl").length() > 1_000_000 &&
            File(dir, "conf/model.conf").exists() &&
            (File(dir, "graph/HCLr.fst").exists() || File(dir, "graph/HCLG.fst").exists())

    /** Scarica (se serve) e carica il modello in un thread separato. Riprova da zero se è danneggiato. */
    private fun loadModel(attempt: Int) {
        updateNotification("Preparo il modello vocale…")
        Thread {
            try {
                val dir = modelDir
                App.log(this, "1. Servizio avviato (v${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}) tentativo $attempt")
                if (!File(dir, ".ok").exists() || !modelLooksComplete(dir)) {
                    App.log(this, "2. Modello assente o incompleto: inizio download")
                    dir.deleteRecursively()
                    val inAssets = !assets.list(Config.VOSK_MODEL_ASSET_DIR).isNullOrEmpty()
                    if (inAssets) copyAssetDir(Config.VOSK_MODEL_ASSET_DIR, dir)
                    else downloadModel(dir)
                    if (!modelLooksComplete(dir)) {
                        throw java.io.IOException("modello incompleto dopo il download: " +
                            (dir.list()?.joinToString() ?: "cartella vuota"))
                    }
                    File(dir, ".ok").writeText("ok")
                    App.log(this, "3. Modello scaricato ed estratto")
                }
                App.log(this, "4. Carico il modello in memoria…")
                model?.close()
                model = Model(dir.absolutePath)
                App.log(this, "5. Modello caricato")
                main.post { startWakeWord() }
            } catch (e: Throwable) {
                Log.e(TAG, "Errore caricamento modello", e)
                App.log(this, "Errore modello (tentativo $attempt): $e")
                modelDir.deleteRecursively()
                if (attempt < 3) {
                    main.post { updateNotification("Download non riuscito, riprovo…") }
                    main.postDelayed({ loadModel(attempt + 1) }, 5_000)
                } else {
                    main.post {
                        updateNotification("Impossibile preparare il modello: usa il Wi-Fi e premi di nuovo Salva e avvia")
                    }
                }
            }
        }.start()
    }

    /** Scarica lo zip del modello e lo estrae in [dest], togliendo la cartella principale. */
    private fun downloadModel(dest: File) {
        val conn = URL(Config.VOSK_MODEL_URL).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        val total = conn.contentLengthLong
        var read = 0L
        var lastPct = -1
        val counting = object : java.io.FilterInputStream(conn.inputStream) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0 && total > 0) {
                    read += n
                    val pct = (read * 100 / total).toInt()
                    if (pct != lastPct && pct % 5 == 0) {
                        lastPct = pct
                        main.post { updateNotification("Scarico il modello vocale… $pct%") }
                    }
                }
                return n
            }
        }
        if (conn.responseCode != 200) throw java.io.IOException("download: HTTP ${conn.responseCode}")
        java.util.zip.ZipInputStream(counting.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val rel = entry.name.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val out = File(dest, rel)
                if (!out.canonicalPath.startsWith(dest.canonicalPath)) continue
                if (entry.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        App.log(this, "Download: ${read / 1_000_000} MB su ${total / 1_000_000} MB")
        if (total > 0 && read < total) throw java.io.IOException("download interrotto ($read su $total byte)")
    }

    private fun onTtsInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            val r = try { tts?.setLanguage(Locale.ITALIAN) } catch (_: Throwable) { null }
            App.log(this, "Voce pronta (italiano: ${r != null && r >= 0})")
        } else {
            App.log(this, "Motore vocale non disponibile (stato $status): le risposte compariranno solo nella notifica")
        }
    }

    // ---------- 1. Parola di attivazione ----------
    private fun startWakeWord() {
        val m = model ?: return
        try {
            // Grammatica ristretta: Vosk sceglie solo tra la tua frase e "rumore",
            // così è veloce, leggero e poco soggetto a falsi allarmi.
            App.log(this, "6. Preparo il riconoscitore…")
            val rec = buildRecognizer(m)
            App.log(this, "7. Avvio microfono…")
            wakeListener = SpeechService(rec, 16000f).also {
                it.startListening(object : org.vosk.android.RecognitionListener {
                    override fun onPartialResult(hyp: String?) = check(hyp, "partial")
                    override fun onResult(hyp: String?) = check(hyp, "text")
                    override fun onFinalResult(hyp: String?) = check(hyp, "text")
                    override fun onError(e: Exception?) { Log.e(TAG, "Vosk", e); App.log(this@WakeWordService, "Errore Vosk: $e") }
                    override fun onTimeout() {}
                })
            }
            App.log(this, "8. In ascolto")
            updateNotification("In ascolto di \"$wakePhrase\"…")
        } catch (e: Throwable) {
            Log.e(TAG, "Errore avvio ascolto", e)
            App.log(this, "Errore ascolto: $e")
            if (e.message?.contains("recognizer", ignoreCase = true) == true && !modelResetDone) {
                // Il modello sul telefono è danneggiato: lo cancello e lo riscarico una volta.
                modelResetDone = true
                App.log(this, "Modello danneggiato: lo cancello e lo riscarico")
                model?.close(); model = null
                modelDir.deleteRecursively()
                loadModel(attempt = 1)
            } else {
                updateNotification("Errore: ${e.message}")
            }
        }
    }

    /** Normalizza: minuscolo, senza "h" (hermes = ermes, ehi = ei), spazi singoli. */
    private fun norm(t: String) = t.lowercase().replace("h", "").replace(Regex("[^a-zàèéìòù ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private val wakeNorm by lazy { norm(wakePhrase) }
    private val wakeKey by lazy { wakeNorm.split(" ").last() }

    /**
     * Se tutte le parole della frase sono nel vocabolario del modello usa una grammatica ristretta
     * (veloce, pochi falsi allarmi); altrimenti riconoscimento libero e confronto tollerante.
     */
    private fun buildRecognizer(m: Model): Recognizer {
        val variants = linkedSetOf(wakePhrase, wakePhrase.replace("h", ""), wakePhrase.split(" ").last(),
            wakePhrase.split(" ").last().replace("h", ""))
        val usable = variants.filter { v ->
            v.isNotBlank() && v.split(" ").all { w ->
                try {
                    // findWord può non esistere in tutte le versioni di Vosk: in quel caso
                    // si usa il riconoscimento libero.
                    val f = m.javaClass.methods.firstOrNull { it.name == "findWord" }
                    f != null && (f.invoke(m, w) as Int) >= 0
                } catch (_: Throwable) { false }
            }
        }
        App.log(this, "Frase: '$wakePhrase' - varianti nel vocabolario: $usable")
        if (usable.isNotEmpty()) {
            try {
                val g = JSONArray(); usable.forEach { g.put(it) }; g.put("[unk]")
                return Recognizer(m, 16000f, g.toString())
            } catch (e: Throwable) {
                App.log(this, "Grammatica non accettata ($e): passo al riconoscimento libero")
            }
        }
        return Recognizer(m, 16000f)
    }

    private fun check(hyp: String?, key: String) {
        val raw = try { hyp?.let { JSONObject(it).optString(key) } } catch (_: Throwable) { null } ?: return
        val text = norm(raw)
        if (text.isEmpty()) return
        val hit = text.contains(wakeNorm) || (wakeKey.length >= 4 && text.split(" ").contains(wakeKey))
        if (hit) { App.log(this, "Attivato da: '$raw'"); main.post { onWakeWord() } }
    }

    private fun onWakeWord() {
        if (wakeListener == null) return // già scattato
        stopWakeWord() // libera il microfono per il riconoscimento della frase
        updateNotification("Ti ascolto…")
        playEarcon() // piccolo segnale
        listenForCommand()
    }

    private fun stopWakeWord() {
        wakeListener?.stop()
        wakeListener?.shutdown()
        wakeListener = null
    }

    // ---------- 2. Ascolto della frase ----------
    private fun listenForCommand() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (text.isNullOrBlank()) startWakeWord() else askAgent(text)
                }
                override fun onError(error: Int) { startWakeWord() }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
        }
        recognizer?.startListening(intent)
    }

    // ---------- 3. Chiamata a Hermes (API compatibile OpenAI) ----------
    // Memoria della conversazione: gli ultimi scambi vengono rimandati a Hermes
    private val history = mutableListOf<JSONObject>()

    private fun askAgent(text: String) {
        updateNotification("Hai detto: $text")
        Thread {
            val reply = try {
                history += JSONObject().put("role", "user").put("content", text)
                while (history.size > 20) history.removeAt(0)

                val messages = JSONArray().put(
                    JSONObject().put("role", "system").put(
                        "content",
                        "Stai parlando a voce. Rispondi in italiano, in modo breve e naturale, " +
                            "senza elenchi, simboli o markdown: il testo verrà letto ad alta voce."
                    )
                )
                history.forEach { messages.put(it) }

                val conn = URL("${Settings.hermesUrl(this).trimEnd('/')}/v1/chat/completions")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 180_000 // Hermes può usare strumenti e metterci un po'
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer ${Settings.hermesKey(this)}")
                conn.doOutput = true
                val body = JSONObject()
                    .put("model", "hermes-agent")
                    .put("messages", messages)
                    .put("stream", false)
                conn.outputStream.use { it.write(body.toString().toByteArray()) }

                if (conn.responseCode == 401) {
                    "Hermes ha rifiutato la chiave. Controlla la password nell.app."
                } else {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val answer = json.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").optString("content").trim()
                    history += JSONObject().put("role", "assistant").put("content", answer)
                    answer.ifEmpty { "Non ho ricevuto risposta." }
                }
            } catch (e: java.net.SocketTimeoutException) {
                "Hermes ci sta mettendo troppo a rispondere."
            } catch (e: Throwable) {
                Log.e(TAG, "Errore Hermes", e)
                App.log(this, "Errore Hermes: $e")
                "Non riesco a raggiungere Hermes. Il computer è acceso e sulla stessa Wi-Fi?"
            }
            main.post { speak(reply) }
        }.start()
    }

    // ---------- 4. Risposta a voce ----------
    private fun speak(text: String) {
        updateNotification(text)
        val ok = ttsReady && try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply") == TextToSpeech.SUCCESS
        } catch (_: Throwable) { false }
        if (!ok) startWakeWord() // senza voce: risposta solo in notifica, poi torna in ascolto
    }

    // ---------- Utilità ----------
    private fun copyAssetDir(assetPath: String, dest: File) {
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
        } else {
            dest.mkdirs()
            for (c in children) copyAssetDir("$assetPath/$c", File(dest, c))
        }
    }

    private fun playEarcon() {
        try {
            android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80)
                .startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (_: Throwable) {}
    }

    // ---------- Notifica fissa (obbligatoria per ascoltare in background) ----------
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Assistente", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification("Avvio…")
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1, n)
            }
        } catch (e: Throwable) {
            App.log(this, "startForeground fallito: $e")
            throw e
        }
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL)
            .setContentTitle("Mio Assistente")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))
    }

    override fun onDestroy() {
        stopWakeWord()
        recognizer?.destroy()
        model?.close()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "MioAssistente"
        private const val CHANNEL = "assistente"
    }
}
