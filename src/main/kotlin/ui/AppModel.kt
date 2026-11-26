package ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.window.WindowState
import com.sportflix.iptv.*
import kotlinx.coroutines.*
import java.io.File

data class PlayRequest(val entry: Entry, val queue: List<Entry>, val title: String, val arch: Prog? = null)

object AppModel {
    const val FAV = "⭐ Избранное"
    const val RESUME = "▶ Продолжить просмотр"

    lateinit var ctx: Context
    lateinit var windowState: WindowState
    var onKey: ((KeyEvent) -> Boolean)? = null

    var tab by mutableStateOf(0)
    val parsed = mutableStateMapOf<String, Playlist.Parsed>()
    var loading by mutableStateOf(false)
    var group by mutableStateOf<String?>(null)
    var season by mutableStateOf<Int?>(null)
    var query by mutableStateOf("")
    var playing by mutableStateOf<PlayRequest?>(null)
    var showSettings by mutableStateOf(false)
    var tick by mutableStateOf(0)          // перерисовка после изменения настроек, отметок просмотра, данных TMDB

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun init() {
        val base = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "SportflixIPTV")
        ctx = Context(base)
        Store.init(ctx)
        Tmdb.init(ctx)
        ImageCache.dir = File(ctx.cacheDir, "img").also { it.mkdirs() }
        tab = Store.lastTab.coerceIn(0, maxOf(0, Config.sources.size - 1))
        var last = 0L
        Tmdb.listener = { val n = System.currentTimeMillis(); if (n - last > 700) { last = n; scope.launch { tick++ } } }
    }

    fun start() {
        loadTab(tab)
        scope.launch(Dispatchers.IO) {
            runCatching { Epg.load(ctx) }
            runCatching { autoUpdateEpg() }
            while (true) {
                delay(5 * 60_000L)
                if (License.recheckIfDue()) withContext(Dispatchers.Main) { reloadAll() }
            }
        }
    }

    /** Автообновление программы передач по расписанию из настроек. */
    private fun autoUpdateEpg() {
        if (Epg.urls().isEmpty() || Epg.running) return
        val now = System.currentTimeMillis()
        val every = Store.epgEveryMs()
        val missing = Epg.byId.isEmpty() || Store.epgFmt != 2
        val ranOut = Epg.coverageEnd() < now + 6 * 3600_000L && now - Store.epgUpdated > 3 * 3600_000L
        val due = if (every == 0L) true else (missing || ranOut || now - Store.epgUpdated >= every)
        if (due) { Epg.update(ctx); scope.launch { tick++ } }
    }

    fun loadTab(i: Int, force: Boolean = false) {
        val s = Config.sources.getOrNull(i) ?: return
        scope.launch {
            loading = parsed[s.url] == null
            if (!force && parsed[s.url] == null) {
                val c = withContext(Dispatchers.IO) { runCatching { Playlist.loadCached(ctx, s.url, s.movies) }.getOrNull() }
                if (c != null) { parsed[s.url] = c; loading = false }
            }
            val age = withContext(Dispatchers.IO) { runCatching { Playlist.ageMs(ctx, s.url) }.getOrDefault(Long.MAX_VALUE) }
            if (force || parsed[s.url] == null || age > 60 * 60_000L) {
                val f = withContext(Dispatchers.IO) { runCatching { Playlist.load(ctx, s.url, s.movies) }.getOrNull() }
                if (f != null) parsed[s.url] = f
            }
            loading = false
        }
    }

    /** Подписку включили или сняли — плейлисты перечитываются (закрытые каналы появляются или пропадают). */
    fun reloadAll() {
        parsed.clear(); group = null; season = null
        Config.sources.forEachIndexed { i, _ -> loadTab(i) }
    }

    fun openBrowser(url: String) { try { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } catch (e: Exception) { } }

    fun play(e: Entry, queue: List<Entry>, title: String, arch: Prog? = null) {
        if (Web.isWeb(e.url)) { openBrowser(Web.url(e.url)); return }
        if (!License.canWatch(e)) { showSettings = true; return }
        playing = PlayRequest(e, queue, title, arch)
    }
}
