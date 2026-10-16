package com.example.mioassistente

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.view.KeyEvent
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Azioni che Hermes può far eseguire al telefono.
 * Hermes le chiede scrivendo nella risposta: [[AZIONE {"tipo": "...", ...}]]
 */
class PhoneActions(private val ctx: Context) {

    /** [spoken]: frase da dire (es. un errore). [data]: dati da rimandare a Hermes. */
    data class Result(val spoken: String? = null, val data: String? = null)

    fun needsConfirmation(a: JSONObject) = a.optString("tipo") in setOf("chiama", "sms", "whatsapp")

    fun confirmationQuestion(a: JSONObject): String {
        val chi = a.optString("contatto")
        val testo = a.optString("testo")
        return when (a.optString("tipo")) {
            "chiama" -> "Chiamo $chi. Confermi?"
            "sms" -> "Mando un SMS a $chi con scritto: $testo. Confermi?"
            "whatsapp" -> "Preparo un messaggio WhatsApp a $chi con scritto: $testo. Confermi?"
            else -> "Confermi?"
        }
    }

    fun execute(a: JSONObject): Result {
        val tipo = a.optString("tipo")
        App.log(ctx, "Azione: $a")
        return try {
            when (tipo) {
                "sveglia" -> alarm(a)
                "timer" -> timer(a)
                "promemoria" -> reminder(a)
                "chiama" -> call(a)
                "sms" -> sms(a)
                "whatsapp" -> whatsapp(a)
                "apri_app" -> openApp(a)
                "naviga" -> navigate(a)
                "posizione" -> location()
                "notifiche" -> Result(data = NotificationReader.snapshot(ctx))
                "cerca_contatto" -> findContact(a)
                "torcia" -> torch(a)
                "volume" -> volume(a)
                "musica" -> media(a)
                else -> Result(spoken = "Non so ancora fare l'azione $tipo.")
            }
        } catch (e: SecurityException) {
            App.log(ctx, "Permesso mancante per $tipo: $e")
            Result(spoken = "Mi manca un permesso per farlo. Aprimi e tocca Configura telefono.")
        } catch (e: Throwable) {
            App.log(ctx, "Errore azione $tipo: $e")
            Result(spoken = "Non sono riuscito a completare l'azione.")
        }
    }

    // ---------- descrizione per Hermes ----------

    fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ITALIAN).format(Date())
        val battery = try {
            (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Throwable) { -1 }
        return """
Stai parlando a voce con l'utente tramite il suo telefono Android. Adesso è $now. Batteria del telefono: $battery%.
Rispondi in italiano, in modo breve e naturale, senza elenchi, simboli o markdown: il testo viene letto ad alta voce.

Oltre agli strumenti che già hai sul computer, puoi far eseguire azioni SUL TELEFONO scrivendo nella risposta un comando così:
[[AZIONE {"tipo":"...", ...}]]
Il comando non viene letto ad alta voce. Puoi usarne più di uno. Azioni disponibili:
- {"tipo":"sveglia","ora":7,"minuti":30,"etichetta":"testo facoltativo"}
- {"tipo":"timer","secondi":300,"etichetta":"testo facoltativo"}
- {"tipo":"promemoria","titolo":"...","quando":"2026-10-17T15:00","durata_minuti":30} (evento nel calendario con avviso)
- {"tipo":"chiama","contatto":"nome in rubrica o numero"}
- {"tipo":"sms","contatto":"nome o numero","testo":"..."}
- {"tipo":"whatsapp","contatto":"nome o numero","testo":"..."} (apre la chat col testo pronto, l'utente preme invio)
- {"tipo":"apri_app","nome":"nome dell'app"}
- {"tipo":"naviga","destinazione":"indirizzo o luogo"}
- {"tipo":"torcia","accesa":true}
- {"tipo":"volume","livello":50} (da 0 a 100)
- {"tipo":"musica","comando":"play|pausa|avanti|indietro"}
Azioni che ti restituiscono dati (dopo riceverai un messaggio che inizia con [RISULTATO ...] e dovrai rispondere usando quei dati):
- {"tipo":"posizione"} posizione attuale dell'utente
- {"tipo":"notifiche"} notifiche recenti del telefono (messaggi, mail, ecc.)
- {"tipo":"cerca_contatto","nome":"..."} numeri di telefono in rubrica
Quando usi un'azione che restituisce dati, scrivi solo il comando, senza altro testo: risponderai dopo aver ricevuto il risultato.
Per chiamate e messaggi il telefono chiede conferma all'utente da solo: non chiederla tu.

Sicurezza: prima di fare sul computer qualcosa di irreversibile o rischioso (cancellare o sovrascrivere file,
inviare email o messaggi, acquisti, pagamenti, modifiche di sistema) spiega in una frase cosa farai e chiedi
conferma; procedi solo dopo un sì esplicito dell'utente nel messaggio successivo.
""".trim()
    }

    // ---------- azioni ----------

    private fun start(i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Throwable) {
        App.log(ctx, "Impossibile aprire ${i.action}: $e"); false
    }

    private val popupHint = "Non riesco ad aprirlo da qui: concedimi il permesso di mostrarmi sopra le altre app, da Configura telefono."

    private fun alarm(a: JSONObject): Result {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("ora", 7))
            .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minuti", 0))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        a.optString("etichetta").takeIf { it.isNotBlank() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return if (start(i)) Result() else Result(spoken = popupHint)
    }

    private fun timer(a: JSONObject): Result {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("secondi", 60))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        a.optString("etichetta").takeIf { it.isNotBlank() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return if (start(i)) Result() else Result(spoken = popupHint)
    }

    private fun reminder(a: JSONObject): Result {
        val title = a.optString("titolo", "Promemoria")
        val whenStr = a.optString("quando")
        val startDt = try { LocalDateTime.parse(whenStr.take(16)) } catch (_: Throwable) {
            return Result(spoken = "Non ho capito quando mettere il promemoria.")
        }
        val startMs = startDt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endMs = startMs + a.optInt("durata_minuti", 30) * 60_000L
        val cr = ctx.contentResolver
        var calId = -1L
        cr.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            "${CalendarContract.Calendars.VISIBLE}=1", null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(2) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                if (calId < 0 || c.getInt(1) == 1) calId = c.getLong(0)
                if (c.getInt(1) == 1) break
            }
        }
        if (calId < 0) return Result(spoken = "Non trovo un calendario dove scrivere.")
        val ev = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val uri = cr.insert(CalendarContract.Events.CONTENT_URI, ev) ?: return Result(spoken = "Non sono riuscito a salvare il promemoria.")
        val eventId = uri.lastPathSegment?.toLongOrNull() ?: return Result()
        cr.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, a.optInt("avviso_minuti", 10))
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
        return Result()
    }

    /** Trova il numero: se è già un numero lo usa, altrimenti cerca in rubrica. */
    private fun resolveNumber(who: String): Pair<String, String>? {
        val digits = who.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 5) return who to digits
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("READ_CONTACTS")
        }
        val matches = contacts(who)
        return matches.firstOrNull { it.first.equals(who, ignoreCase = true) } ?: matches.firstOrNull()
    }

    private fun contacts(name: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%${name.trim()}%"), null
        )?.use { c ->
            while (c.moveToNext() && out.size < 8) {
                val p = c.getString(0) to c.getString(1)
                if (out.none { it.second.filter(Char::isDigit) == p.second.filter(Char::isDigit) }) out += p
            }
        }
        return out
    }

    private fun findContact(a: JSONObject): Result {
        val name = a.optString("nome")
        val list = contacts(name)
        return Result(data = if (list.isEmpty()) "Nessun contatto trovato per \"$name\"."
            else list.joinToString("; ") { "${it.first}: ${it.second}" })
    }

    private fun call(a: JSONObject): Result {
        val (name, num) = resolveNumber(a.optString("contatto")) ?: return Result(spoken = "Non trovo questo contatto in rubrica.")
        val canCall = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val i = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$num"))
        App.log(ctx, "Chiamo $name ($num)")
        return if (start(i)) Result() else Result(spoken = popupHint)
    }

    private fun sms(a: JSONObject): Result {
        val (name, num) = resolveNumber(a.optString("contatto")) ?: return Result(spoken = "Non trovo questo contatto in rubrica.")
        val text = a.optString("testo")
        if (text.isBlank()) return Result(spoken = "Il messaggio è vuoto.")
        @Suppress("DEPRECATION")
        val sm = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        sm.sendMultipartTextMessage(num, null, sm.divideMessage(text), null, null)
        App.log(ctx, "SMS inviato a $name ($num)")
        return Result(spoken = "Messaggio inviato.")
    }

    private fun whatsapp(a: JSONObject): Result {
        val (_, raw) = resolveNumber(a.optString("contatto")) ?: return Result(spoken = "Non trovo questo contatto in rubrica.")
        var num = raw.filter { it.isDigit() }
        if (raw.startsWith("00")) num = num.drop(2)
        else if (!raw.startsWith("+") && num.length == 10 && num.startsWith("3")) num = "39$num" // cellulare italiano
        val url = "https://wa.me/$num?text=" + URLEncoder.encode(a.optString("testo"), "UTF-8")
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.whatsapp")
        return if (start(i) || start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) Result(spoken = "Ho preparato il messaggio, premi invio su WhatsApp.")
        else Result(spoken = popupHint)
    }

    private fun openApp(a: JSONObject): Result {
        val wanted = a.optString("nome").lowercase().trim()
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val hit = apps.firstOrNull { it.first.lowercase() == wanted }
            ?: apps.firstOrNull { it.first.lowercase().contains(wanted) }
            ?: apps.firstOrNull { wanted.contains(it.first.lowercase()) && it.first.length >= 3 }
            ?: return Result(spoken = "Non trovo l'app ${a.optString("nome")}.")
        val i = pm.getLaunchIntentForPackage(hit.second) ?: return Result(spoken = "Non riesco ad aprire ${hit.first}.")
        return if (start(i)) Result() else Result(spoken = popupHint)
    }

    private fun navigate(a: JSONObject): Result {
        val dest = URLEncoder.encode(a.optString("destinazione"), "UTF-8")
        val ok = start(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$dest")))
            || start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$dest")))
        return if (ok) Result() else Result(spoken = popupHint)
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun location(): Result {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return Result(data = "Permesso di posizione non concesso dall'utente.")
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = lm.getProviders(true).mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Throwable) { null } }
            .maxByOrNull { it.time } ?: return Result(data = "Posizione non disponibile al momento (GPS spento?).")
        val minutes = (System.currentTimeMillis() - loc.time) / 60_000
        val address = try {
            Geocoder(ctx, Locale.ITALIAN).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.getAddressLine(0)
        } catch (_: Throwable) { null }
        return Result(data = "Coordinate ${"%.5f".format(Locale.US, loc.latitude)}, ${"%.5f".format(Locale.US, loc.longitude)} " +
            "(precisione ${loc.accuracy.toInt()} m, rilevata $minutes minuti fa)" + (address?.let { ". Indirizzo: $it" } ?: ""))
    }

    private fun torch(a: JSONObject): Result {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return Result(spoken = "Questo telefono non ha la torcia.")
        cm.setTorchMode(id, a.optBoolean("accesa", true))
        return Result()
    }

    private fun volume(a: JSONObject): Result {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = (a.optInt("livello", 50).coerceIn(0, 100) * max / 100.0).toInt()
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        return Result()
    }

    private fun media(a: JSONObject): Result {
        val key = when (a.optString("comando").lowercase()) {
            "play", "riproduci" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pausa", "pause", "stop" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "avanti", "next", "successiva" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "indietro", "previous", "precedente" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return Result()
    }
}
