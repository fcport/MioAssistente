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
        val googleTts = "com.google.android.tts"
        val hasGoogleTts = try { packageManager.getPackageInfo(googleTts, 0); true } catch (_: Throwable) { false }
        val ttsListener = TextToSpeech.OnInitListener { status -> main.post { onTtsInit(status) } }
        tts = if (hasGoogleTts) TextToSpeech(this, ttsListener, googleTts) else TextToSpeech(this, ttsListener)
        App.log(this, "Voce: ${if (hasGoogleTts) "Google" else "predefinita del telefono (Google TTS non installato)"}")
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
                prepareWhisper()
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
    // ---------- 2. Ascolto della domanda ----------
    // Registra l'audio: il modello Vosk sul telefono decide quando hai finito di parlare e fa da riserva;
    // la domanda viene poi trascritta da Whisper sul telefono (capisce anche parole inventate).
    @Volatile private var recording = false

    private fun listenForCommand() {
        val m = model ?: run { startWakeWord(); return }
        recording = true
        Thread {
            var voskText = ""
            var pcm = ByteArray(0)
            try {
                val r = recordCommand(m)
                voskText = r.first; pcm = r.second
            } catch (e: Throwable) {
                App.log(this, "Errore registrazione domanda: $e")
            }
            recording = false
            var text = voskText
            val ctx = whisperCtx
            if (ctx != 0L && pcm.size > 8000) { // almeno un quarto di secondo di audio
                main.post { updateNotification("Trascrivo…") }
                val w = try { transcribeLocal(ctx, pcm) } catch (e: Throwable) {
                    App.log(this, "Errore Whisper: $e"); null
                }
                if (!w.isNullOrBlank()) text = w
            }
            // togli l'eventuale frase di attivazione ripetuta all'inizio
            val words = text.trim().split(Regex("\\s+"))
            val n = wakePhrase.split(" ").size
            if (words.size >= n && norm(words.take(n).joinToString(" ")) == norm(wakePhrase)) {
                text = words.drop(n).joinToString(" ")
            }
            text = text.trim().trim(',', '.', ' ')
            main.post {
                if (text.isBlank()) { App.log(this, "Nessuna domanda sentita"); startWakeWord() }
                else askAgent(text)
            }
        }.start()
    }

    /** Registra finché parli (si ferma dopo ~1,5 s di silenzio). Ritorna testo Vosk + audio PCM 16 kHz. */
    private fun recordCommand(m: Model): Pair<String, ByteArray> {
        val rate = 16000
        val min = android.media.AudioRecord.getMinBufferSize(rate,
            android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission")
        val ar = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, 8192) * 2)
        val rec = Recognizer(m, rate.toFloat())
        val out = java.io.ByteArrayOutputStream()
        val text = StringBuilder()
        val buf = ByteArray(3200) // 100 ms
        val start = System.currentTimeMillis()
        var lastSpeech = 0L
        var heardAny = false
        var lastPartial = ""
        ar.startRecording()
        try {
            while (recording) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) continue
                out.write(buf, 0, n)
                val now = System.currentTimeMillis()
                // volume: se c'è voce, aggiorna l'ultimo istante di parlato
                var sum = 0.0
                var i = 0
                while (i + 1 < n) {
                    val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toDouble()
                    sum += v * v; i += 2
                }
                val rms = Math.sqrt(sum / (n / 2).coerceAtLeast(1))
                if (rms > 900) { lastSpeech = now; heardAny = true }
                if (rec.acceptWaveForm(buf, n)) {
                    val t = parse(rec.result, "text")
                    if (t.isNotBlank()) { text.append(' ').append(t); lastSpeech = now; heardAny = true }
                } else {
                    val p = parse(rec.partialResult, "partial")
                    if (p.isNotBlank() && p != lastPartial) {
                        lastPartial = p; lastSpeech = now; heardAny = true
                        val shown = (text.toString() + " " + p).trim()
                        main.post { updateNotification("… $shown") }
                    }
                }
                val elapsed = now - start
                if (!heardAny && elapsed > 6_000) break          // non hai detto nulla
                if (heardAny && now - lastSpeech > 1_500) break  // hai finito di parlare
                if (elapsed > 20_000) break                       // massimo 20 secondi
            }
        } finally {
            try { ar.stop() } catch (_: Throwable) {}
            ar.release()
        }
        text.append(' ').append(parse(rec.finalResult, "text"))
        rec.close()
        val t = text.toString().trim()
        App.log(this, "Modello telefono ha capito: '$t'")
        return t to out.toByteArray()
    }

    // ---------- Whisper sul telefono ----------
    @Volatile private var whisperCtx = 0L
    private val whisperFile by lazy { File(filesDir, "whisper/" + Config.WHISPER_MODEL_NAME) }

    /** Scarica (la prima volta) e carica il modello Whisper. Finché non è pronto si usa Vosk. */
    private fun prepareWhisper() {
        if (whisperCtx != 0L) return
        if (!Whisper.available) { App.log(this, "Whisper: libreria non disponibile"); return }
        Thread {
            try {
                val f = whisperFile
                if (!File(f.parentFile, ".ok").exists() || f.length() < Config.WHISPER_MODEL_MIN_BYTES) {
                    App.log(this, "W1. Scarico il modello Whisper (~190 MB)…")
                    f.parentFile?.deleteRecursively(); f.parentFile?.mkdirs()
                    val tmp = File(f.parentFile, "download.tmp")
                    val conn = URL(Config.WHISPER_MODEL_URL).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 15_000; conn.readTimeout = 60_000
                    if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
                    val total = conn.contentLengthLong
                    var read = 0L; var last = -1
                    conn.inputStream.use { input -> tmp.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); read += n
                            if (total > 0) {
                                val pct = (read * 100 / total).toInt()
                                if (pct != last && pct % 10 == 0) {
                                    last = pct
                                    if (!recording && wakeListener != null) main.post {
                                        updateNotification("In ascolto di \"$wakePhrase\"… (preparo il riconoscimento avanzato $pct%)")
                                    }
                                }
                            }
                        }
                    } }
                    if (total > 0 && read < total) throw java.io.IOException("download interrotto ($read su $total)")
                    if (!tmp.renameTo(f)) throw java.io.IOException("impossibile salvare il modello")
                    File(f.parentFile, ".ok").writeText("ok")
                    App.log(this, "W2. Modello Whisper scaricato (${f.length() / 1_000_000} MB)")
                }
                val t0 = System.currentTimeMillis()
                val ctx = Whisper.nativeInit(f.absolutePath)
                if (ctx == 0L) {
                    App.log(this, "Whisper: modello non valido, lo riscarico al prossimo avvio")
                    f.parentFile?.deleteRecursively()
                    return@Thread
                }
                whisperCtx = ctx
                App.log(this, "W3. Whisper pronto (${System.currentTimeMillis() - t0} ms)")
                main.post { if (!recording && wakeListener != null) updateNotification("In ascolto di \"$wakePhrase\"…") }
            } catch (e: Throwable) {
                App.log(this, "Whisper non disponibile per ora ($e): uso il modello base")
            }
        }.start()
    }

    private fun transcribeLocal(ctx: Long, pcm: ByteArray): String? {
        val n = pcm.size / 2
        val audio = FloatArray(n)
        val bb = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) audio[i] = bb.getShort(i * 2) / 32768f
        // contesto audio proporzionale alla durata: molto più veloce per frasi brevi
        val seconds = n / 16000.0
        val audioCtx = minOf(1500, (seconds * 50).toInt() + 128)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val t0 = System.currentTimeMillis()
        val raw = Whisper.nativeTranscribe(ctx, audio, threads, audioCtx) ?: return null
        // togli annotazioni tipo [Musica] o (risate) che Whisper aggiunge sul silenzio
        val text = raw.replace(Regex("[\\[(][^\\])]*[\\])]"), " ").replace(Regex("\\s+"), " ").trim()
        App.log(this, "Whisper ha capito: '$text' (${System.currentTimeMillis() - t0} ms)")
        return text
    }

    private fun parse(hyp: String?, key: String): String =
        try { hyp?.let { JSONObject(it).optString(key) } ?: "" } catch (_: Throwable) { "" }

    /** Usa il riconoscimento vocale di Google se presente (quello Xiaomi può essere in cinese). */
    private fun createItalianRecognizer(): SpeechRecognizer {
        val services = packageManager.queryIntentServices(
            Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0
        ).map { android.content.ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        val google = services.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            ?: services.firstOrNull { it.packageName.startsWith("com.google") }
        App.log(this, "Riconoscitori disponibili: ${services.joinToString { it.packageName }} -> uso ${google?.packageName ?: "predefinito"}")
        return if (google != null) SpeechRecognizer.createSpeechRecognizer(this, google)
        else SpeechRecognizer.createSpeechRecognizer(this)
    }

    // ---------- 3. Chiamata a Hermes (API compatibile OpenAI) ----------
    // Memoria della conversazione: gli ultimi scambi vengono rimandati a Hermes
    private val history = mutableListOf<JSONObject>()

    private fun askAgent(text: String) {
        updateNotification("Hai detto: $text")
        App.log(this, "Hai detto: $text")
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
        App.log(this, "Risposta: ${text.take(120)}")
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
        recording = false
        recognizer?.destroy()
        if (whisperCtx != 0L) { Whisper.nativeFree(whisperCtx); whisperCtx = 0L }
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
