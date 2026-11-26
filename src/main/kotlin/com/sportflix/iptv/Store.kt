package com.sportflix.iptv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Src(val name: String, val url: String, val movies: Boolean = false)

object Store {
    private lateinit var p: SharedPreferences

    fun init(c: Context) {
        if (!::p.isInitialized) p = c.applicationContext.getSharedPreferences("sf", Context.MODE_PRIVATE)
    }

    var epgUrl: String
        get() = p.getString("epgUrl", "") ?: ""
        set(v) { p.edit().putString("epgUrl", v.trim()).apply() }

    var epgUpdated: Long
        get() = p.getLong("epgUpdated", 0L)
        set(v) { p.edit().putLong("epgUpdated", v).apply() }

    var autoEpg: Set<String>
        get() = HashSet(p.getStringSet("autoEpg", emptySet()) ?: emptySet())
        set(v) { p.edit().putStringSet("autoEpg", HashSet(v)).apply() }

    /** Версия формата сохранённой программы: 2 — хранит и прошедшие передачи (архив). */
    val EPG_EVERY = arrayOf("При открытии приложения", "Раз в 6 часов", "Раз в 12 часов", "Раз в сутки", "Раз в 3 дня", "Раз в 5 дней", "Раз в 7 дней")
    private val EPG_EVERY_MS = longArrayOf(0L, 6, 12, 24, 72, 120, 168).map { it * 3600_000L }

    /** Как часто обновлять программу передач (индекс в EPG_EVERY), по умолчанию — при открытии. */
    var epgEvery: Int
        get() = p.getInt("epgEvery", 0).coerceIn(0, EPG_EVERY.size - 1)
        set(v) { p.edit().putInt("epgEvery", v).apply() }
    fun epgEveryMs(): Long = EPG_EVERY_MS[epgEvery]

    /** Последняя открытая вкладка (ТВ / Медиатека) — при следующем запуске открываем её же. */
    var lastTab: Int
        get() = p.getInt("lastTab", 0)
        set(v) { p.edit().putInt("lastTab", v).apply() }

    /** Размер текста: индекс в UiScale.NAMES (2 — «Нормальный»). */
    var textScale: Int
        get() = p.getInt("textScale", 2).coerceIn(0, 6)
        set(v) { p.edit().putInt("textScale", v).apply() }

    /** Масштаб экрана (размер всех элементов): индекс в UiScale.NAMES (2 — «Нормальный»). */
    var uiScale: Int
        get() = p.getInt("uiScale", 2).coerceIn(0, 6)
        set(v) { p.edit().putInt("uiScale", v).apply() }

    /** Тариф и срок кода из списка (0 — без срока) и когда список подтверждал код в последний раз (см. License). */
    var licTier: Int
        get() = p.getInt("licTier", 0)
        set(v) { p.edit().putInt("licTier", v).apply() }
    var licUntil: Long
        get() = p.getLong("licUntil", 0L)
        set(v) { p.edit().putLong("licUntil", v).apply() }
    var licOk: Long
        get() = p.getLong("licOk", 0L)
        set(v) { p.edit().putLong("licOk", v).apply() }

    /** Код подписки (см. License). */
    var licenseKey: String
        get() = p.getString("licenseKey", "") ?: ""
        set(v) { p.edit().putString("licenseKey", v).apply() }

    /** Самое позднее время, которое видели, — защита от перевода часов назад. */
    var lastSeen: Long
        get() = p.getLong("lastSeen", 0L)
        set(v) { p.edit().putLong("lastSeen", v).apply() }

    /** Все сериалы Медиатеки одной папкой «Сериалы» (по умолчанию включено). */
    var seriesFolder: Boolean
        get() = p.getBoolean("seriesFolder", true)
        set(v) { p.edit().putBoolean("seriesFolder", v).apply() }

    /** Ключ API TMDB (themoviedb.org) для автоматических рейтингов, стран и возраста фильмов. */
    var tmdbKey: String
        get() = p.getString("tmdbKey", "") ?: ""
        set(v) { p.edit().putString("tmdbKey", v).apply() }

    /** User-Agent, с которым сервер этого хоста сработал в прошлый раз (чтобы не перебирать заново). */
    fun uaFor(host: String): String? = if (host.isBlank()) null else p.getString("ua:$host", null)
    fun rememberUa(host: String, ua: String?) { if (host.isNotBlank() && !ua.isNullOrBlank() && uaFor(host) != ua) p.edit().putString("ua:$host", ua).apply() }

    /** Хост адреса потока (префиксы resolve:/web: отбрасываются). */
    fun hostOf(url: String): String {
        var u = url.trim()
        if (u.startsWith("resolve:", true)) u = u.substring(8).trim() else if (u.startsWith("web:", true)) u = u.substring(4).trim()
        return try { android.net.Uri.parse(u).host ?: "" } catch (e: Exception) { "" }
    }

    /** Масштаб видео в плеере: индекс в SCALE_NAMES. */
    val SCALE_NAMES = listOf("Поместить в экран", "Заполнить экран", "Увеличить за экран", "Оригинал", "16:9", "4:3")
    var videoScale: Int
        get() = p.getInt("videoScale", 0).coerceIn(0, 5)
        set(v) { p.edit().putInt("videoScale", v).apply() }

    var epgFmt: Int
        get() = p.getInt("epgFmt", 0)
        set(v) { p.edit().putInt("epgFmt", v).apply() }

    var userAgent: String
        get() = p.getString("ua", "") ?: ""
        set(v) { p.edit().putString("ua", v.trim()).apply() }

    /** "" — ещё не выбрано, "phone" или "tv" */
    var device: String
        get() = p.getString("device", "") ?: ""
        set(v) { p.edit().putString("device", v).apply() }

    val isTv: Boolean get() = device == "tv"

    var fullscreen: Boolean
        get() = p.getBoolean("fullscreen", false)
        set(v) { p.edit().putBoolean("fullscreen", v).apply() }

    var tiles: Boolean
        get() = p.getBoolean("tiles", true)
        set(v) { p.edit().putBoolean("tiles", v).apply() }

    val CH_VIEWS = arrayOf("Список", "Компактный", "Плитка", "Крупные карточки", "Афиша (постеры)")

    /** Вид каналов внутри разделов и в поиске: 0 список, 1 компактный, 2 плитка, 3 крупные карточки. */
    var chView: Int
        get() = p.getInt("chView", 4).coerceIn(0, 4)
        set(v) { p.edit().putInt("chView", v).apply() }

    var seekStep: Int
        get() = p.getInt("seekStep", 10)
        set(v) { p.edit().putInt("seekStep", v).apply() }

    var rev: Int
        get() = p.getInt("rev", 0)
        set(v) { p.edit().putInt("rev", v).apply() }

    fun favs(): Set<String> = HashSet(p.getStringSet("favs", emptySet()) ?: emptySet())

    fun toggleFav(url: String): Boolean {
        val s = HashSet(favs())
        val added = s.add(url)
        if (!added) s.remove(url)
        p.edit().putStringSet("favs", s).apply()
        return added
    }

    fun clearFavs() { p.edit().remove("favs").apply() }

    // ---------- настройки 1.6 ----------
    /** Автоматически включать следующую серию в конце фильма/серии. */
    var autoNext: Boolean
        get() = p.getBoolean("autoNext", true)
        set(v) { p.edit().putBoolean("autoNext", v).apply() }

    /** За сколько минут до начала передачи напоминать. */
    var remindLead: Int
        get() = p.getInt("remindLead", 10)
        set(v) { p.edit().putInt("remindLead", v).apply() }

    // ---------- история просмотра ----------
    /** Растёт при любом изменении истории — главный экран по нему понимает, что пора обновиться. */
    @Volatile var watchRev = 0

    private var watchMap: HashMap<String, Watch>? = null

    private fun wm(): HashMap<String, Watch> {
        watchMap?.let { return it }
        val m = HashMap<String, Watch>()
        try {
            val a = JSONArray(p.getString("watch", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val u = o.getString("u")
                m[u] = Watch(u, o.optString("n"), o.optString("g"), o.optLong("p"), o.optLong("d"), o.optLong("t"), o.optBoolean("x"))
            }
        } catch (e: Exception) { }
        watchMap = m
        return m
    }

    private fun saveWatch() {
        val m = wm()
        if (m.size > 300) m.values.sortedByDescending { it.ts }.drop(300).forEach { m.remove(it.url) }
        val a = JSONArray()
        m.values.forEach {
            a.put(JSONObject().put("u", it.url).put("n", it.name).put("g", it.group)
                .put("p", it.pos).put("d", it.dur).put("t", it.ts).put("x", it.done))
        }
        p.edit().putString("watch", a.toString()).apply()
        watchRev++
    }

    fun watch(url: String): Watch? = wm()[url]

    /** Недавно открытые каналы (адреса), самые свежие — первыми; для вкладки «Недавние каналы» в мультипросмотре. */
    fun recentChannelUrls(): List<String> = (p.getString("recentCh", "") ?: "").split('\n').filter { it.isNotBlank() }

    fun pushRecentChannel(url: String) {
        val l = ArrayList<String>(); l.add(url)
        recentChannelUrls().forEach { if (it != url && l.size < 40) l.add(it) }
        p.edit().putString("recentCh", l.joinToString("\n")).apply()
    }

    /** Недосмотренные фильмы и серии, самые свежие — первыми. */
    fun recent(): List<Watch> =
        wm().values.filter { !it.done && it.pos >= MIN_SAVE && it.dur > 0 }.sortedByDescending { it.ts }

    /** Запоминает место остановки. Если досмотрено почти до конца — ставит отметку «просмотрено». */
    fun saveProgress(e: Entry, pos: Long, dur: Long) {
        if (dur <= 0 || pos < 0) return
        val done = pos >= dur * 87 / 100 || dur - pos < 45_000L   // с 87% (пошли титры) — считается просмотренным, «Продолжить» не горит
        if (!done && pos < MIN_SAVE) return
        val w = if (done) Watch(e.url, e.name, e.group, 0L, dur, System.currentTimeMillis(), true)
                else Watch(e.url, e.name, e.group, pos, dur, System.currentTimeMillis(), false)
        val old = wm()[e.url]
        if (old != null && old.done == w.done && old.pos == w.pos && old.dur == w.dur) return
        wm()[e.url] = w
        saveWatch()
    }

    fun markWatched(e: Entry, done: Boolean) {
        if (done) wm()[e.url] = Watch(e.url, e.name, e.group, 0L, wm()[e.url]?.dur ?: 0L, System.currentTimeMillis(), true)
        else wm().remove(e.url)
        saveWatch()
    }

    /** Отмечает целиком список (сезон, сериал) просмотренным или снимает отметки — одним сохранением. */
    fun markAll(list: List<Entry>, done: Boolean) {
        val m = wm(); val now = System.currentTimeMillis()
        for (e in list) {
            if (done) m[e.url] = Watch(e.url, e.name, e.group, 0L, m[e.url]?.dur ?: 0L, now, true) else m.remove(e.url)
        }
        saveWatch()
    }

    fun clearWatch(url: String) { if (wm().remove(url) != null) saveWatch() }

    fun clearAllWatch() { wm().clear(); saveWatch() }

    /** Меньше 20 секунд просмотра в начале — не запоминаем, что фильм открывали. */
    const val MIN_SAVE = 20_000L

    // ---------- напоминания о передачах ----------
    fun reminders(): List<Reminder> {
        val out = ArrayList<Reminder>()
        try {
            val a = JSONArray(p.getString("rem", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Reminder(o.getString("u"), o.optString("n"), o.optString("i"), o.optString("h"), o.optString("t"), o.getLong("s")))
            }
        } catch (e: Exception) { }
        return out
    }

    private fun saveReminders(l: List<Reminder>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("u", it.url).put("n", it.name).put("i", it.tvgId).put("h", it.hdrs).put("t", it.title).put("s", it.start)) }
        p.edit().putString("rem", a.toString()).apply()
    }

    fun addReminder(r: Reminder) {
        val l = reminders().filterNot { it.url == r.url && it.start == r.start }
        saveReminders(l + r)
    }

    fun removeReminder(url: String, start: Long) {
        saveReminders(reminders().filterNot { it.url == url && it.start == start })
    }

    fun setReminders(l: List<Reminder>) = saveReminders(l)
}

data class Watch(val url: String, val name: String, val group: String, val pos: Long, val dur: Long, val ts: Long, val done: Boolean)

data class Reminder(val url: String, val name: String, val tvgId: String, val hdrs: String, val title: String, val start: Long) {
    val id: Int get() = (url + "|" + start).hashCode()
}

fun fmtClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(java.util.Locale.US, "%02d:%02d", m, sec)
}
