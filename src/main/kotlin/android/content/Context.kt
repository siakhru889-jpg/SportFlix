package android.content

import java.io.File

/** Замена Android Context: папки данных в %APPDATA%\SportflixIPTV. */
open class Context(private val baseDir: File) {
    val filesDir: File = File(baseDir, "files").also { it.mkdirs() }
    val cacheDir: File = File(baseDir, "cache").also { it.mkdirs() }
    val applicationContext: Context get() = this
    val packageName: String = "com.sportflix.iptv"

    private val prefs = HashMap<String, SharedPreferences>()
    @Synchronized fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.getOrPut(name) { JsonPrefs(File(baseDir, "$name.json")) }

    companion object { const val MODE_PRIVATE = 0 }
}
