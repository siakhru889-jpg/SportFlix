package com.sportflix.iptv

/** Записи вида web:https://… — страницы-плееры; на ПК открываются в браузере. */
object Web {
    fun isWeb(u: String) = u.startsWith("web:", ignoreCase = true)
    fun url(u: String) = u.substring(4).trim().replace("&amp;", "&")
}
