package ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest

/** Загрузка картинок (постеры, логотипы): кеш на диске и небольшой кеш в памяти. */
object ImageCache {
    lateinit var dir: File
    private val mem = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ImageBitmap>) = size > 160
    }
    private val sem = Semaphore(6)

    private fun key(u: String) = MessageDigest.getInstance("MD5").digest(u.toByteArray()).joinToString("") { "%02x".format(it) }

    @Synchronized fun peek(u: String): ImageBitmap? = mem[u]
    @Synchronized private fun put(u: String, b: ImageBitmap) { mem[u] = b }

    suspend fun load(u: String): ImageBitmap? {
        peek(u)?.let { return it }
        return withContext(Dispatchers.IO) {
            sem.withPermit {
                try {
                    val f = File(dir, key(u))
                    val bytes = if (f.exists() && f.length() > 0) f.readBytes() else {
                        val c = URL(u).openConnection()
                        c.connectTimeout = 8000; c.readTimeout = 12000
                        c.setRequestProperty("User-Agent", "Mozilla/5.0")
                        c.getInputStream().use { it.readBytes() }.also { f.writeBytes(it) }
                    }
                    val img = org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                    put(u, img)
                    img
                } catch (e: Throwable) { null }
            }
        }
    }
}

@Composable
fun AsyncImage(url: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    if (url.isNullOrBlank()) return
    var bmp by remember(url) { mutableStateOf(ImageCache.peek(url)) }
    LaunchedEffect(url) { if (bmp == null) bmp = ImageCache.load(url) }
    bmp?.let { Image(bitmap = it, contentDescription = null, modifier = modifier, contentScale = scale) }
}
