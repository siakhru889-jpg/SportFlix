package android.content

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Замена Android SharedPreferences: настройки хранятся в JSON-файле в профиле пользователя. */
interface SharedPreferences {
    fun getString(k: String, d: String?): String?
    fun getInt(k: String, d: Int): Int
    fun getLong(k: String, d: Long): Long
    fun getBoolean(k: String, d: Boolean): Boolean
    fun getStringSet(k: String, d: Set<String>?): Set<String>?
    fun edit(): Editor

    interface Editor {
        fun putString(k: String, v: String?): Editor
        fun putInt(k: String, v: Int): Editor
        fun putLong(k: String, v: Long): Editor
        fun putBoolean(k: String, v: Boolean): Editor
        fun putStringSet(k: String, v: Set<String>?): Editor
        fun remove(k: String): Editor
        fun apply()
        fun commit(): Boolean { apply(); return true }
    }
}

class JsonPrefs(private val file: File) : SharedPreferences {
    private val map = LinkedHashMap<String, Any>()

    init {
        try {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                for (k in o.keys()) {
                    val v = o.get(k)
                    map[k] = if (v is JSONArray) (0 until v.length()).map { v.getString(it) }.toSet() else v
                }
            }
        } catch (e: Exception) { }
    }

    @Synchronized private fun save() {
        try {
            val o = JSONObject()
            map.forEach { (k, v) -> o.put(k, if (v is Set<*>) JSONArray(v.toList()) else v) }
            file.parentFile?.mkdirs()
            file.writeText(o.toString())
        } catch (e: Exception) { }
    }

    override fun getString(k: String, d: String?) = (map[k] as? String) ?: d
    override fun getInt(k: String, d: Int) = (map[k] as? Number)?.toInt() ?: d
    override fun getLong(k: String, d: Long) = (map[k] as? Number)?.toLong() ?: d
    override fun getBoolean(k: String, d: Boolean) = (map[k] as? Boolean) ?: d
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(k: String, d: Set<String>?) = (map[k] as? Set<String>) ?: d

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        override fun putString(k: String, v: String?) = apply { pending[k] = v }
        override fun putInt(k: String, v: Int) = apply { pending[k] = v }
        override fun putLong(k: String, v: Long) = apply { pending[k] = v }
        override fun putBoolean(k: String, v: Boolean) = apply { pending[k] = v }
        override fun putStringSet(k: String, v: Set<String>?) = apply { pending[k] = v }
        override fun remove(k: String) = apply { pending[k] = null }
        override fun apply() {
            synchronized(this@JsonPrefs) { pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v } }
            save()
        }
    }
}
