@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sportflix.iptv.*
import com.sportflix.iptv.SeriesCatalog.KEY_CAT
import com.sportflix.iptv.SeriesCatalog.KEY_SER
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Bg = Color(0xFF0F0F14)
val Card = Color(0xFF1B1B23)
val Red = Color(0xFFE50914)
val Gray = Color(0xFF9A9AA8)
private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
fun Browser() {
    DisposableEffect(Unit) {
        AppModel.onKey = { ev -> if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) { goBack(); true } else false }
        onDispose { AppModel.onKey = null }
    }
    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar()
        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp)) { Content() }
    }
    if (AppModel.showSettings) SettingsDialog { AppModel.showSettings = false }
}

@Composable
private fun TopBar() {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("SPORTFLIX IPTV", color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp)
        Spacer(Modifier.width(28.dp))
        Config.sources.forEachIndexed { i, s ->
            val sel = AppModel.tab == i
            Box(
                Modifier.padding(end = 8.dp).clip(RoundedCornerShape(20.dp)).background(if (sel) Red else Card)
                    .clickable {
                        AppModel.tab = i; Store.lastTab = i
                        AppModel.group = null; AppModel.season = null; AppModel.query = ""
                        AppModel.loadTab(i)
                    }.padding(horizontal = 20.dp, vertical = 8.dp)
            ) { Text(s.name, color = Color.White, fontWeight = FontWeight.Medium) }
        }
        Spacer(Modifier.weight(1f))
        OutlinedTextField(
            value = AppModel.query, onValueChange = { AppModel.query = it },
            placeholder = { Text("Поиск", color = Gray) }, singleLine = true, modifier = Modifier.width(300.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
        )
        IconButton(onClick = { AppModel.showSettings = true }) { Icon(Icons.Default.Settings, "Настройки", tint = Color.White) }
    }
}

// ---------- навигация ----------
private fun titleOf(g: String): String = when {
    g.startsWith(KEY_SER) -> g.substringAfter('\u0002')
    g.startsWith(KEY_CAT) -> SeriesCatalog.catTitle(g.removePrefix(KEY_CAT))
    else -> g
}

private fun parentOf(g: String): String? = if (g.startsWith(KEY_SER)) KEY_CAT + g.removePrefix(KEY_SER).substringBefore('\u0002') else null

fun goBack() {
    when {
        AppModel.query.isNotBlank() -> AppModel.query = ""
        AppModel.season != null -> AppModel.season = null
        AppModel.group != null -> { AppModel.group = AppModel.group?.let { parentOf(it) }; AppModel.season = null }
    }
}

@Composable
private fun Content() {
    val src = Config.sources.getOrNull(AppModel.tab) ?: return
    val p = AppModel.parsed[src.url]
    AppModel.tick  // подписка на перерисовку
    if (p == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (AppModel.loading) CircularProgressIndicator(color = Red) else Text("Не удалось загрузить плейлист", color = Gray)
        }
        return
    }
    val g = AppModel.group
    val q = AppModel.query.trim()
    Column(Modifier.fillMaxSize()) {
        if (g != null || q.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                IconButton(onClick = { goBack() }) { Icon(Icons.Default.ArrowBack, "Назад", tint = Color.White) }
                val t = if (q.isNotEmpty()) "Поиск" + (g?.let { " в «" + titleOf(it) + "»" } ?: "")
                else titleOf(g!!) + (AppModel.season?.let { " · " + Seasons.label(it) } ?: "")
                Text(t, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        when {
            q.isNotEmpty() -> SearchView(p, src.movies, q, g)
            g == null -> MainPage(p, src.movies)
            else -> GroupView(p, src.movies, g)
        }
    }
}

// ---------- главная страница ----------
@Composable
private fun MainPage(p: Playlist.Parsed, movies: Boolean) {
    val vis = p.groups.filterKeys { it != Playlist.NO_GROUP }
    val cat = if (movies && Store.seriesFolder) SeriesCatalog.catalog(p) else null
    val absorbed = cat?.second ?: emptySet()
    val folders = ArrayList<Triple<String, String, String>>()   // ключ, название, число
    val favs = Store.favs()
    val favCount = vis.values.sumOf { l -> l.count { it.url in favs } }
    if (favCount > 0) folders.add(Triple(AppModel.FAV, AppModel.FAV, favCount.toString()))
    val recent = Store.recent()
    if (movies && recent.isNotEmpty()) folders.add(Triple(AppModel.RESUME, AppModel.RESUME, recent.size.toString()))
    val catRows = cat?.first?.map { (cn, m) -> Triple(KEY_CAT + cn, SeriesCatalog.catTitle(cn), m.size.toString()) } ?: emptyList()
    val cartoonKey = vis.keys.firstOrNull { it !in absorbed && it.lowercase().contains("мультфильм") }
    if (catRows.isNotEmpty() && cartoonKey == null) folders.addAll(catRows)
    vis.forEach { (k, v) ->
        if (k in absorbed) return@forEach
        folders.add(Triple(k, k, if (v.isEmpty()) "🔒" else v.size.toString()))
        if (catRows.isNotEmpty() && k == cartoonKey) folders.addAll(catRows)
    }
    LazyVerticalGrid(columns = GridCells.Adaptive(260.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(folders.size) { i ->
            val (key, title, count) = folders[i]
            FolderCard(title, count) { AppModel.group = key; AppModel.season = null }
        }
    }
}

@Composable
fun FolderCard(title: String, count: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(count, color = Gray, fontSize = 15.sp)
    }
}

// ---------- содержимое раздела ----------
@Composable
private fun GroupView(p: Playlist.Parsed, movies: Boolean, g: String) {
    val all = p.groups.filterKeys { it != Playlist.NO_GROUP }
    when {
        g.startsWith(KEY_CAT) -> CategoryView(p, g.removePrefix(KEY_CAT))
        g.startsWith(KEY_SER) -> SeriesView(p, g)
        g == AppModel.FAV -> { val f = Store.favs(); EntriesView(all.values.flatten().filter { it.url in f }, movies, g) }
        g == AppModel.RESUME -> {
            val byUrl = all.values.flatten().associateBy { it.url }
            EntriesView(Store.recent().mapNotNull { byUrl[it.url] }, movies, g)
        }
        else -> EntriesView(all[g] ?: emptyList(), movies, g, randomLabel = if (movies) randomFilmLabel(g) else null, hidden = p.hidden[g])
    }
}

private fun randomFilmLabel(g: String): String? {
    val n = g.lowercase()
    return when { n.contains("мультфильм") -> "🎲 Случайный мультфильм"; n.contains("фильм") -> "🎲 Случайный фильм"; else -> null }
}

@Composable
private fun CategoryView(p: Playlist.Parsed, cn: String) {
    val m = SeriesCatalog.catalog(p).first[cn] ?: emptyMap()
    val one = when (cn) { "Мультсериалы" -> "мультсериал"; "Документальные сериалы" -> "документальный сериал"; else -> "сериал" }
    LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ActionRow("🎲 Случайный $one", "Непросмотрено: " + m.count { (_, l) -> l.any { Store.watch(it.url)?.done != true } }) {
                val pool = m.filter { (_, l) -> l.any { Store.watch(it.url)?.done != true } }
                if (pool.isNotEmpty()) {
                    val (name, list) = pool.entries.random().let { it.key to it.value }
                    val ordered = Seasons.split(list)?.values?.flatten() ?: list
                    val start = ordered.firstOrNull { Store.watch(it.url)?.done != true } ?: ordered.first()
                    AppModel.play(start, ordered, name)
                }
            }
        }
        val keys = m.keys.toList()
        items(keys.size) { i ->
            val k = keys[i]; val v = m.getValue(k)
            PosterCard(cover = SeriesCatalog.coverOf(v), title = k, badge = "${v.size} серий", series = true) {
                AppModel.group = KEY_SER + cn + "\u0002" + k; AppModel.season = null
            }
        }
    }
}

@Composable
private fun SeriesView(p: Playlist.Parsed, key: String) {
    val rest = key.removePrefix(KEY_SER)
    val entries = SeriesCatalog.catalog(p).first[rest.substringBefore('\u0002')]?.get(rest.substringAfter('\u0002')) ?: emptyList()
    val name = rest.substringAfter('\u0002')
    val seasons = Seasons.split(entries)
    val os = AppModel.season
    if (seasons != null && (os == null || !seasons.containsKey(os))) {
        LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val ks = seasons.keys.toList()
            items(ks.size) { i ->
                val n = ks[i]; val l = seasons.getValue(n)
                val d = l.count { Store.watch(it.url)?.done == true }
                PosterCard(cover = SeriesCatalog.coverOf(l), title = Seasons.label(n), badge = "${l.size} серий", series = true, lookup = name,
                    progress = if (d in 1 until l.size) d * 100 / l.size else -1, watched = d == l.size) { AppModel.season = n }
            }
        }
    } else {
        val list = if (seasons != null) seasons[os] ?: emptyList() else entries
        val label = if (seasons != null) Seasons.label(os!!) else name
        EntriesView(list, true, name, markLabel = label, forceRows = true)
    }
}

@Composable
private fun SearchView(p: Playlist.Parsed, movies: Boolean, q: String, g: String?) {
    val all = p.groups.filterKeys { it != Playlist.NO_GROUP }
    val scope: List<Entry> = when {
        g == null || g == AppModel.RESUME -> all.values.flatten()
        g == AppModel.FAV -> { val f = Store.favs(); all.values.flatten().filter { it.url in f } }
        g.startsWith(KEY_SER) -> SeriesCatalog.catalog(p).first[g.removePrefix(KEY_SER).substringBefore('\u0002')]?.get(g.substringAfter('\u0002')) ?: emptyList()
        g.startsWith(KEY_CAT) -> SeriesCatalog.catalog(p).first[g.removePrefix(KEY_CAT)]?.values?.flatten() ?: emptyList()
        else -> all[g] ?: emptyList()
    }
    val ql = q.lowercase()
    EntriesView(scope.filter { it.name.lowercase().contains(ql) }.take(500), movies, "Поиск")
}

// ---------- списки и карточки ----------
@Composable
fun EntriesView(
    entries: List<Entry>, movies: Boolean, title: String, randomLabel: String? = null, markLabel: String? = null,
    forceRows: Boolean = false, hidden: Playlist.Hid? = null
) {
    val posterMode = movies && Store.chView == 4 && !forceRows
    LazyVerticalGrid(
        columns = if (posterMode) GridCells.Adaptive(160.dp) else GridCells.Adaptive(420.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (randomLabel != null) item(span = { GridItemSpan(maxLineSpan) }) {
            ActionRow(randomLabel, "Непросмотрено: " + entries.count { Store.watch(it.url)?.done != true }) {
                val pool = entries.filter { Store.watch(it.url)?.done != true }
                if (pool.isNotEmpty()) pool.random().let { AppModel.play(it, listOf(it), title) }
            }
        }
        if (markLabel != null && entries.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            val done = entries.count { Store.watch(it.url)?.done == true }
            val all = done == entries.size
            ActionRow(if (all) "↩ Снять отметки просмотра: $markLabel" else "✓ Отметить просмотренным: $markLabel", "Просмотрено $done из ${entries.size}") {
                Store.markAll(entries, !all); AppModel.tick++
            }
        }
        items(entries.size) { i ->
            val e = entries[i]
            if (posterMode && e.poster && !Meta.isEpisode(e)) {
                val w = Store.watch(e.url)
                PosterCard(cover = e, progress = if (w != null && !w.done && w.dur > 0) (w.pos * 100 / w.dur).toInt() else -1, watched = w?.done == true) {
                    AppModel.play(e, entries, title)
                }
            } else EntryRow(e) { AppModel.play(e, entries, title) }
        }
        if (hidden != null && hidden.count > 0) item(span = { GridItemSpan(maxLineSpan) }) {
            val need = when (hidden.tiers) { 1 shl License.T_SPORT -> "Спорт"; 1 shl License.T_MEDIA -> "Медиатека"; else -> "Премиум" }
            ActionRow("🔒 Больше ${if (movies) "фильмов и сериалов" else "каналов"} по подписке «$need»", "Скрыто: ${hidden.count} · нажмите, чтобы подключить") { AppModel.showSettings = true }
        }
    }
}

@Composable
fun ActionRow(title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text(sub, color = Gray, fontSize = 13.sp)
    }
}

/** Строка канала или серии. */
@Composable
fun EntryRow(e: Entry, onClick: () -> Unit) {
    AppModel.tick
    val now = if (e.poster) null else Epg.now(e)
    val w = if (e.poster) Store.watch(e.url) else null
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(72.dp, 48.dp)) { AsyncImage(Epg.logos(e).firstOrNull() ?: e.logo, Modifier.fillMaxSize(), ContentScale.Fit) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text((if (w?.done == true) "✓ " else "") + e.name, color = Color.White, fontSize = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (now != null) {
                Text(hm.format(Date(now.start)) + "–" + hm.format(Date(now.stop)) + "  " + now.title, color = Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val t = System.currentTimeMillis()
                LinearProgressIndicator(progress = { ((t - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp), color = Red, trackColor = Color(0xFF2B2B36))
            }
            if (w != null && !w.done && w.dur > 0) LinearProgressIndicator(progress = { (w.pos.toFloat() / w.dur).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp), color = Red, trackColor = Color(0xFF2B2B36))
        }
        if (!e.poster) {
            val fav = e.url in Store.favs()
            IconButton(onClick = { Store.toggleFav(e.url); AppModel.tick++ }) {
                Icon(if (fav) Icons.Default.Star else Icons.Outlined.StarBorder, "Избранное", tint = if (fav) Color(0xFFFFC107) else Gray)
            }
        }
    }
}

/** Карточка-афиша: постер, возраст, «Экранка», рейтинг, год, флаг и название. cover — запись, чей постер и данные показываем. */
@Composable
fun PosterCard(
    cover: Entry?, title: String? = null, badge: String? = null, series: Boolean = false, lookup: String? = null,
    progress: Int = -1, watched: Boolean = false, onClick: () -> Unit
) {
    if (cover == null) return
    AppModel.tick
    val m = remember(cover.url, cover.name, cover.rating, cover.country, cover.age) { Meta.of(cover) }
    val shownTitle = title ?: m.title
    val look = if (series) SeriesCatalog.searchTitle(lookup ?: shownTitle) else m.title
    val year = m.year.ifEmpty { if (series) Regex("\\(\\s*((?:19|20)\\d{2})\\s*\\)").find(shownTitle)?.groupValues?.get(1) ?: "" else "" }
    val x = Tmdb.cached(look, year, series)
    val rating = m.rating.ifEmpty { x?.rating ?: "" }
    val flag = m.flag.ifEmpty { if (x != null && x.country.isNotEmpty()) Meta.flagOf(x.country) else "" }
    val age = m.age.ifEmpty { x?.age ?: "" }
    LaunchedEffect(look, year, series) { if (x == null && (rating.isEmpty() || flag.isEmpty() || age.isEmpty())) Tmdb.request(look, year, series) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(4.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(Card)) {
            AsyncImage(Epg.logos(cover).firstOrNull() ?: cover.logo, Modifier.fillMaxSize())
            if (series && badge != null) Tag(badge, Color.White, Modifier.align(Alignment.TopStart).padding(8.dp))
            else if (m.ts) Tag("Экранка", Color(0xFFFFB74D), Modifier.align(Alignment.TopStart).padding(8.dp))
            if (age.isNotEmpty()) Tag(age, Color.White, Modifier.align(Alignment.BottomEnd).padding(8.dp))
            if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp), color = Red, trackColor = Color(0x66000000))
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (rating.isNotEmpty()) Text("★ $rating", color = Color(0xFF8C98FF), fontSize = 13.sp)
            if (year.isNotEmpty()) Text((if (rating.isNotEmpty()) "  " else "") + year, color = Color(0xFFC8C8D2), fontSize = 13.sp)
            if (flag.isNotEmpty()) Text("  $flag", fontSize = 13.sp)
        }
        Text((if (watched) "✓ " else "") + shownTitle, color = Color.White, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Tag(text: String, color: Color, modifier: Modifier) {
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)).padding(horizontal = 8.dp, vertical = 4.dp))
}
