package com.sportflix.iptv

/** Разбивка сериала на сезоны по названиям серий: «Сезон 1», «1 сезон», «Season 1», «S01E03». */
object Seasons {
    private val seasonRe = listOf(
        Regex("""(?i)(?:сезон|season)\s*[:№#.\-]?\s*(\d{1,2})(?!\d)"""),
        Regex("""(?i)(?<!\d)(\d{1,2})\s*-?\s*(?:й\s*)?(?:сезон|season)"""),
        Regex("""(?i)(?<![A-Za-z0-9])S(\d{1,2})\s*[ ._-]?\s*E\d{1,3}"""),
        Regex("""(?i)(?<![A-Za-z0-9])S(\d{1,2})(?![A-Za-z0-9])""")
    )
    private val episodeRe = listOf(
        Regex("""(?i)(?:серия|серии|эпизод|episode|ep\.?)\s*[:№#.\-]?\s*(\d{1,4})(?!\d)"""),
        Regex("""(?i)(?<!\d)(\d{1,4})\s*-?\s*(?:я\s*)?(?:серия|episode)"""),
        Regex("""(?i)(?<![A-Za-z0-9])S\d{1,2}\s*[ ._-]?\s*E(\d{1,3})"""),
        Regex("""(?i)(?<![A-Za-z0-9])E(\d{1,3})(?![A-Za-z0-9])""")
    )

    private fun first(rs: List<Regex>, s: String): Int? {
        for (r in rs) r.find(s)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    fun seasonOf(name: String): Int? = first(seasonRe, name)
    fun episodeOf(name: String): Int? = first(episodeRe, name)

    fun label(n: Int) = if (n < 0) "Другое" else "$n сезон"

    /**
     * Сезоны раздела: номер сезона → серии. null, если в названиях нет хотя бы двух разных сезонов
     * (тогда раздел показывается обычным списком). Серии без сезона собираются в «Другое» в конце.
     */
    fun split(list: List<Entry>): Map<Int, List<Entry>>? {
        val by = HashMap<Int, MutableList<Entry>>()
        for (e in list) by.getOrPut(seasonOf(e.name) ?: -1) { ArrayList() }.add(e)
        val real = by.keys.filter { it >= 0 }.sorted()
        if (real.size < 2) return null
        val out = LinkedHashMap<Int, List<Entry>>()
        for (k in real + (if (by.containsKey(-1)) listOf(-1) else emptyList())) {
            val l = by[k]!!
            val eps = l.map { episodeOf(it.name) }
            // если номера серий нашлись у всех и не повторяются — упорядочиваем по ним, иначе оставляем порядок плейлиста
            out[k] = if (eps.all { it != null } && eps.toSet().size == eps.size) l.sortedBy { episodeOf(it.name) } else l
        }
        return out
    }
}
