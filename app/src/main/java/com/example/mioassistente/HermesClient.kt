package com.example.mioassistente

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Chiamata a Hermes (API compatibile OpenAI) con risposta in streaming, interrompibile. */
class HermesClient(private val baseUrl: String, private val key: String) {

    class AuthException : IOException("chiave rifiutata")
    class CancelledException : IOException("annullato")

    @Volatile private var conn: HttpURLConnection? = null
    @Volatile var cancelled = false
        private set

    fun cancel() {
        cancelled = true
        try { conn?.disconnect() } catch (_: Throwable) {}
    }

    /** Invia i messaggi e chiama [onDelta] per ogni pezzo di testo appena arriva. */
    fun stream(messages: JSONArray, onDelta: (String) -> Unit) {
        val c = URL("${baseUrl.trimEnd('/')}/v1/chat/completions").openConnection() as HttpURLConnection
        conn = c
        if (cancelled) throw CancelledException()
        c.requestMethod = "POST"
        c.connectTimeout = 10_000
        c.readTimeout = 300_000 // Hermes può lavorare a lungo con i suoi strumenti
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "text/event-stream")
        c.setRequestProperty("Authorization", "Bearer $key")
        c.doOutput = true
        val body = JSONObject()
            .put("model", "hermes-agent")
            .put("messages", messages)
            .put("stream", true)
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code == 401 || code == 403) throw AuthException()
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.readText()?.take(200) ?: ""
                throw IOException("HTTP $code $err")
            }
            val type = c.contentType ?: ""
            c.inputStream.bufferedReader().use { r ->
                if (!type.contains("event-stream")) {
                    // il server ha risposto tutto in una volta
                    val json = JSONObject(r.readText())
                    val msg = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    if (!msg.isNull("content")) onDelta(msg.getString("content"))
                    return
                }
                while (!cancelled) {
                    val line = r.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val j = try { JSONObject(data) } catch (_: Throwable) { continue }
                    val choice = j.optJSONArray("choices")?.optJSONObject(0) ?: continue
                    val delta = choice.optJSONObject("delta") ?: continue
                    if (delta.isNull("content")) continue
                    val d = delta.optString("content")
                    if (d.isNotEmpty()) onDelta(d)
                }
            }
            if (cancelled) throw CancelledException()
        } catch (e: IOException) {
            if (cancelled) throw CancelledException()
            throw e
        } finally {
            try { c.disconnect() } catch (_: Throwable) {}
            conn = null
        }
    }
}

/**
 * Divide il testo in arrivo in frasi pronte da leggere e separa i comandi [[AZIONE {...}]],
 * che non vanno letti ad alta voce.
 */
class Segmenter {
    private val buf = StringBuilder()

    fun feed(d: String): Pair<List<String>, List<JSONObject>> {
        buf.append(d)
        return drain(false)
    }

    fun flush(): Pair<List<String>, List<JSONObject>> = drain(true)

    private fun drain(final: Boolean): Pair<List<String>, List<JSONObject>> {
        val actions = mutableListOf<JSONObject>()
        while (true) {
            val s = buf.indexOf("[[")
            if (s < 0) break
            val e = buf.indexOf("]]", s)
            if (e < 0) break
            parseAction(buf.substring(s + 2, e))?.let { actions += it }
            buf.delete(s, e + 2)
        }
        var open = buf.indexOf("[[")
        if (final && open >= 0) {
            // comando scritto male (es. una sola "]"): prova a recuperarlo e non perdere il testo dopo
            val rest = buf.substring(open + 2)
            parseAction(rest)?.let { actions += it }
            val b = rest.lastIndexOf('}')
            val tail = if (b >= 0) rest.substring(b + 1).trimStart(']', ' ') else rest
            buf.setLength(open); buf.append(' ').append(tail)
            open = -1
        }
        val limit = if (open >= 0) open else buf.length
        var cut = 0
        if (final) {
            cut = limit
        } else {
            var i = 0
            while (i < limit - 1) {
                if (buf[i] in ".!?…\n" && buf[i + 1].isWhitespace()) cut = i + 1
                i++
            }
        }
        val chunk = buf.substring(0, cut)
        buf.delete(0, cut)
        val clean = clean(chunk)
        return (if (clean.isNotBlank()) listOf(clean) else emptyList<String>()) to actions
    }

    private fun parseAction(inner: String): JSONObject? {
        val a = inner.indexOf('{')
        val b = inner.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try { JSONObject(inner.substring(a, b + 1)) } catch (_: Throwable) { null }
    }

    companion object {
        /** Toglie i simboli markdown che la voce leggerebbe male. */
        fun clean(t: String): String = t
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("(?m)^\\s*[-*•]\\s+"), "")
            .replace(Regex("(?m)^\\s*#+\\s*"), "")
            .replace("**", "").replace("__", "").replace("`", "").replace("*", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
