import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ui.*

@Composable
fun App() {
    MaterialTheme(colorScheme = darkColorScheme(primary = Red, background = Bg, surface = Card)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            val req = AppModel.playing
            if (req != null) PlayerScreen(req) { AppModel.playing = null; AppModel.tick++ } else Browser()
        }
    }
}

fun main() {
    AppModel.init()
    application {
        val ws = rememberWindowState(width = 1280.dp, height = 800.dp)
        AppModel.windowState = ws
        androidx.compose.runtime.LaunchedEffect(Unit) { AppModel.start() }
        Window(
            onCloseRequest = ::exitApplication, state = ws, title = "Sportflix IPTV",
            onPreviewKeyEvent = { AppModel.onKey?.invoke(it) ?: false }
        ) { App() }
    }
}
