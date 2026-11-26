package com.sportflix.iptv

import android.util.Base64

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class LicenseException(msg: String) : Exception(msg)

/**
 * Подписка по коду. Действующие коды перечислены (в виде хэшей) в текстовом файле на Dropbox — см. LIST_URL.
 * Закрытые каналы (tier="premium" / tier="sport" в плейлисте) скрыты, пока не введён действующий код.
 * Чтобы забанить код — удалите его строку из файла (или поставьте # в начале): при ближайшей проверке доступ пропадёт.
 *
 * Формат файла:
 *   #SFX-LIST v1
 *   a1b2c3d4e5f60718 P            # 001   (хэш, тариф: P — Премиум, S — Спорт, B — оба, необязательная дата «до»: 2026-12-31)
 */
object License {
    const val PREMIUM = 1   // «Премиум» — все ТВ-каналы + Медиатека
    const val SPORT = 2     // «Спорт» — каналы с tier="sport"
    const val MEDIA = 4     // «Медиатека» — записи с tier="media" (фильмы и сериалы)

    // тариф записи в плейлисте (Entry.tier): 0 — бесплатно, 1 — «Премиум», 2 — «Спорт», 3 — «Медиатека»
    const val T_PREMIUM = 1
    const val T_SPORT = 2
    const val T_MEDIA = 3

    /** Ссылка на файл со списком действующих кодов (Dropbox). */
    private const val LIST_URL = "https://dl.dropboxusercontent.com/s/ujw2oh8ublmtync7qw625/valid_keys.txt?rlkey=klduay3m3b4425ywsddul292j&dl=0"

    private const val DAY_MS = 86_400_000L
    private const val GRACE_MS = 3 * DAY_MS          // без связи со списком подписка держится до 3 суток
    private const val RECHECK_MS = 2 * 3600_000L     // как часто сверяться со списком

    @Volatile private var lastAttempt = 0L
    @Volatile private var checking = false

    private class Rec(val tier: Int, val until: Long)

    fun normalize(key: String): String = key.uppercase().filter { it.isLetterOrDigit() }

    private fun hash(body: String): String =
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** Текущая дата по московскому времени (UTC+3): подписка действует весь последний день до 00:00 по Москве. */
    private fun today() = (System.currentTimeMillis() + 3 * 3600_000L) / DAY_MS

    /** Часы не отматывали назад: помним самое позднее время, которое видели. */
    fun clockOk(): Boolean {
        val now = System.currentTimeMillis()
        val seen = Store.lastSeen
        if (now + 6 * 3600_000L < seen) return false
        if (now - seen > 10 * 60_000L) Store.lastSeen = now
        return true
    }

    /** Какие тарифы действуют сейчас (битовая маска, 0 — нет подписки). */
    fun tiersNow(): Int {
        if (Store.licenseKey.isBlank()) return 0
        if (!clockOk()) return 0
        if (System.currentTimeMillis() - Store.licOk > GRACE_MS) return 0
        if (Store.licUntil != 0L && today() > Store.licUntil) return 0
        return Store.licTier
    }

    fun has(tier: Int): Boolean = tiersNow() and tier != 0

    fun allowsTier(tiers: Int, tier: Int): Boolean =
        tier == 0 || tiers and PREMIUM != 0 || (tier == T_SPORT && tiers and SPORT != 0) || (tier == T_MEDIA && tiers and MEDIA != 0)

    /** Можно ли смотреть канал: бесплатный, «Премиум» открывает всё, «Спорт» — каналы со спортивным тарифом. */
    fun canWatch(e: Entry): Boolean = allowsTier(tiersNow(), e.tier)

    // ---------- список кодов ----------
    private fun listUrl(): String {
        var u = LIST_URL.trim()
        u = u.replace("://www.dropbox.com/", "://dl.dropboxusercontent.com/").replace("://dropbox.com/", "://dl.dropboxusercontent.com/")
        return u + (if (u.contains('?')) "&" else "?") + "_=" + System.currentTimeMillis()
    }

    private fun fetchList(): String {
        val c = URL(listUrl()).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000; c.readTimeout = 15000; c.useCaches = false
            c.setRequestProperty("Cache-Control", "no-cache")
            if (c.responseCode != 200) throw LicenseException("список недоступен")
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var n: Int; var total = 0
            c.inputStream.use { i -> while (i.read(buf).also { n = it } > 0 && total < 524288) { out.write(buf, 0, n); total += n } }
            return String(out.toByteArray(), Charsets.UTF_8)
        } finally { c.disconnect() }
    }

    /** Запись о коде в списке или null (кода нет / он отключён). Бросает исключение, если файл не похож на список. */
    private fun lookup(text: String, h: String): Rec? {
        val lines = text.replace("\uFEFF", "").lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || !lines[0].startsWith("#SFX")) throw LicenseException("это не список ключей")
        for (l in lines) {
            if (l.startsWith("#")) continue
            val tok = l.substringBefore('#').trim().split(Regex("\\s+"))
            if (tok[0].lowercase() != h) continue
            // буквы тарифов в строке списка: P — Премиум, S — Спорт, M — Медиатека (можно вместе: SM), B — Премиум+Спорт (старый вариант)
            var tier = 0
            for (ch in (tok.getOrNull(1) ?: "P").uppercase()) tier = tier or when (ch) { 'P' -> PREMIUM; 'S' -> SPORT; 'M' -> MEDIA; 'B' -> PREMIUM or SPORT; else -> 0 }
            if (tier == 0) tier = PREMIUM
            var until = 0L
            tok.getOrNull(2)?.let { d ->
                try { val f = SimpleDateFormat("yyyy-MM-dd", Locale.US); f.timeZone = TimeZone.getTimeZone("UTC"); until = f.parse(d)!!.time / DAY_MS } catch (e: Exception) { }
            }
            return Rec(tier, until)
        }
        return null
    }

    /** Применяет запись из списка; true — если доступ изменился. */
    private fun apply(key: String, r: Rec?): Boolean {
        val before = tiersNow()
        if (r == null) { Store.licenseKey = ""; Store.licTier = 0; Store.licUntil = 0L }
        else { Store.licenseKey = key; Store.licTier = r.tier; Store.licUntil = r.until; Store.licOk = System.currentTimeMillis() }
        val now = System.currentTimeMillis(); if (now > Store.lastSeen) Store.lastSeen = now
        return before != tiersNow()
    }

    /** Фоновая сверка со списком (не из главного потока). true — доступ изменился (код отозван, поменялся тариф или срок). */
    fun recheckIfDue(): Boolean {
        if (LIST_URL.isBlank() || Store.licenseKey.isBlank() || checking) return false
        val now = System.currentTimeMillis()
        if (now - lastAttempt < RECHECK_MS) return false
        lastAttempt = now; checking = true
        try {
            val key = Store.licenseKey
            val text = try { fetchList() } catch (e: Exception) { return false }      // нет связи — действует льготный период
            val rec = try { lookup(text, hash(key)) } catch (e: LicenseException) { return false }   // странный файл — не отзываем
            return apply(key, rec)
        } finally { checking = false }
    }

    /** Активация кода (не из главного потока). Возвращает статус подписки или бросает LicenseException с причиной. */
    fun activate(raw: String): String {
        if (LIST_URL.isBlank()) throw LicenseException("Адрес списка кодов не настроен")
        val body = normalize(raw)
        if (body.length != 12) throw LicenseException("Код состоит из 12 символов, например ABCD-EFGH-JKMN")
        val text = try { fetchList() } catch (e: Exception) { throw LicenseException("Нет связи — не удалось проверить код. Проверьте интернет") }
        val rec = (try { lookup(text, hash(body)) } catch (e: LicenseException) { throw LicenseException("Список кодов временно недоступен") })
            ?: throw LicenseException("Код недействителен")
        if (rec.until != 0L && today() > rec.until) throw LicenseException("Срок действия кода истёк")
        apply(body, rec)
        return statusText()
    }

    private fun dateText(day: Long): String {
        val f = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()); f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(day * DAY_MS))
    }

    fun statusText(): String {
        if (Store.licenseKey.isBlank()) return "Не активна"
        if (!clockOk()) return "Проверьте дату и время на устройстве"
        val t = tiersNow()
        if (t == 0) {
            if (Store.licUntil != 0L && today() > Store.licUntil) return "Срок подписки истёк"
            return "Нужна проверка — подключитесь к интернету"
        }
        val name = if (t and PREMIUM != 0) "Премиум" else listOfNotNull(if (t and SPORT != 0) "Спорт" else null, if (t and MEDIA != 0) "Медиатека" else null).joinToString(" + ")
        return name + if (Store.licUntil != 0L) " до " + dateText(Store.licUntil) else ""
    }
}

