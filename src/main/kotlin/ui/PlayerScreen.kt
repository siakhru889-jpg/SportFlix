@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import com.sportflix.iptv.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun createVlc(): EmbeddedMediaPlayerComponent? = try {
    if (NativeDiscovery().discover()) EmbeddedMediaPlayerComponent() else null
} catch (t: Throwable) { null }

private fun fmt(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun MissingVlc(onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Bg), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Для воспроизведения нужен VLC (64-разрядная версия)", color = Color.White, fontSize = 20.sp)
        Text("Установите VLC с videolan.org и запустите приложение снова.", color = Gray, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 20.dp)) {
            Button(onClick = { AppModel.openBrowser("https://www.videolan.org/vlc/download-windows.html") }) { Text("Скачать VLC") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onClose) { Text("Назад") }
        }
    }
}

@Composable
private fun ListDialog(title: String, items: List<String>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            if (items.isEmpty()) Text("Нет вариантов")
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(items) { i, t -> TextButton(onClick = { onPick(i); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text(t, modifier = Modifier.fillMaxWidth()) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@Composable
fun PlayerScreen(req: PlayRequest, onClose: () -> Unit) {
    val vlc = remember { createVlc() }
    if (vlc == null) { MissingVlc(onClose); return }
    val player = vlc.mediaPlayer()

    var idx by remember { mutableStateOf(req.queue.indexOfFirst { it.url == req.entry.url }.coerceAtLeast(0)) }
    val entry = req.queue.getOrNull(idx) ?: req.entry
    var arch by remember { mutableStateOf(req.arch) }
    var mirror by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    var pos by remember { mutableStateOf(0L) }
    var len by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(1f) }
    var fullscreen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var scaleMode by remember { mutableStateOf(Store.videoScale) }
    var tick by remember { mutableStateOf(0) }
    val vod = entry.poster || arch != null

    fun next(d: Int) { val n = idx + d; if (n in req.queue.indices) { arch = null; mirror = 0; idx = n } }
    fun toggleFs() { fullscreen = !fullscreen; AppModel.windowState.placement = if (fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating }
    fun applyScale(m: Int) {
        scaleMode = m; Store.videoScale = m
        val v = player.video()
        val w = maxOf(1, vlc.videoSurfaceComponent().width); val h = maxOf(1, vlc.videoSurfaceComponent().height)
        when (m) {
            0 -> { v.setCropGeometry(null); v.setAspectRatio(null); v.setScale(0f) }
            1 -> { v.setCropGeometry(null); v.setScale(0f); v.setAspectRatio("$w:$h") }
            2 -> { v.setAspectRatio(null); v.setScale(0f); v.setCropGeometry("$w:$h") }
            3 -> { v.setAspectRatio(null); v.setCropGeometry(null); v.setScale(1f) }
            4 -> { v.setCropGeometry(null); v.setScale(0f); v.setAspectRatio("16:9") }
            else -> { v.setCropGeometry(null); v.setScale(0f); v.setAspectRatio("4:3") }
        }
    }

    // ошибка потока: пробуем запасной адрес, иначе показываем сообщение; конец серии — следующая
    DisposableEffect(Unit) {
        val l = object : MediaPlayerEventAdapter() {
            override fun error(mp: MediaPlayer) {
                AppModel.scope.launch {
                    if (mirror < entry.mirrors.size) mirror++ else status = "Не удалось открыть: " + (if (entry.poster) "серия" else "канал")
                }
            }
            override fun finished(mp: MediaPlayer) { if (vod) AppModel.scope.launch { if (entry.poster) next(1) } }
        }
        player.events().addMediaPlayerEventListener(l)
        onDispose { player.events().removeMediaPlayerEventListener(l) }
    }

    // запуск потока
    LaunchedEffect(entry.url, arch, mirror) {
        status = ""
        val sources = listOf(entry.url) + entry.mirrors
        val src = sources.getOrElse(mirror) { entry.url }
        val a = arch
        val url: String? = when {
            a != null -> Catchup.url(entry, a)
            Resolver.isDynamic(src) -> withContext(Dispatchers.IO) { runCatching { Resolver.resolve(src, entry.headers) }.getOrNull() }
            else -> src
        }
        if (url == null) { status = "Не удалось получить ссылку на поток"; return@LaunchedEffect }
        if (!entry.poster) Store.pushRecentChannel(entry.url)
        val ua = entry.headers["User-Agent"] ?: Store.userAgent.ifBlank { "VLC/3.0.20 LibVLC/3.0.20" }
        val opts = ArrayList<String>()
        opts.add(":http-user-agent=$ua")
        entry.headers["Referer"]?.let { opts.add(":http-referrer=$it") }
        opts.add(":network-caching=2000")
        opts.add(":vout=direct3d11")
        player.media().play(url, *opts.toTypedArray())
        player.audio().setVolume((volume * 100).toInt())
        applyScale(scaleMode)
    }

    // состояние плеера, сохранение места просмотра
    LaunchedEffect(entry.url) {
        var lastSave = 0L; var resumed = false
        while (true) {
            pos = player.status().time(); len = player.status().length(); isPlaying = player.status().isPlaying
            val now = System.currentTimeMillis()
            if (entry.poster && arch == null && len > 0) {
                if (!resumed) { resumed = true; Store.watch(entry.url)?.takeIf { !it.done && it.pos > 20_000 }?.let { player.controls().setTime(it.pos) } }
                if (now - lastSave > 5000) { lastSave = now; Store.saveProgress(entry, pos, len) }
            }
            delay(500)
        }
    }

    // клавиатура: пробел — пауза, ◀ ▶ — перемотка, ▲ ▼ — громкость, F — полный экран, Esc — выход
    DisposableEffect(Unit) {
        AppModel.onKey = { ev ->
            if (ev.type != KeyEventType.KeyDown) false else when (ev.key) {
                Key.Spacebar, Key.K -> { player.controls().pause(); true }
                Key.DirectionRight -> { player.controls().skipTime(10_000); true }
                Key.DirectionLeft -> { player.controls().skipTime(-10_000); true }
                Key.DirectionUp -> { volume = (volume + 0.05f).coerceAtMost(1.5f); player.audio().setVolume((volume * 100).toInt()); true }
                Key.DirectionDown -> { volume = (volume - 0.05f).coerceAtLeast(0f); player.audio().setVolume((volume * 100).toInt()); true }
                Key.F -> { toggleFs(); true }
                Key.N, Key.PageDown -> { next(1); true }
                Key.P, Key.PageUp -> { next(-1); true }
                Key.Escape -> { if (fullscreen) toggleFs() else onClose(); true }
                else -> false
            }
        }
        onDispose { AppModel.onKey = null }
    }
    DisposableEffect(Unit) {
        onDispose {
            AppModel.windowState.placement = WindowPlacement.Floating
            try { player.controls().stop() } catch (e: Throwable) { }
            vlc.release()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        SwingPanel(factory = { vlc }, modifier = Modifier.weight(1f).fillMaxWidth())
        Column(Modifier.fillMaxWidth().background(Color(0xFF14141A)).padding(horizontal = 12.dp, vertical = 6.dp)) {
            // что идёт сейчас и дальше
            val now = if (entry.poster) null else Epg.now(entry)
            val nxt = if (entry.poster) null else Epg.next(entry)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.name, color = Color.White, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                if (status.isNotEmpty()) Text(status, color = Color(0xFFFF6B6B), fontSize = 13.sp)
            }
            if (now != null) Text(
                hm.format(Date(now.start)) + "–" + hm.format(Date(now.stop)) + "  " + now.title + (nxt?.let { "     Далее " + hm.format(Date(it.start)) + "  " + it.title } ?: ""),
                color = Gray, fontSize = 12.sp, maxLines = 1
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Назад", tint = Color.White) }
                IconButton(onClick = { next(-1) }, enabled = idx > 0) { Icon(Icons.Default.SkipPrevious, "Предыдущий", tint = Color.White) }
                IconButton(onClick = { player.controls().pause() }) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Пауза", tint = Color.White) }
                IconButton(onClick = { next(1) }, enabled = idx < req.queue.size - 1) { Icon(Icons.Default.SkipNext, "Следующий", tint = Color.White) }
                Text(fmt(pos), color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
                Slider(
                    value = if (len > 0) (pos.toFloat() / len).coerceIn(0f, 1f) else 0f,
                    onValueChange = { if (len > 0) { player.controls().setTime((it * len).toLong()); pos = (it * len).toLong() } },
                    enabled = vod && len > 0, modifier = Modifier.weight(1f)
                )
                Text(if (vod) fmt(len) else "ЭФИР", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
                Icon(Icons.Default.VolumeUp, null, tint = Color.White)
                Slider(value = volume, onValueChange = { volume = it; player.audio().setVolume((it * 100).toInt()) }, valueRange = 0f..1.5f, modifier = Modifier.width(110.dp))
                TextButton(onClick = { dialog = "audio" }) { Text("Звук") }
                TextButton(onClick = { dialog = "subs" }) { Text("Субтитры") }
                TextButton(onClick = { dialog = "scale" }) { Text("Масштаб") }
                if (!entry.poster) TextButton(onClick = { dialog = "epg" }) { Text("Программа") }
                if (!entry.poster) {
                    val fav = entry.url in Store.favs()
                    IconButton(onClick = { Store.toggleFav(entry.url); tick++ }) {
                        Icon(if (fav) Icons.Default.Star else Icons.Outlined.StarBorder, "Избранное", tint = if (fav) Color(0xFFFFC107) else Gray)
                    }
                }
                IconButton(onClick = { toggleFs() }) { Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Полный экран", tint = Color.White) }
            }
        }
    }

    when (dialog) {
        "audio" -> { val t = player.audio().trackDescriptions(); ListDialog("Звуковая дорожка", t.map { it.description() }, { player.audio().setTrack(t[it].id()) }) { dialog = null } }
        "subs" -> { val t = player.subpictures().trackDescriptions(); ListDialog("Субтитры", t.map { it.description() }, { player.subpictures().setTrack(t[it].id()) }) { dialog = null } }
        "scale" -> ListDialog("Масштаб видео", Store.SCALE_NAMES, { applyScale(it) }) { dialog = null }
        "epg" -> {
            val nowT = System.currentTimeMillis()
            val depth = if (Catchup.supported(entry)) Catchup.days(entry) * 24 * 3600_000L else 0L
            val progs = Epg.list(entry).filter { it.stop > nowT - depth }.take(150)
            val labels = progs.map { pr ->
                val live = nowT >= pr.start && nowT < pr.stop
                val mark = when { live -> "● "; pr.start < nowT && Catchup.available(entry, pr, nowT) -> "▶ "; else -> "" }
                mark + SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(pr.start)) + "  " + pr.title
            }
            ListDialog("Программа: " + entry.name, labels, { i ->
                val pr = progs[i]; val t = System.currentTimeMillis()
                when {
                    t >= pr.start && t < pr.stop -> { arch = null; mirror = 0 }
                    pr.start < t && Catchup.available(entry, pr, t) -> arch = pr
                }
            }) { dialog = null }
        }
    }
}
