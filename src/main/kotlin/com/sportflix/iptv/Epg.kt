package com.sportflix.iptv

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

data class Prog(val start: Long, val stop: Long, val title: String, val desc: String)

class EpgException(msg: String) : Exception(msg)

object Epg {
    @Volatile var byId: Map<String, List<Prog>> = emptyMap()
    @Volatile private var nameToId: Map<String, String> = emptyMap()
    @Volatile private var icons: Map<String, String> = emptyMap()
    /** Автообновление: проверка при открытии уже была в этом запуске / время последней попытки (чтобы не долбить при ошибках). */
    @Volatile var openChecked = false
    @Volatile var lastAttempt = 0L
    private val busy = AtomicBoolean(false)
    /** Идёт ли сейчас обновление (общее для автообновления и ручного) и его текущая стадия. */
    val running: Boolean get() = busy.get()
    @Volatile var status = ""

    @Volatile var loaded = false

    fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun idFor(e: Entry): String? {
        if (e.poster) return null
        // tvg-id (а если его нет — tvg-name) сначала как id канала, потом как название в программе; затем название канала
        if (e.tvgId.isNotEmpty()) {
            if (byId.containsKey(e.tvgId)) return e.tvgId
            nameToId[norm(e.tvgId)]?.let { return it }
        }
        return nameToId[norm(e.name)]
    }

    fun list(e: Entry): List<Prog> = idFor(e)?.let { byId[it] } ?: emptyList()
    fun now(e: Entry, t: Long = System.currentTimeMillis()): Prog? = list(e).firstOrNull { t >= it.start && t < it.stop }
    fun next(e: Entry, t: Long = System.currentTimeMillis()): Prog? = list(e).firstOrNull { it.start >= t }

    /** Логотип канала: сначала tvg-logo из плейлиста, иначе из телепрограммы (EPG). */
    fun logo(e: Entry): String = logos(e).firstOrNull() ?: ""

    /** Список логотипов по приоритету: из плейлиста (tvg-logo), затем из телепрограммы. Следующий пробуется, если предыдущий не загрузился. */
    fun logos(e: Entry): List<String> {
        val id = idFor(e)
        val ic = if (id != null) icons[id] else null
        return listOfNotNull(e.logo, ic).filter { it.isNotBlank() }.distinct()
    }

    /** Сколько прошедших передач храним: для просмотра архива и «что было до эфира». */
    const val PAST_MS = 3 * 24 * 3600_000L
    /** Сколько вперёд сохраняем из источника. Раньше было 36 часов — при обновлении раз в несколько дней программа заканчивалась уже через день. */
    const val AHEAD_MS = 14 * 24 * 3600_000L

    /** До какого времени есть передачи (максимум по всем каналам); 0 — программы нет. */
    fun coverageEnd(): Long = byId.values.maxOfOrNull { l -> l.lastOrNull()?.stop ?: 0L } ?: 0L

    fun urls(): List<String> =
        (if (Store.epgUrl.isNotBlank()) listOf(Store.epgUrl) else Store.autoEpg.toList())
            .flatMap { it.split(",") }.map { it.trim() }.filter { it.startsWith("http") }.distinct()

    fun clear(ctx: Context) {
        File(ctx.filesDir, "epg.bin").delete()
        byId = emptyMap(); nameToId = emptyMap(); icons = emptyMap(); Store.epgUpdated = 0L
    }

    @Synchronized
    fun load(ctx: Context) {
        loaded = true
        val f = File(ctx.filesDir, "epg.bin")
        if (!f.exists()) return
        try {
            DataInputStream(BufferedInputStream(FileInputStream(f))).use { i ->
                val ver = i.readInt()
                if (ver != 1 && ver != 2) return
                val pool = HashMap<String, String>()
                fun pl(x: String) = pool.getOrPut(x) { x }
                val progs = HashMap<String, List<Prog>>()
                repeat(i.readInt()) {
                    val id = i.readUTF()
                    val n = i.readInt()
                    val l = ArrayList<Prog>(n)
                    repeat(n) { l.add(Prog(i.readLong(), i.readLong(), pl(i.readUTF()), pl(i.readUTF()))) }
                    progs[id] = l
                }
                val names = HashMap<String, String>()
                repeat(i.readInt()) { val n = i.readUTF(); names[n] = i.readUTF() }
                val ic = HashMap<String, String>()
                if (ver == 2) repeat(i.readInt()) { val k = i.readUTF(); ic[k] = i.readUTF() }
                byId = progs; nameToId = names; icons = ic
            }
        } catch (e: Throwable) {
            // не хватило памяти или файл повреждён — работаем без программы, а не падаем
            byId = emptyMap(); nameToId = emptyMap(); icons = emptyMap(); Store.epgUpdated = 0L
        }
    }

    /** Считает прочитанные байты — по ним показываем проценты установки. */
    private class CountingInputStream(i: InputStream) : FilterInputStream(i) {
        @Volatile var count = 0L
        override fun read(): Int { val r = super.read(); if (r >= 0) count++; return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int { val r = super.read(b, off, len); if (r > 0) count += r; return r }
    }

    /**
     * Загрузка и установка программы передач. cb(текст, проценты) вызывается при смене процента:
     * сначала «Загрузка…», затем «Установка…», затем «Сохранение…». В память попадают только каналы из ваших плейлистов.
     */
    fun update(ctx: Context, cb: (String, Int) -> Unit = { _, _ -> }): Result<Int> {
        if (!busy.compareAndSet(false, true)) return Result.failure(EpgException("Программа передач уже обновляется"))
        try {
            return doUpdate(ctx) { t, p -> status = t; cb(t, p) }
        } finally {
            busy.set(false); status = ""
        }
    }

    @Synchronized
    private fun doUpdate(ctx: Context, cb: (String, Int) -> Unit): Result<Int> = runCatching {
        val urls = urls()
        if (urls.isEmpty()) throw EpgException("Адрес программы передач не найден. Укажите его в настройках.")
        val now = System.currentTimeMillis()
        val keys = Playlist.epgKeys(ctx)
        val wantIds = keys?.first
        val wantNames = keys?.second
        val progs = HashMap<String, MutableList<Prog>>()
        val names = HashMap<String, String>()
        val iconMap = HashMap<String, String>()
        val tmp = File(ctx.cacheDir, "epg_dl.tmp")
        var ok = 0
        urls.forEachIndexed { n, u ->
            val sfx = if (urls.size > 1) " (${n + 1}/${urls.size})" else ""
            try {
                cb("Загрузка программы передач$sfx…", 0)
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 60000; c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "SportflixIPTV/1.4")
                val total = c.contentLengthLong
                var lastP = -1; var lastMb = -1L
                c.inputStream.use { i ->
                    tmp.outputStream().use { o ->
                        val buf = ByteArray(65536); var done = 0L; var r: Int
                        while (i.read(buf).also { r = it } > 0) {
                            o.write(buf, 0, r); done += r
                            if (total > 0) {
                                val p = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (p != lastP) { lastP = p; cb("Загрузка программы передач$sfx… $p%", p) }
                            } else {
                                val mb = done / (512 * 1024)
                                if (mb != lastMb) { lastMb = mb; cb("Загрузка программы передач$sfx… " + String.format(Locale.US, "%.1f", done / 1048576.0) + " МБ", 0) }
                            }
                        }
                    }
                }
                val size = tmp.length().coerceAtLeast(1L)
                cb("Установка программы передач$sfx… 0%", 0)
                var lastI = -1
                FileInputStream(tmp).use { fi ->
                    val cnt = CountingInputStream(fi)
                    parse(cnt, now, progs, names, iconMap, wantIds, wantNames) {
                        val p = (cnt.count * 100 / size).toInt().coerceIn(0, 100)
                        if (p != lastI) { lastI = p; cb("Установка программы передач$sfx… $p%", p) }
                    }
                }
                ok++
            } catch (t: Throwable) { }
        }
        tmp.delete()
        if (ok == 0) throw EpgException("Не удалось загрузить программу передач")
        cb("Сохранение…", 100)
        progs.values.forEach { l -> l.sortBy { it.start } }
        val usedNames = names.filterValues { progs.containsKey(it) }
        val usedIcons = iconMap.filterKeys { progs.containsKey(it) }
        save(ctx, progs, usedNames, usedIcons)
        byId = progs; nameToId = usedNames; icons = usedIcons
        Store.epgUpdated = now
        Store.epgFmt = 2
        progs.size
    }

    private fun save(ctx: Context, progs: Map<String, List<Prog>>, names: Map<String, String>, ic: Map<String, String>) {
        val tmp = File(ctx.filesDir, "epg.tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { o ->
            o.writeInt(2); o.writeInt(progs.size)
            for ((id, l) in progs) {
                o.writeUTF(id); o.writeInt(l.size)
                for (p in l) { o.writeLong(p.start); o.writeLong(p.stop); o.writeUTF(p.title); o.writeUTF(p.desc) }
            }
            o.writeInt(names.size)
            for ((n, id) in names) { o.writeUTF(n); o.writeUTF(id) }
            val okIcons = ic.filter { it.value.length < 2000 }
            o.writeInt(okIcons.size)
            for ((k, v) in okIcons) { o.writeUTF(k); o.writeUTF(v) }
        }
        tmp.renameTo(File(ctx.filesDir, "epg.bin"))
    }

    private fun parse(
        input: InputStream, now: Long,
        progs: HashMap<String, MutableList<Prog>>, names: HashMap<String, String>, icons: HashMap<String, String>,
        wantIds: Set<String>?, wantNames: Set<String>?, onTick: () -> Unit
    ) {
        val bis = BufferedInputStream(input, 65536)
        bis.mark(2)
        val b1 = bis.read(); val b2 = bis.read()
        bis.reset()
        val src: InputStream = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(bis, 65536) else bis
        val xp = Xml.newPullParser()
        xp.setInput(src, null)
        val f1 = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val f2 = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        fun t(s: String?): Long {
            if (s == null) return 0L
            var x = s.trim()
            if (x.length > 14 && x[14] != ' ') x = x.substring(0, 14) + " " + x.substring(14)
            return try { (if (x.length > 14) f1 else f2).parse(x)?.time ?: 0L } catch (e: Exception) { 0L }
        }
        val pool = HashMap<String, String>()
        fun pl(x: String) = pool.getOrPut(x) { x }
        val keep = HashSet<String>()
        var skip = false
        var ticks = 0
        var curCh: String? = null
        var inProg = false
        var pCh = ""; var pStart = 0L; var pStop = 0L; var pTitle = ""; var pDesc = ""
        var ev = xp.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if ((++ticks and 1023) == 0) onTick()
            if (ev == XmlPullParser.START_TAG) {
                when (xp.name) {
                    "channel" -> {
                        curCh = xp.getAttributeValue(null, "id")
                        // оставляем только каналы из плейлистов — остальные не нужны и занимают память
                        val c = curCh
                        if (c != null && (wantIds == null || c in wantIds || (wantNames != null && norm(c) in wantNames))) keep.add(c)
                    }
                    "display-name" -> {
                        val n = xp.nextText(); val c = curCh
                        if (c != null) {
                            val nn = norm(n)
                            if (wantNames != null && nn in wantNames) keep.add(c)
                            if (c in keep) names[nn] = c
                        }
                    }
                    "icon" -> {
                        val c = curCh
                        val srcUrl = xp.getAttributeValue(null, "src")
                        if (c != null && c in keep && !inProg && !srcUrl.isNullOrBlank() && !icons.containsKey(c)) icons[c] = srcUrl.trim()
                    }
                    "programme" -> {
                        inProg = true
                        pCh = xp.getAttributeValue(null, "channel") ?: ""
                        skip = pCh !in keep
                        pStart = t(xp.getAttributeValue(null, "start"))
                        pStop = t(xp.getAttributeValue(null, "stop"))
                        pTitle = ""; pDesc = ""
                    }
                    "title" -> if (inProg && !skip && pTitle.isEmpty()) pTitle = xp.nextText().trim()
                    "desc" -> if (inProg && !skip) pDesc = xp.nextText().trim().take(300)
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (xp.name) {
                    "channel" -> curCh = null
                    "programme" -> {
                        inProg = false
                        if (!skip && pCh.isNotEmpty() && pStop > now - PAST_MS && pStart < now + AHEAD_MS && pStart > 0L) {
                            // у прошедших передач описание не храним — экономим память
                            // описание нужно только идущей и ближайшим передачам
                            val keepDesc = pStop >= now && pStart < now + 36 * 3600_000L
                            progs.getOrPut(pCh) { ArrayList() }.add(Prog(pStart, pStop, pl(pTitle.take(200)), if (keepDesc) pl(pDesc.take(200)) else ""))
                        }
                    }
                }
            }
            ev = xp.next()
        }
    }
}
