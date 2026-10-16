package com.example.mioassistente

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Servizio sempre attivo:
 * 1. ascolta la parola di attivazione (Vosk, offline)
 * 2. registra la domanda e la trascrive con Whisper sul telefono
 * 3. la manda a Hermes e legge la risposta frase per frase mentre arriva
 * 4. esegue le azioni sul telefono chieste da Hermes (con conferma per quelle delicate)
 * 5. resta in ascolto per una domanda successiva; "stop" interrompe in qualsiasi momento
 */
class WakeWordService : Service() {

    private var model: Model? = null
    private var wakeListener: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val main = Handler(Looper.getMainLooper())
    private val wakePhrase by lazy { Settings.wakePhrase(this).lowercase().trim() }
    private lateinit var actions: PhoneActions
    @Volatile private var destroyed = false
    private val whisperLock = Any()
    private val whisperLoading = java.util.concurrent.atomic.AtomicBoolean(false)
    private var tone: android.media.ToneGenerator? = null

    override fun onCreate() {
        super.onCreate()
        actions = PhoneActions(this)
        if (!startAsForeground()) {
            // es. dopo il riavvio Android 14+ non consente di accendere il microfono da solo
            notifyTapToStart()
            stopSelf()
            return
        }
        // Il callback può arrivare subito (anche prima che "tts" sia assegnato): lo gestiamo dopo.
        val googleTts = "com.google.android.tts"
        val hasGoogleTts = try { packageManager.getPackageInfo(googleTts, 0); true } catch (_: Throwable) { false }
        val ttsListener = TextToSpeech.OnInitListener { status -> main.post { onTtsInit(status) } }
        tts = if (hasGoogleTts) TextToSpeech(this, ttsListener, googleTts) else TextToSpeech(this, ttsListener)
        App.log(this, "Voce: ${if (hasGoogleTts) "Google" else "predefinita del telefono (Google TTS non installato)"}")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { main.post { onUtteranceEnd(id) } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { main.post { onUtteranceEnd(id) } }
            override fun onStop(id: String?, interrupted: Boolean) { main.post { onUtteranceEnd(id) } }
        })
        loadModel(attempt = 1)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // =====================================================================
    // Modello Vosk (parola di attivazione e fine frase)
    // =====================================================================

    private var modelResetDone = false
    private val modelDir by lazy { File(filesDir, "vosk-model") }

    private fun modelLooksComplete(dir: File): Boolean =
        File(dir, "am/final.mdl").length() > 1_000_000 &&
            File(dir, "conf/model.conf").exists() &&
            (File(dir, "graph/HCLr.fst").exists() || File(dir, "graph/HCLG.fst").exists())

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
                    if (inAssets) copyAssetDir(Config.VOSK_MODEL_ASSET_DIR, dir) else downloadModel(dir)
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
                if (destroyed) return@Thread
                main.post { startWakeWord() }
                prepareWhisper()
            } catch (e: Throwable) {
                Log.e(TAG, "Errore caricamento modello", e)
                App.log(this, "Errore modello (tentativo $attempt): $e")
                modelDir.deleteRecursively()
                if (attempt < 3) {
                    main.post { updateNotification("Download non riuscito, riprovo…") }
                    main.postDelayed({ if (!destroyed) loadModel(attempt + 1) }, 5_000)
                } else {
                    main.post { updateNotification("Impossibile preparare il modello: usa il Wi-Fi e premi di nuovo Salva e avvia") }
                }
            }
        }.start()
    }

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

    // =====================================================================
    // 1. Parola di attivazione
    // =====================================================================

    private var wakeRecognizer: Recognizer? = null

    private fun startWakeWord() {
        if (destroyed) return
        val m = model ?: return
        if (wakeListener != null) return
        conversationActive = false
        var rec: Recognizer? = null
        try {
            rec = buildRecognizer(m)
            wakeRecognizer = rec
            wakeListener = SpeechService(rec, 16000f).also {
                it.startListening(object : org.vosk.android.RecognitionListener {
                    override fun onPartialResult(hyp: String?) = check(hyp, "partial")
                    override fun onResult(hyp: String?) = check(hyp, "text")
                    override fun onFinalResult(hyp: String?) = check(hyp, "text")
                    override fun onError(e: Exception?) {
                        Log.e(TAG, "Vosk", e); App.log(this@WakeWordService, "Errore Vosk: $e")
                        main.post {
                            stopWakeWord()
                            main.postDelayed({ if (!conversationActive) startWakeWord() }, 2_000)
                        }
                    }
                    override fun onTimeout() {}
                })
            }
            updateNotification("In ascolto di \"$wakePhrase\"…")
        } catch (e: Throwable) {
            Log.e(TAG, "Errore avvio ascolto", e)
            App.log(this, "Errore ascolto: $e")
            if (e.message?.contains("recognizer", ignoreCase = true) == true && !modelResetDone) {
                modelResetDone = true
                App.log(this, "Modello danneggiato: lo cancello e lo riscarico")
                model?.close(); model = null
                modelDir.deleteRecursively()
                loadModel(attempt = 1)
            } else {
                wakeListener = null
                wakeRecognizer = null
                try { rec?.close() } catch (_: Throwable) {}
                updateNotification("Microfono occupato, riprovo…")
                main.postDelayed({ if (!conversationActive) startWakeWord() }, 3_000)
            }
        }
    }

    /** Normalizza: minuscolo, senza "h" (hermes = ermes, ehi = ei), spazi singoli. */
    private fun norm(t: String) = t.lowercase().replace("h", "").replace(Regex("[^a-zàèéìòù ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private val wakeNorm by lazy { norm(wakePhrase) }
    private val wakeKey by lazy { wakeNorm.split(" ").last() }

    private fun inVocabulary(m: Model, word: String): Boolean = try {
        val f = m.javaClass.methods.firstOrNull { it.name == "findWord" }
        f != null && (f.invoke(m, word) as Int) >= 0
    } catch (_: Throwable) { false }

    private fun buildRecognizer(m: Model): Recognizer {
        val variants = linkedSetOf(wakePhrase, wakePhrase.replace("h", ""), wakePhrase.split(" ").last(),
            wakePhrase.split(" ").last().replace("h", ""))
        val usable = variants.filter { v -> v.isNotBlank() && v.split(" ").all { inVocabulary(m, it) } }
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
        val text = norm(parse(hyp, key))
        if (text.isEmpty()) return
        val hit = text.contains(wakeNorm) || (wakeKey.length >= 4 && text.split(" ").contains(wakeKey))
        if (hit) { App.log(this, "Attivato da: '$text'"); main.post { onWakeWord() } }
    }

    private fun onWakeWord() {
        if (wakeListener == null) return // già scattato
        stopWakeWord()
        conversationActive = true
        actionDepth = 0
        playEarcon()
        listenForCommand(followUp = false)
    }

    private fun stopWakeWord() {
        try { wakeListener?.stop() } catch (_: Throwable) {}
        try { wakeListener?.shutdown() } catch (_: Throwable) {}
        wakeListener = null
        try { wakeRecognizer?.close() } catch (_: Throwable) {}
        wakeRecognizer = null
    }

    // =====================================================================
    // 2. Ascolto della domanda (registrazione + Whisper)
    // =====================================================================

    private var activeCapture: java.util.concurrent.atomic.AtomicBoolean? = null
    private var conversationActive = false

    /** Ascolta una domanda. Se non dici nulla, torna in attesa della parola di attivazione. */
    private fun listenForCommand(followUp: Boolean) {
        if (destroyed) return
        updateNotification(if (followUp) "Ti ascolto (puoi continuare)…" else "Ti ascolto…")
        captureUtterance(if (followUp) 5_000 else 6_000) { text ->
            if (text.isBlank()) {
                App.log(this, if (followUp) "Fine conversazione" else "Nessuna domanda sentita")
                startWakeWord()
            } else {
                actionDepth = 0
                askAgent(text, fromUser = true)
            }
        }
    }

    /** Registra finché parli, trascrive (Whisper se pronto, altrimenti Vosk) e restituisce il testo. */
    private fun captureUtterance(noSpeechMs: Long, done: (String) -> Unit) {
        if (destroyed) return
        val m = model ?: run { done(""); return }
        activeCapture?.set(false) // ferma un'eventuale registrazione precedente
        val flag = java.util.concurrent.atomic.AtomicBoolean(true)
        activeCapture = flag
        Thread {
            var rec = Recording("", ByteArray(0), false)
            try { rec = recordCommand(m, noSpeechMs, flag) } catch (e: Throwable) {
                App.log(this, "Errore registrazione: $e")
            }
            var text = rec.voskText
            if (rec.heardSpeech && flag.get() && rec.pcm.size > 8000) {
                main.post { updateNotification("Trascrivo…") }
                val w = try {
                    synchronized(whisperLock) {
                        val c = whisperCtx
                        if (c == 0L || destroyed) null else transcribeLocal(c, rec.pcm)
                    }
                } catch (e: Throwable) {
                    App.log(this, "Errore Whisper: $e"); null
                }
                if (!w.isNullOrBlank()) text = w
            }
            if (!rec.heardSpeech) text = ""
            // togli l'eventuale frase di attivazione ripetuta all'inizio
            val words = text.trim().split(Regex("\\s+"))
            val n = wakePhrase.split(" ").size
            if (words.size >= n && norm(words.take(n).joinToString(" ")) == norm(wakePhrase)) {
                text = words.drop(n).joinToString(" ")
            }
            text = text.trim().trim(',', '.', ' ')
            main.post {
                // solo la registrazione più recente conta
                if (!destroyed && activeCapture === flag && flag.get()) {
                    activeCapture = null
                    done(text)
                }
            }
        }.start()
    }

    private class Recording(val voskText: String, val pcm: ByteArray, val heardSpeech: Boolean)

    /** Registra finché parli (si ferma dopo ~1,5 s di silenzio). */
    private fun recordCommand(m: Model, noSpeechMs: Long, flag: java.util.concurrent.atomic.AtomicBoolean): Recording {
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
        try {
            ar.startRecording()
            while (flag.get()) {
                val n = ar.read(buf, 0, buf.size)
                if (n < 0) { App.log(this, "Errore microfono ($n)"); break }
                if (n == 0) { if (System.currentTimeMillis() - start > 20_000) break; continue }
                out.write(buf, 0, n)
                val now = System.currentTimeMillis()
                if (rms(buf, n) > 900) { lastSpeech = now; heardAny = true }
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
                if (!heardAny && elapsed > noSpeechMs) break
                if (heardAny && now - lastSpeech > 1_500) break
                if (elapsed > 20_000) break
            }
            text.append(' ').append(parse(rec.finalResult, "text"))
        } finally {
            try { ar.stop() } catch (_: Throwable) {}
            ar.release()
            rec.close()
        }
        val t = text.toString().trim()
        App.log(this, "Modello telefono ha capito: '$t'")
        return Recording(t, out.toByteArray(), heardAny || t.isNotBlank())
    }

    private fun rms(buf: ByteArray, n: Int): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toDouble()
            sum += v * v; i += 2
        }
        return Math.sqrt(sum / (n / 2).coerceAtLeast(1))
    }

    // =====================================================================
    // Whisper sul telefono
    // =====================================================================

    @Volatile private var whisperCtx = 0L
    private val whisperFile by lazy { File(filesDir, "whisper/" + Config.WHISPER_MODEL_NAME) }

    private fun prepareWhisper() {
        if (whisperCtx != 0L) return
        if (!Whisper.available) { App.log(this, "Whisper: libreria non disponibile"); return }
        if (!whisperLoading.compareAndSet(false, true)) return
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
                                    main.post {
                                        if (!conversationActive && wakeListener != null)
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
                synchronized(whisperLock) {
                    if (destroyed) { Whisper.nativeFree(ctx); return@Thread }
                    whisperCtx = ctx
                }
                App.log(this, "W3. Whisper pronto (${System.currentTimeMillis() - t0} ms)")
                main.post { if (!conversationActive && wakeListener != null) updateNotification("In ascolto di \"$wakePhrase\"…") }
            } catch (e: Throwable) {
                App.log(this, "Whisper non disponibile per ora ($e): uso il modello base")
            } finally {
                whisperLoading.set(false)
            }
        }.start()
    }

    private fun transcribeLocal(ctx: Long, pcm: ByteArray): String? {
        val n = pcm.size / 2
        val audio = FloatArray(n)
        val bb = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) audio[i] = bb.getShort(i * 2) / 32768f
        val seconds = n / 16000.0
        val audioCtx = minOf(1500, (seconds * 50).toInt() + 128)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val t0 = System.currentTimeMillis()
        val raw = Whisper.nativeTranscribe(ctx, audio, threads, audioCtx) ?: return null
        var text = raw.replace(Regex("[\\[(][^\\])]*[\\])]"), " ").replace(Regex("\\s+"), " ").trim()
        // frasi che Whisper "inventa" sul silenzio
        val lower = text.lowercase()
        if (lower.contains("amara.org") || lower.contains("sottotitoli") || lower == "grazie per la visione." ||
            lower == "grazie a tutti." || lower == "grazie.") text = ""
        App.log(this, "Whisper ha capito: '$text' (${System.currentTimeMillis() - t0} ms)")
        return text
    }

    private fun parse(hyp: String?, key: String): String =
        try { hyp?.let { JSONObject(it).optString(key) } ?: "" } catch (_: Throwable) { "" }

    // =====================================================================
    // 3. Hermes: risposta in streaming, letta frase per frase
    // =====================================================================

    private val history = mutableListOf<JSONObject>()
    private var turnId = 0
    private var hermes: HermesClient? = null
    @Volatile private var streamDone = true
    private var turnFinishing = false
    private val pendingActions = mutableListOf<JSONObject>()
    private val pendingSpeech = AtomicInteger(0)
    private var actionDepth = 0

    private fun askAgent(text: String, fromUser: Boolean) {
        if (destroyed) return
        turnId++
        val myTurn = turnId
        streamDone = false
        turnFinishing = false
        pendingActions.clear()
        pendingSpeech.set(0)
        if (fromUser) {
            updateNotification("Hai detto: $text")
            App.log(this, "Hai detto: $text")
        } else {
            updateNotification("Elaboro…")
            App.log(this, "Invio a Hermes: ${text.take(200)}")
        }
        history += JSONObject().put("role", "user").put("content", text)
        while (history.size > 24) history.removeAt(0)
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", actions.systemPrompt()))
        history.forEach { messages.put(it) }

        val client = HermesClient(Settings.hermesUrl(this), Settings.hermesKey(this))
        hermes = client
        startStopListener()
        Thread {
            val seg = Segmenter()
            val full = StringBuilder()
            var error: String? = null
            try {
                client.stream(messages) { delta ->
                    full.append(delta)
                    val (sentences, acts) = seg.feed(delta)
                    if (sentences.isNotEmpty() || acts.isNotEmpty()) main.post {
                        if (myTurn == turnId) { sentences.forEach { speakPart(it) }; pendingActions += acts }
                    }
                }
                val (sentences, acts) = seg.flush()
                main.post { if (myTurn == turnId) { sentences.forEach { speakPart(it) }; pendingActions += acts } }
            } catch (e: HermesClient.CancelledException) {
                return@Thread
            } catch (e: HermesClient.AuthException) {
                error = "Hermes ha rifiutato la password. Controllala nell'app."
            } catch (e: java.net.SocketTimeoutException) {
                error = "Hermes ci sta mettendo troppo a rispondere."
            } catch (e: Throwable) {
                App.log(this, "Errore Hermes: $e")
                error = "Non riesco a raggiungere Hermes. Il computer è acceso?"
            }
            val answer = full.toString().trim()
            val err = error
            App.log(this, "Risposta: ${answer.take(200)}")
            main.post {
                if (myTurn != turnId) return@post
                if (err != null) speakPart(err)
                else if (answer.isEmpty()) speakPart("Non ho ricevuto risposta.")
                if (answer.isNotEmpty()) {
                    history += JSONObject().put("role", "assistant").put("content", answer)
                }
                streamDone = true
                maybeFinishTurn()
                startSpeechWatchdog(myTurn)
            }
        }.start()
    }

    /** Se il motore vocale "perde" una frase senza avvisare, non restare bloccato. */
    private fun startSpeechWatchdog(myTurn: Int) {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (destroyed || myTurn != turnId || turnFinishing) return
                if (tts?.isSpeaking != true) {
                    App.log(this@WakeWordService, "Voce bloccata: riparto")
                    pendingSpeech.set(0); maybeFinishTurn()
                } else main.postDelayed(this, 3_000)
            }
        }, 3_000)
    }

    // =====================================================================
    // 4. Voce
    // =====================================================================

    private var utteranceSeq = 0
    private val afterUtterance = HashMap<String, () -> Unit>()

    /** Legge un pezzo della risposta (in coda dopo i precedenti). */
    private fun speakPart(text: String) {
        updateNotification(text)
        if (!ttsReady) return
        val id = "s${turnId}_${utteranceSeq++}"
        pendingSpeech.incrementAndGet()
        val ok = try { tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS } catch (_: Throwable) { false }
        if (!ok) pendingSpeech.decrementAndGet()
    }

    /** Dice una frase e poi esegue [then]. */
    private fun speakThen(text: String, then: () -> Unit) {
        updateNotification(text)
        if (!ttsReady) { main.post(then); return }
        val id = "x${turnId}_${utteranceSeq++}"
        afterUtterance[id] = then
        val ok = try { tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS } catch (_: Throwable) { false }
        if (!ok) { afterUtterance.remove(id); main.post(then) }
    }

    private fun onUtteranceEnd(id: String?) {
        id ?: return
        if (!id.startsWith("s$turnId" + "_") && !id.startsWith("x$turnId" + "_")) return // turno vecchio
        if (id.startsWith("s")) {
            if (pendingSpeech.decrementAndGet() < 0) pendingSpeech.set(0)
            maybeFinishTurn()
        } else {
            afterUtterance.remove(id)?.invoke()
        }
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

    // =====================================================================
    // 5. Fine turno: azioni, conferme, domanda successiva
    // =====================================================================

    private fun maybeFinishTurn() {
        if (!streamDone || pendingSpeech.get() > 0 || turnFinishing) return
        turnFinishing = true
        stopStopListener()
        val list = ArrayList(pendingActions)
        pendingActions.clear()
        runActions(list, mutableListOf())
    }

    private fun runActions(list: MutableList<JSONObject>, results: MutableList<String>) {
        val myTurn = turnId
        if (list.isEmpty()) {
            if (results.isNotEmpty() && actionDepth < 3) {
                actionDepth++
                askAgent(results.joinToString("\n"), fromUser = false)
            } else {
                followUp()
            }
            return
        }
        val a = list.removeAt(0)
        val exec = {
            Thread {
                val r = actions.execute(a)
                main.post {
                    if (myTurn != turnId) return@post
                    r.data?.let { results += "[RISULTATO ${a.optString("tipo")}] $it" }
                    if (r.spoken != null) speakThen(r.spoken) { runActions(list, results) }
                    else runActions(list, results)
                }
            }.start()
        }
        if (actions.needsConfirmation(a)) {
            // trova prima il contatto esatto, così la domanda dice chi verrà davvero chiamato
            Thread {
                val problem = actions.prepare(a)
                main.post {
                    if (myTurn != turnId) return@post
                    if (problem != null) speakThen(problem) { runActions(list, results) }
                    else askConfirmation(a, myTurn, exec) { runActions(list, results) }
                }
            }.start()
        } else {
            exec()
        }
    }

    private fun askConfirmation(a: JSONObject, myTurn: Int, exec: () -> Unit, skip: () -> Unit) {
        run {
            speakThen(actions.confirmationQuestion(a)) {
                playEarcon()
                updateNotification("Rispondi sì o no…")
                captureUtterance(5_000) { answer ->
                    if (myTurn != turnId) return@captureUtterance
                    App.log(this, "Conferma: '$answer'")
                    if (isYes(answer)) exec()
                    else speakThen("Ok, annullato.") { skip() }
                }
            }
        }
    }

    private fun isYes(t: String): Boolean {
        val n = t.lowercase().replace(Regex("[^a-zàèéìòù ]"), " ").replace(Regex("\\s+"), " ").trim()
        val w = n.split(" ").filter { it.isNotEmpty() }.toSet()
        val negative = setOf("no", "non", "annulla", "aspetta", "ferma", "fermati", "stop", "basta", "sbagliato", "nessuno")
        if (w.isEmpty() || w.any { it in negative }) return false
        val positive = setOf("si", "sì", "certo", "ok", "okay", "conferma", "confermo", "vai", "esatto", "procedi", "sicuro", "fallo")
        return w.any { it in positive } || n.contains("va bene")
    }

    /** Dopo la risposta resta in ascolto qualche secondo: puoi continuare senza ridire la parola di attivazione. */
    private fun followUp() {
        if (!conversationActive) { startWakeWord(); return }
        playEarcon()
        listenForCommand(followUp = true)
    }

    // =====================================================================
    // "Stop": interrompe Hermes e la voce in qualsiasi momento
    // =====================================================================

    @Volatile private var stopRunning = false
    private var stopThread: Thread? = null

    private fun startStopListener() {
        val m = model ?: return
        if (stopThread != null) return
        val words = listOf("stop", "fermati").filter { inVocabulary(m, it) }
        if (words.isEmpty()) return
        stopRunning = true
        val myTurn = turnId
        stopThread = Thread {
            var ar: android.media.AudioRecord? = null
            var rec: Recognizer? = null
            try {
                val g = JSONArray(); words.forEach { g.put(it) }; g.put("[unk]")
                rec = Recognizer(m, 16000f, g.toString())
                val min = android.media.AudioRecord.getMinBufferSize(16000,
                    android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
                @Suppress("MissingPermission")
                val r = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
                    android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min, 8192) * 2)
                ar = r
                r.startRecording()
                val buf = ByteArray(3200)
                while (stopRunning) {
                    val n = r.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n == 0) continue
                    if (!rec.acceptWaveForm(buf, n)) continue
                    // solo un risultato finale fatto esattamente della parola di stop
                    val heard = parse(rec.result, "text").trim()
                    if (heard in words) {
                        App.log(this, "Interrotto con \"$heard\"")
                        main.post { if (myTurn == turnId && !turnFinishing && !destroyed) onBargeIn() }
                        break
                    }
                }
            } catch (e: Throwable) {
                App.log(this, "Ascolto dello stop non disponibile: $e")
            } finally {
                try { ar?.stop() } catch (_: Throwable) {}
                ar?.release()
                rec?.close()
            }
        }.also { it.start() }
    }

    private fun stopStopListener() {
        stopRunning = false
        val t = stopThread
        stopThread = null
        if (t != null && t !== Thread.currentThread()) try { t.join(800) } catch (_: Throwable) {}
    }

    private fun onBargeIn() {
        turnId++ // invalida tutto quello che appartiene al turno interrotto
        hermes?.cancel()
        try { tts?.stop() } catch (_: Throwable) {}
        pendingSpeech.set(0)
        pendingActions.clear()
        afterUtterance.clear()
        streamDone = true
        turnFinishing = true
        stopStopListener()
        conversationActive = true
        playEarcon()
        listenForCommand(followUp = true)
    }

    // =====================================================================
    // Utilità
    // =====================================================================

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
            val t = tone ?: android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80).also { tone = it }
            t.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (_: Throwable) {}
    }

    private fun startAsForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Assistente", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification("Avvio…")
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                // la posizione si può leggere in background solo se dichiarata (e concessa) all'avvio
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                ) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                startForeground(1, n, types)
            } else {
                startForeground(1, n)
            }
            return true
        } catch (e: Throwable) {
            App.log(this, "Avvio in background non consentito da Android: $e")
            return false
        }
    }

    private fun notifyTapToStart() = notifyTapToStart(this)

    private fun buildNotification(text: String): Notification {
        val open = android.app.PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Mio Assistente")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        turnId++
        hermes?.cancel()
        activeCapture?.set(false)
        stopStopListener()
        stopWakeWord()
        synchronized(whisperLock) {
            if (whisperCtx != 0L) { Whisper.nativeFree(whisperCtx); whisperCtx = 0L }
        }
        model?.close(); model = null
        tts?.shutdown()
        try { tone?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "MioAssistente"
        private const val CHANNEL = "assistente"

        /** Notifica "tocca per riattivare" (Android non permette di accendere il microfono da solo). */
        fun notifyTapToStart(ctx: android.content.Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Assistente", NotificationManager.IMPORTANCE_LOW))
            val i = Intent(ctx, MainActivity::class.java).putExtra("avvia", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = android.app.PendingIntent.getActivity(ctx, 1, i,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(2, Notification.Builder(ctx, CHANNEL)
                .setContentTitle("Mio Assistente è in pausa")
                .setContentText("Tocca per riattivarlo")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build())
        }
    }
}
