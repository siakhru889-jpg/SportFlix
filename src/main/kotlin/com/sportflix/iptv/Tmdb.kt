package com.sportflix.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Автоматические данные о фильме с TMDB (themoviedb.org): рейтинг, страна и возрастное ограничение — для карточек афиши,
 * если их нет в плейлисте. Запрос идёт по названию и году, результат сохраняется на устройстве, повторно не запрашивается.
 * Нужен бесплатный ключ API (Настройки → Данные о фильмах с TMDB). Данные: TMDB — This product uses the TMDB API but is not endorsed or certified by TMDB.
 */
object Tmdb {
    /** Сюда можно вшить ключ — тогда данные подтягиваются у всех пользователей без настройки. */
    const val DEFAULT_KEY = "fec0beabd6cd153cba65d6f5813b00e3"

    class Extra(val rating: String, val country: String, val age: String)

    @Volatile var listener: (() -> Unit)? = null

    private val mem = ConcurrentHashMap<String, Extra>()
    private val missing = ConcurrentHashMap<String, Long>()
    private val pending: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap())
    private val pool = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(150), ThreadPoolExecutor.AbortPolicy())
    private var file: File? = null
    private var unsaved = 0

    fun apiKey(): String = Store.tmdbKey.ifBlank { DEFAULT_KEY }
    fun enabled(): Boolean = apiKey().isNotBlank()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "tmdb_cache.json")
        try {
            val t = file?.takeIf { it.exists() }?.readText() ?: return
            val o = JSONObject(t)
            o.keys().forEach { k -> val v = o.getJSONObject(k); mem[k] = Extra(v.optString("r"), v.optString("c"), v.optString("a")) }
        } catch (e: Exception) { }
    }

    private fun cacheKey(title: String, year: String, tv: Boolean) = (if (tv) "tv:" else "") + title.lowercase(Locale.ROOT).trim() + "|" + year

    fun cached(title: String, year: String, tv: Boolean = false): Extra? = mem[cacheKey(title, year, tv)]

    /** Запросить данные в фоне (если есть ключ, и фильма ещё нет в сохранённых). Результат приходит в listener. */
    fun request(title: String, year: String, tv: Boolean = false) {
        if (!enabled() || title.isBlank()) return
        val ck = cacheKey(title, year, tv)
        if (mem.containsKey(ck)) return
        val miss = missing[ck]
        if (miss != null && System.currentTimeMillis() - miss < 24 * 3600_000L) return
        if (!pending.add(ck)) return
        try { pool.execute { try { fetch(title, year, ck, tv) } finally { pending.remove(ck) } } }
        catch (e: RejectedExecutionException) { pending.remove(ck) }
    }

    /** Последний ответ TMDB (для проверки в настройках): «ок», «HTTP 401», «нет связи…». */
    @Volatile var lastStatus = ""

    private fun getOnce(url: String): JSONObject? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 8000
            val code = c.responseCode
            if (code != 200) { lastStatus = "HTTP $code"; return null }
            lastStatus = "ок"
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            lastStatus = "нет связи (" + e.javaClass.simpleName + ")"
            return null
        } finally { c.disconnect() }
    }

    /** Запрос с запасным адресом api.tmdb.org — основной в ряде сетей недоступен. */
    private fun get(url: String): JSONObject? =
        getOnce(url) ?: getOnce(url.replace("api.themoviedb.org", "api.tmdb.org"))

    private fun fetch(title: String, year: String, ck: String, tv: Boolean) {
        val api = apiKey()
        try {
            val q = URLEncoder.encode(title, "UTF-8")
            fun search(withYear: Boolean): Int {
                val u = if (tv) "https://api.themoviedb.org/3/search/tv?api_key=$api&language=ru-RU&query=$q" + (if (withYear && year.isNotEmpty()) "&first_air_date_year=$year" else "")
                else "https://api.themoviedb.org/3/search/movie?api_key=$api&language=ru-RU&query=$q" + (if (withYear && year.isNotEmpty()) "&year=$year" else "")
                return get(u)?.optJSONArray("results")?.optJSONObject(0)?.optInt("id", 0) ?: 0
            }
            var id = search(true)
            if (id == 0 && year.isNotEmpty()) id = search(false)
            if (id == 0) { missing[ck] = System.currentTimeMillis(); return }
            val d = get(if (tv) "https://api.themoviedb.org/3/tv/$id?api_key=$api&language=ru-RU&append_to_response=content_ratings"
                else "https://api.themoviedb.org/3/movie/$id?api_key=$api&language=ru-RU&append_to_response=release_dates")
            if (d == null) { missing[ck] = System.currentTimeMillis(); return }
            val rating = d.optDouble("vote_average", 0.0).takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.1f", it) } ?: ""
            val country = if (tv) (d.optJSONArray("origin_country")?.optString(0) ?: "")
                else (d.optJSONArray("production_countries")?.optJSONObject(0)?.optString("iso_3166_1") ?: "")
            val age = if (tv) tvCertification(d.optJSONObject("content_ratings")?.optJSONArray("results"))
                else certification(d.optJSONObject("release_dates")?.optJSONArray("results"))
            mem[ck] = Extra(rating, country, age)
            save()
            listener?.invoke()
        } catch (e: Exception) { missing[ck] = System.currentTimeMillis() }
    }

    /** Возраст: российская категория (0+, 6+, 12+, 16+, 18+), иначе американская (G, PG, PG-13, R, NC-17) в пересчёте. */
    private fun certification(arr: JSONArray?): String {
        if (arr == null) return ""
        fun find(code: String): String {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("iso_3166_1") != code) continue
                val rd = o.optJSONArray("release_dates") ?: continue
                for (j in 0 until rd.length()) { val c = rd.optJSONObject(j)?.optString("certification").orEmpty(); if (c.isNotBlank()) return c }
            }
            return ""
        }
        val ru = find("RU").trim()
        if (ru.isNotEmpty()) return if (ru.endsWith("+")) ru else "$ru+"
        return when (find("US").uppercase(Locale.ROOT)) { "G" -> "0+"; "PG" -> "6+"; "PG-13" -> "12+"; "R" -> "16+"; "NC-17" -> "18+"; else -> "" }
    }

    /** Возраст сериала: российская категория, иначе американская TV-рейтинг в пересчёте. */
    private fun tvCertification(arr: JSONArray?): String {
        if (arr == null) return ""
        fun find(code: String): String {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("iso_3166_1") == code) { val r = o.optString("rating"); if (r.isNotBlank()) return r }
            }
            return ""
        }
        val ru = find("RU").trim()
        if (ru.isNotEmpty()) return if (ru.endsWith("+")) ru else "$ru+"
        return when (find("US").uppercase(Locale.ROOT)) { "TV-Y", "TV-G" -> "0+"; "TV-Y7" -> "6+"; "TV-PG" -> "12+"; "TV-14" -> "16+"; "TV-MA" -> "18+"; else -> "" }
    }

    @Synchronized
    private fun save() {
        if (++unsaved < 25) return
        unsaved = 0
        flush()
    }

    /** Записывает накопленное на диск (вызывается при сохранении по счётчику и при закрытии экрана). */
    @Synchronized
    fun flush() {
        try {
            val o = JSONObject()
            mem.forEach { (k, v) -> o.put(k, JSONObject().put("r", v.rating).put("c", v.country).put("a", v.age)) }
            file?.writeText(o.toString())
        } catch (e: Exception) { }
    }
}
