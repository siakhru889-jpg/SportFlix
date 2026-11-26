package com.sportflix.iptv

/**
 * Данные карточки фильма, вынутые из записи плейлиста: год, возрастной рейтинг, рейтинг фильма, страна (флаг), отметка «Экранка».
 * Читаются из названия — «Жертва обстоятельств (2026) (18+) ★5.0 🇫🇷 (ts)» — и из атрибутов rating, country, age.
 */
object Meta {
    class Info(val title: String, val year: String, val age: String, val rating: String, val flag: String, val ts: Boolean)

    private val cache = HashMap<String, Info>()

    private val tsTag = Regex("(?i)[(\\[]\\s*(?:ts|тс|cam|camrip|камрип|экранка)\\s*[)\\]]")
    private val tsWord = Regex("(?i)(?<![\\p{L}\\d])(?:экранка|camrip|cam-rip)(?![\\p{L}\\d])")
    private val yearRe = Regex("[(\\[]\\s*((?:19|20)\\d{2})\\s*[)\\]]")
    private val ageRe = Regex("[(\\[]\\s*(\\d{1,2})\\s*\\+\\s*[)\\]]")
    private val ratingStar = Regex("[★⭐]\\s*(\\d{1,2}(?:[.,]\\d{1,2})?)")
    private val ratingWord = Regex("(?i)(?:imdb|кп|рейтинг)\\s*[:=]?\\s*(\\d{1,2}(?:[.,]\\d{1,2})?)")
    private val flagRe = Regex("[\\uD83C][\\uDDE6-\\uDDFF][\\uD83C][\\uDDE6-\\uDDFF]")

    private val countries = mapOf(
        "сша" to "US", "usa" to "US", "united states" to "US", "россия" to "RU", "russia" to "RU", "ссср" to "RU",
        "франция" to "FR", "france" to "FR", "великобритания" to "GB", "uk" to "GB", "англия" to "GB", "united kingdom" to "GB",
        "германия" to "DE", "germany" to "DE", "испания" to "ES", "италия" to "IT", "китай" to "CN", "япония" to "JP",
        "южная корея" to "KR", "корея" to "KR", "индия" to "IN", "канада" to "CA", "украина" to "UA", "турция" to "TR", "австралия" to "AU"
    )

    fun flagOf(country: String): String {
        val c = country.trim()
        val code = when {
            c.length == 2 && c.all { it.isLetter() } -> c.uppercase()
            else -> countries[c.lowercase()] ?: return ""
        }
        val a = Character.toChars(0x1F1E6 + (code[0] - 'A')); val b = Character.toChars(0x1F1E6 + (code[1] - 'A'))
        return String(a) + String(b)
    }

    private val epCache = HashMap<String, Boolean>()

    /** Серия сериала (в названии есть сезон или серия) — такие записи остаются строками списка, а не карточками-постерами. */
    fun isEpisode(e: Entry): Boolean {
        if (epCache.size > 20000) epCache.clear()
        return epCache.getOrPut(e.url + "|" + e.name) { Seasons.seasonOf(e.name) != null || Seasons.episodeOf(e.name) != null }
    }

    fun of(e: Entry): Info {
        if (cache.size > 6000) cache.clear()
        // ключ включает данные записи — после обновления плейлиста (новые rating/country/age) карточка перечитывается
        return cache.getOrPut(e.url + "|" + e.name + "|" + e.rating + "|" + e.country + "|" + e.age) { parse(e) }
    }

    private fun parse(e: Entry): Info {
        var rest = e.name
        val ts = tsTag.containsMatchIn(rest) || tsWord.containsMatchIn(rest) || tsTag.containsMatchIn(e.group)
        rest = rest.replace(tsTag, " ").replace(tsWord, " ")

        var year = ""
        yearRe.find(rest)?.let { year = it.groupValues[1]; rest = rest.replaceFirst(it.value, " ") }

        var age = e.age.trim().let { a -> if (a.isNotEmpty()) (if (a.endsWith("+")) a else "$a+") else "" }
        ageRe.find(rest)?.let { if (age.isEmpty()) age = it.groupValues[1] + "+"; rest = rest.replaceFirst(it.value, " ") }

        var rating = e.rating.trim().replace(',', '.')
        (ratingStar.find(rest) ?: ratingWord.find(rest))?.let { if (rating.isEmpty()) rating = it.groupValues[1].replace(',', '.'); rest = rest.replaceFirst(it.value, " ") }

        var flag = if (e.country.isNotBlank()) flagOf(e.country) else ""
        flagRe.find(rest)?.let { if (flag.isEmpty()) flag = it.value; rest = rest.replaceFirst(it.value, " ") }

        val title = rest.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', '|', ',', '.', ':', '·').ifBlank { e.name }
        return Info(title, year, age, rating, flag, ts)
    }
}
