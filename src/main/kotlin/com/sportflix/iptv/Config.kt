package com.sportflix.iptv

import android.util.Base64

object Config {
    private val KEY = "sfx#k3y!9".toByteArray()

    private fun d(s: String): String {
        val b = Base64.decode(s, Base64.NO_WRAP)
        for (i in b.indices) b[i] = (b[i].toInt() xor KEY[i % KEY.size].toInt()).toByte()
        return String(b, Charsets.UTF_8)
    }

    val sources: List<Src> by lazy {
        listOf(
            Src("ТВ", d("GxIMUxgJVg5dH0gcUQRDG05BBhUdUQhcF1VcHRJWQAReVlIWAwEIR11ZDUwKGw0NUB8HHhkJGVAJDDhDDVcXHlUNG1RBFUpcCltLGhhFF1UKHA0aTQFSCxAJAFUVWl9SDFYPVQIUHls=")),
            Src("Медиатека", d("GxIMUxgJVg5dH0gcUQRDG05BBhUdUQhcF1VcHRJWQAReVlIWCQsQSx1JSFZdQwQeW1lcHEVRSxZMDA1aFUxWBwMTQkVeSlQBTBQUSA5KREZcSxAQUQ0EDkNLGg0WQhNFG0kLEg9AQRIVHU0EQw=="), true)
        )
    }

    /** Ссылка на update.json в зашифрованном виде. Пусто = проверка обновлений выключена. */
    private const val UPD = "GxIMUxgJVg5dH0gcUQRDG05BBhUdUQhcF1VcHRJWQAReVlIWFgsLUl9fDE1KARBOVh9XTBRcSh4cDB5DHUBNFkgSUARdRlNVGAMBHlhdC1leRxYTTRJLF0RRARULEFNCDE1PEFdeRwcOSQ=="

    val updateUrl: String get() = if (UPD.isEmpty()) "" else d(UPD)
}
