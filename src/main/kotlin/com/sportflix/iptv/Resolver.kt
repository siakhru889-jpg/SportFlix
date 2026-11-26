package com.sportflix.iptv

import java.net.HttpURLConnection
import java.net.URL

/**
 * Ссылки, которые меняются. В плейлисте адрес пишется с префиксом resolve: —
 *   resolve:https://example.com/stream.php
 * Перед каждым запуском (и при ошибке воспроизведения) приложение открывает этот адрес и берёт актуальную ссылку на поток:
 * либо адрес перенаправляет на .m3u8, либо в ответе (текст, JSON, HTML) есть ссылка на .m3u8.
 * Нужен только для ваших собственных источников или источников, на использование которых у вас есть право.
 */
object Resolver {
    const val PREFIX = "resolve:"

    fun isDynamic(url: String) = url.startsWith(PREFIX, ignoreCase = true)

    private val m3u8 = Regex("""https?:(?://|\\/\\/)[^\s"'<>\\]*?\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)

    /** Блокирующий вызов — запускать не в главном потоке. Возвращает ссылку на поток или null. */
    fun resolve(url: String, headers: Map<String, String>): String? {
        var cur = url.substring(PREFIX.length).trim()
        repeat(6) {
            val c = URL(cur).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 15000; c.readTimeout = 20000
                c.setRequestProperty("User-Agent", headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: "Mozilla/5.0 (Linux; Android 11) SportflixIPTV")
                headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) c.setRequestProperty(k, v) }
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return null
                    cur = URL(URL(cur), loc).toString()
                    return@repeat
                }
                if (code !in 200..299) return null
                val type = c.contentType ?: ""
                if (type.contains("mpegurl", true) || cur.substringBefore('?').endsWith(".m3u8", true)) return cur
                val body = c.inputStream.use { i ->
                    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var n: Int; var total = 0
                    while (i.read(buf).also { n = it } > 0 && total < 262144) { out.write(buf, 0, n); total += n }
                    String(out.toByteArray(), Charsets.UTF_8)
                }
                if (body.trimStart().startsWith("#EXTM3U")) return cur
                m3u8.find(body)?.value?.replace("\\/", "/")?.let { return it }
                val one = body.trim()
                if (one.startsWith("http") && !one.contains('\n') && !one.contains(' ')) return one
                return null
            } finally { c.disconnect() }
        }
        return null
    }
}
