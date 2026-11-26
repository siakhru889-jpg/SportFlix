package com.sportflix.iptv

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

data class Entry(
    val name: String, val url: String, val group: String,
    val tvgId: String = "", val logo: String = "",
    val headers: Map<String, String> = emptyMap(),
    val poster: Boolean = false,
    val catchup: String = "",
    val catchupDays: Int = 0,
    val catchupSource: String = "",
    /** Запасные адреса того же потока (строки #EXTMIRROR: в плейлисте): при ошибке плеер переключается на следующий. */
    val mirrors: List<String> = emptyList(),
    /** Тариф, нужный для канала: 0 — бесплатный, 1 — «Премиум», 2 — «Спорт» (атрибут tier="premium"/"sport" в #EXTINF). */
    val tier: Int = 0,
    /** Код вида сериала из атрибута stype: s — сериал, ms — мультсериал, doc — документальный сериал (пусто — определяется по названию). */
    val kind: String = "",
    /** Рейтинг фильма, страна и возрастное ограничение из атрибутов rating / country / age (если их нет — берутся из названия, см. Meta). */
    val rating: String = "", val country: String = "", val age: String = ""
)

/** Разобранные плейлисты живут, пока жив процесс, — пересоздание экрана их не сбрасывает. */
object Playlists { val mem = HashMap<String, Playlist.Parsed>() }

object Queue { var items: List<Entry> = emptyList(); var title: String = "" }

object Playlist {
    /** Скрытые (не по подписке) каналы раздела: сколько и каких тарифов — для карточки «Ещё N каналов по подписке». */
    class Hid(var count: Int = 0, var tiers: Int = 0)

    class Parsed(val groups: LinkedHashMap<String, MutableList<Entry>>, val epg: List<String>, val hidden: Map<String, Hid> = emptyMap())

    /** Тариф из атрибута tier: premium / sport / media (Медиатека); free — бесплатный; null — атрибута нет. */
    private fun tierOf(v: String?): Int? = when (v?.trim()?.lowercase()) {
        null, "" -> null
        "premium", "prem", "vip" -> 1
        "sport", "sports", "спорт" -> 2
        "media", "mediateka", "movies", "медиатека" -> 3
        else -> 0
    }

    const val NO_GROUP = "Без группы"

    private fun file(ctx: Context, link: String) = File(ctx.filesDir, "pl_" + Integer.toHexString(link.hashCode()) + ".m3u")

    /** Плейлист из сохранённого файла, без сети. null — файла ещё нет. */
    fun loadCached(ctx: Context, link: String, poster: Boolean = false): Parsed? {
        val f = file(ctx, link)
        if (!f.exists() || f.length() == 0L) return null
        return try { parse(f.readText(), poster) } catch (e: Throwable) { null }
    }

    /** Сколько времени назад сохранён плейлист. */
    fun ageMs(ctx: Context, link: String): Long {
        val f = file(ctx, link)
        return if (f.exists()) System.currentTimeMillis() - f.lastModified() else Long.MAX_VALUE
    }

    fun load(ctx: Context, link: String, poster: Boolean = false): Parsed {
        val f = file(ctx, link)
        val text = try { fetch(link).also { f.writeText(it) } } catch (e: Exception) { if (f.exists()) f.readText() else throw e }
        return parse(text, poster)
    }

    private fun fetch(link: String): String {
        val c = URL(link).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "SportflixIPTV/1.2")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** Значение атрибута: key="v", key='v' или key=v. Часть другого имени (logo внутри tvg-logo) не считается. */
    private fun attr(l: String, k: String): String? {
        val m = Regex("(?<![\\w-])" + Regex.escape(k) + "=(?:\"([^\"]*)\"|'([^']*)'|([^\\s,\"']+))").find(l) ?: return null
        val v = (m.groups[1]?.value ?: m.groups[2]?.value ?: m.groups[3]?.value ?: "").trim()
        return v
    }

    private fun fixUrl(u: String): String = when {
        u.startsWith("//") -> "https:$u"
        else -> u
    }

    /** Название — всё после запятой, стоящей вне кавычек. Если кавычки в строке не парные (лишняя "), берём текст после последней запятой. */
    private fun titleOf(l: String): String {
        if (l.count { it == '"' } % 2 == 0) {
            var q = false
            for (i in l.indices) {
                val ch = l[i]
                if (ch == '"') q = !q else if (ch == ',' && !q) return l.substring(i + 1).trim()
            }
        }
        val i = l.lastIndexOf(',')
        return if (i >= 0) l.substring(i + 1).trim() else ""
    }

    /** tvg-id и названия (в нормализованном виде) каналов из сохранённых плейлистов — по ним отбираем нужное из телепрограммы. null — плейлистов ещё нет. */
    fun epgKeys(ctx: Context): Pair<Set<String>, Set<String>>? {
        val ids = HashSet<String>(); val nm = HashSet<String>()
        for (s in Config.sources) {
            if (s.movies) continue
            val f = file(ctx, s.url)
            if (!f.exists() || f.length() == 0L) continue
            try {
                f.bufferedReader().useLines { seq ->
                    for (raw in seq) {
                        if (!raw.startsWith("#EXTINF")) continue
                        val id = attr(raw, "tvg-id")?.takeIf { it.isNotBlank() } ?: attr(raw, "tvg-name")?.takeIf { it.isNotBlank() }
                        if (!id.isNullOrEmpty()) { ids.add(id); nm.add(Epg.norm(id)) }
                        val t = titleOf(raw.trim())
                        if (t.isNotEmpty()) nm.add(Epg.norm(t))
                    }
                }
            } catch (e: Throwable) { }
        }
        return if (ids.isEmpty() && nm.isEmpty()) null else ids to nm
    }

    /** Настоящая ссылка на поток? Пустые адреса и «шаблоны» (http://, example.com, xxx…) в список не попадают. */
    fun validUrl(raw: String): Boolean {
        var u = raw.trim()
        if (Resolver.isDynamic(u)) u = u.substring(Resolver.PREFIX.length).trim()
        else if (Web.isWeb(u)) u = u.substring(4).trim()
        val m = Regex("^(?i)(https?|rtmps?|rtsp|udp|rtp|mms|srt)://([^/?#\\s]+)").find(u) ?: return false
        val auth = m.groupValues[2].substringAfterLast('@')
        val host = (if (auth.startsWith("[")) auth.substringBefore(']') else auth.substringBefore(':')).lowercase()
        val hasPort = auth.contains(':') && !auth.startsWith("[")
        if (host.length < 3) return false
        if (host.any { it == '{' || it == '<' || it == '}' || it == '>' || it == '%' || it == '$' }) return false
        if (!host.contains('.') && !host.startsWith("[") && host != "localhost" && !hasPort) return false
        if (host.startsWith("xxx") || host.contains("example.") || host.contains("yourdomain") || host == "domain.com" || host == "url" || host == "link") return false
        return u.length > m.value.length || host.contains('.')
    }

    fun parse(text: String, poster: Boolean = false): Parsed {
        val res = LinkedHashMap<String, MutableList<Entry>>()
        val epg = ArrayList<String>()
        var name = ""; var group = NO_GROUP; var id = ""; var logo = ""
        var defCu = ""; var defDays = 0; var defSrc = ""
        var cu = ""; var cuDays = 0; var cuSrc = ""; var tier = 0; var kind = ""
        var rating = ""; var country = ""; var age = ""
        var defTier = 0   // tier="…" в заголовке #EXTM3U — тариф по умолчанию для всех записей плейлиста (например, всей Медиатеки)
        val hidden = LinkedHashMap<String, Hid>()
        var extGrp: String? = null
        val tiersNow = License.tiersNow()   // закрытые каналы без подписки в список не попадают вовсе
        var hdrs = HashMap<String, String>()
        var mirrors = ArrayList<String>()
        val pool = HashMap<String, String>()
        fun pl(x: String) = if (x.isEmpty()) x else pool.getOrPut(x) { x }
        for (raw in text.lineSequence()) {
            val l = raw.trim().removePrefix("\uFEFF")
            if (l.startsWith("#EXTM3U")) {
                val v = attr(l, "url-tvg") ?: attr(l, "x-tvg-url")
                if (v != null) epg.addAll(v.split(",").map { it.trim() }.filter { it.startsWith("http") })
                defCu = attr(l, "catchup") ?: attr(l, "catchup-type") ?: ""
                defDays = (attr(l, "catchup-days") ?: attr(l, "tvg-rec"))?.toIntOrNull() ?: 0
                defSrc = attr(l, "catchup-source") ?: ""
                defTier = tierOf(attr(l, "tier")) ?: 0
            } else if (l.startsWith("#EXTINF")) {
                name = titleOf(l)
                // если tvg-id пустой — телепрограмму ищем по tvg-name
                id = attr(l, "tvg-id")?.takeIf { it.isNotBlank() } ?: attr(l, "tvg-name")?.takeIf { it.isNotBlank() } ?: ""
                logo = fixUrl(attr(l, "tvg-logo") ?: attr(l, "logo") ?: attr(l, "tvg-logo-small") ?: "")
                cu = attr(l, "catchup") ?: attr(l, "catchup-type") ?: defCu
                cuDays = (attr(l, "catchup-days") ?: attr(l, "tvg-rec"))?.toIntOrNull() ?: defDays
                cuSrc = attr(l, "catchup-source") ?: defSrc
                tier = tierOf(attr(l, "tier")) ?: defTier
                rating = attr(l, "rating") ?: attr(l, "tvg-rating") ?: attr(l, "imdb") ?: ""
                country = attr(l, "country") ?: attr(l, "tvg-country") ?: ""
                age = attr(l, "age") ?: attr(l, "tvg-age") ?: ""
                kind = when (attr(l, "stype")?.trim()?.lowercase()) { "s" -> "s"; "ms" -> "ms"; "doc" -> "doc"; else -> "" }
                val g = attr(l, "group-title")
                group = if (!g.isNullOrEmpty()) g else (extGrp ?: NO_GROUP)
                (attr(l, "http-user-agent") ?: attr(l, "user-agent"))?.let { hdrs["User-Agent"] = it }
                (attr(l, "http-referrer") ?: attr(l, "http-referer") ?: attr(l, "referrer"))?.let { hdrs["Referer"] = it }
            } else if (l.startsWith("#EXTVLCOPT:")) {
                val kv = l.removePrefix("#EXTVLCOPT:")
                val i = kv.indexOf('=')
                if (i > 0) {
                    val k = kv.substring(0, i).trim().lowercase()
                    val v = kv.substring(i + 1).trim()
                    when (k) {
                        "http-user-agent" -> hdrs["User-Agent"] = v
                        "http-referrer", "http-referer" -> hdrs["Referer"] = v
                        "http-origin" -> hdrs["Origin"] = v
                    }
                }
            } else if (l.startsWith("#EXTHTTP:")) {
                try {
                    val o = JSONObject(l.removePrefix("#EXTHTTP:"))
                    o.keys().forEach { k -> hdrs[k] = o.getString(k) }
                } catch (e: Exception) { }
            } else if (l.startsWith("#EXTMIRROR:")) {
                l.removePrefix("#EXTMIRROR:").trim().takeIf { it.isNotEmpty() }?.let { mirrors.add(it) }
            } else if (l.startsWith("#EXTGRP:")) {
                extGrp = l.removePrefix("#EXTGRP:").trim()
            } else if (l.isNotEmpty() && !l.startsWith("#")) {
                var u = l
                val bar = u.indexOf('|')
                if (bar > 0) {
                    val h = u.substring(bar + 1)
                    u = u.substring(0, bar)
                    h.split("&").forEach { part ->
                        val i = part.indexOf('=')
                        if (i > 0) {
                            val v = part.substring(i + 1)
                            hdrs[part.substring(0, i)] = try { URLDecoder.decode(v, "UTF-8") } catch (e: Exception) { v }
                        }
                    }
                }
                if (validUrl(u) && !License.allowsTier(tiersNow, tier)) {
                    // канал не по подписке: не показываем, но запоминаем — раздел покажет карточку «Ещё N каналов по подписке»
                    res.getOrPut(group) { mutableListOf() }
                    hidden.getOrPut(group) { Hid() }.also { it.count++; it.tiers = it.tiers or (1 shl tier) }
                } else if (validUrl(u)) res.getOrPut(group) { mutableListOf() }.add(Entry(name.ifEmpty { u }, u, pl(group), pl(id), pl(logo), if (hdrs.isEmpty()) emptyMap() else HashMap(hdrs), poster, pl(cu), cuDays, pl(cuSrc), if (mirrors.isEmpty()) emptyList() else ArrayList(mirrors), tier, kind, rating, country, age))
                name = ""; group = NO_GROUP; id = ""; logo = ""; hdrs = HashMap(); mirrors = ArrayList()
                cu = ""; cuDays = 0; cuSrc = ""; tier = 0; kind = ""; rating = ""; country = ""; age = ""
            }
        }
        return Parsed(res, epg, hidden)
    }
}
