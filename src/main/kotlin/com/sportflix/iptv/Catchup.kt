package com.sportflix.iptv

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Ссылки на архив передач (catchup) по параметрам из плейлиста. */
object Catchup {
    fun supported(e: Entry): Boolean = e.catchup.isNotBlank() && !e.catchup.equals("none", true) && !e.poster

    /** Сколько дней архива у канала. */
    fun days(e: Entry): Int = if (e.catchupDays > 0) e.catchupDays else 3

    /** Можно ли открыть эту передачу из архива. */
    fun available(e: Entry, pr: Prog, now: Long = System.currentTimeMillis()): Boolean =
        supported(e) && pr.start < now - 20_000L && pr.start >= now - days(e) * 24 * 3600_000L

    private fun utc(fmt: String, t: Long): String =
        SimpleDateFormat(fmt, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))

    /**
     * Ссылка на передачу [pr], начиная с момента [from] (мс). null — если архив для канала не настроен.
     * Поддерживаются режимы append, default, shift/timeshift, flussonic.
     */
    fun url(e: Entry, pr: Prog, from: Long = pr.start): String? {
        if (!supported(e)) return null
        val now = System.currentTimeMillis()
        val startSec = from / 1000
        val endSec = pr.stop / 1000
        val dur = ((pr.stop - from) / 1000).coerceAtLeast(1)
        val vars = mapOf(
            "timestamp" to startSec.toString(), "start" to startSec.toString(), "utc" to startSec.toString(),
            "end" to endSec.toString(), "utcend" to endSec.toString(),
            "duration" to dur.toString(),
            "offset" to (now / 1000 - startSec).toString(),
            "now" to (now / 1000).toString(), "lutc" to (now / 1000).toString(),
            "Y" to utc("yyyy", from), "m" to utc("MM", from), "d" to utc("dd", from),
            "H" to utc("HH", from), "M" to utc("mm", from), "S" to utc("ss", from)
        )
        fun fill(t: String): String = Regex("\\$?\\{(\\w+)\\}").replace(t) { m -> vars[m.groupValues[1]] ?: m.value }
        fun join(base: String, add: String): String =
            if (add.startsWith("?") && base.contains("?")) base + "&" + add.substring(1) else base + add
        val src = e.catchupSource
        return when (e.catchup.lowercase(Locale.ROOT)) {
            "append" -> if (src.isBlank()) null else join(e.url, fill(src))
            "default" -> if (src.isBlank()) null else fill(src)
            "shift", "timeshift" -> join(e.url, fill("?utc={utc}&lutc={lutc}"))
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonic(e.url, startSec, dur)
            else -> when {
                src.isBlank() -> null
                src.startsWith("http", true) -> fill(src)
                else -> join(e.url, fill(src))
            }
        }
    }

    private fun flussonic(url: String, utc: Long, dur: Long): String? {
        val q = url.indexOf('?')
        val base = if (q >= 0) url.substring(0, q) else url
        val query = if (q >= 0) url.substring(q) else ""
        val res = when {
            base.endsWith("/index.m3u8") -> base.removeSuffix("/index.m3u8") + "/index-$utc-$dur.m3u8"
            base.endsWith("/video.m3u8") -> base.removeSuffix("/video.m3u8") + "/video-$utc-$dur.m3u8"
            base.endsWith("/mpegts") -> base.removeSuffix("/mpegts") + "/archive-$utc-$dur.ts"
            else -> return null
        }
        return res + query
    }
}
