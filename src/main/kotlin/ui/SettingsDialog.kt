@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sportflix.iptv.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsDialog(onClose: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var epgText by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var posterMode by remember { mutableStateOf(Store.chView == 4) }
    var seriesFolder by remember { mutableStateOf(Store.seriesFolder) }
    var every by remember { mutableStateOf(Store.epgEvery) }
    var status by remember { mutableStateOf(License.statusText()) }

    LaunchedEffect(Unit) { while (true) { epgText = if (Epg.running) Epg.status.ifBlank { "Подготовка…" } else ""; delay(500) } }

    AlertDialog(
        onDismissRequest = onClose, title = { Text("Настройки") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).widthIn(min = 460.dp)) {
                Text("ПОДПИСКА", color = Red, fontSize = 12.sp)
                Text("Статус: $status", modifier = Modifier.padding(vertical = 4.dp))
                if (License.tiersNow() == 0) {
                    Text("Подписки: «Спорт» — спортивные каналы, «Медиатека» — фильмы и сериалы, «Премиум» — всё. Код выдаётся после оплаты.", color = Gray, fontSize = 12.sp)
                    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Код подписки") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    Row(Modifier.padding(top = 6.dp)) {
                        Button(onClick = {
                            msg = "Проверяем код…"
                            AppModel.scope.launch(Dispatchers.IO) {
                                try {
                                    val r = License.activate(code)
                                    withContext(Dispatchers.Main) { msg = "Подписка активна: $r"; status = License.statusText(); AppModel.reloadAll() }
                                } catch (e: LicenseException) { withContext(Dispatchers.Main) { msg = e.message ?: "Ошибка" } }
                            }
                        }) { Text("Активировать") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { AppModel.openBrowser("https://t.me/SportFlix_iptv?direct") }) { Text("Купить подписку") }
                    }
                    if (msg.isNotEmpty()) Text(msg, color = Color(0xFFFFC107), modifier = Modifier.padding(top = 4.dp))
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("ПЛЕЙЛИСТЫ И ПРОГРАММА ПЕРЕДАЧ", color = Red, fontSize = 12.sp)
                Row(Modifier.padding(top = 6.dp)) {
                    Button(onClick = { AppModel.parsed.clear(); AppModel.group = null; Config.sources.indices.forEach { AppModel.loadTab(it, true) } }) { Text("Обновить плейлисты") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = !Epg.running, onClick = {
                        AppModel.scope.launch(Dispatchers.IO) { Epg.update(AppModel.ctx) { t, _ -> epgText = t }; withContext(Dispatchers.Main) { AppModel.tick++ } }
                    }) { Text("Обновить программу") }
                }
                if (epgText.isNotEmpty()) Text(epgText, color = Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Box(Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { menu = true }) { Text("Автообновление программы: " + Store.EPG_EVERY[every]) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        Store.EPG_EVERY.forEachIndexed { i, t -> DropdownMenuItem(text = { Text(t) }, onClick = { Store.epgEvery = i; every = i; menu = false }) }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("ВНЕШНИЙ ВИД", color = Red, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Switch(checked = posterMode, onCheckedChange = { posterMode = it; Store.chView = if (it) 4 else 0; AppModel.tick++ })
                    Text("  Афиша (постеры) у фильмов")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = seriesFolder, onCheckedChange = { seriesFolder = it; Store.seriesFolder = it; AppModel.group = null; AppModel.tick++ })
                    Text("  Папки «Сериалы», «Мультсериалы», «Документальные сериалы»")
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("Sportflix IPTV для Windows", color = Color.White)
                Text("Telegram: t.me/SportFlix_iptv · t.me/sportflix_news", color = Gray, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } }
    )
}
