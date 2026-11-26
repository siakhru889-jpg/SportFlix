package com.sportflix.iptv

/** Каталог сериалов Медиатеки: Сериалы / Мультсериалы / Документальные сериалы (общий с Android-версией). */
object SeriesCatalog {
    const val KEY_CAT = "\u0001cat:"
    const val KEY_SER = "\u0001ser:"

    private var catalogFor: Playlist.Parsed? = null
    private var catalogVal: Pair<Map<String, Map<String, List<Entry>>>, Set<String>> = emptyMap<String, Map<String, List<Entry>>>() to emptySet()

    /** Категория по коду: s — Сериалы, ms — Мультсериалы, doc — Документальные сериалы. */
    /** Название для поиска на TMDB: без эмодзи, года, возраста и скобок («🎬 Гангстерленд (2025)(18+)» → «Гангстерленд»). */
    fun searchTitle(raw: String): String =
        seriesName(raw).replace(Regex("[^\\p{L}\\p{N}\\s:'!?.,&-]"), " ").replace(Regex("\\s+"), " ").trim().ifBlank { raw }

    /** Запись папки-сериала для афиши: лучше та, у которой в плейлисте есть рейтинг или страна. */
    fun coverOf(l: List<Entry>): Entry? {
        // у сериала постер берём с последнего сезона (у сезонов он часто разный), а не с первого
        val lastSeason = Seasons.split(l)?.entries?.filter { it.key >= 0 }?.maxByOrNull { it.key }?.value
        val pool = lastSeason ?: l
        return pool.firstOrNull { it.rating.isNotBlank() || it.country.isNotBlank() } ?: pool.firstOrNull()
    }

    /** Названия папок сериалов с эмодзи. */
    fun catTitle(cn: String): String = when (cn) {
        "Сериалы" -> "📺 Сериалы"
        "Мультсериалы" -> "🧸 Мультсериалы"
        "Документальные сериалы" -> "🌍 Документальные сериалы"
        else -> cn
    }

    fun catOfCode(code: String): String? = when (code.lowercase()) {
        "s" -> "Сериалы"; "ms" -> "Мультсериалы"; "doc" -> "Документальные сериалы"; else -> null
    }

    /** Категория по названию: «мульт…» → Мультсериалы, «документ…» → Документальные сериалы, иначе Сериалы. */
    fun catOf(n: String): String {
        val s = n.lowercase()
        return when {
            s.contains("мульт") || s.contains("анимац") -> "Мультсериалы"
            s.contains("документ") || s.contains("докум") -> "Документальные сериалы"
            else -> "Сериалы"
        }
    }

    /** Название сериала из названия серии: всё до «Сезон 1», «S01E02», «Серия 3»; год и скобки отбрасываются. */
    fun seriesName(entry: String): String {
        var n = entry
        val cut = Regex("(?i)[\\s,.:;\\-–—|/]*(?:\\(?\\d+\\s*сезон|сезон\\s*\\d|season\\s*\\d|\\bS\\d{1,2}\\s*[ ._-]?E?\\d|\\b\\d{1,2}\\s*[xх]\\s*\\d{1,3}\\b|серия\\s*\\d|эпизод\\s*\\d|episode\\s*\\d)").find(n)
        if (cut != null && cut.range.first > 0) n = n.substring(0, cut.range.first)
        n = n.replace(Regex("\\([^)]*\\)"), " ").replace(Regex("\\[[^\\]]*\\]"), " ")
        n = n.replace(Regex("\\s+"), " ").trim(' ', '-', '–', ',', '.', ':', '|', '/')
        return n.ifBlank { entry }
    }

    val codePrefix = Regex("^(?i)(doc|ms|s)\\s*[-:|_.]\\s*(.+)$")

    /**
     * Каталог сериалов Медиатеки: категория (Сериалы / Мультсериалы / Документальные сериалы) → сериал → серии,
     * и разделы, которые в него вошли (на главной они не показываются). Вид определяется по кодам, потом по названию:
     *  • атрибут stype="s|ms|doc" у записи;
     *  • код в начале названия раздела: «ms-Симпсоны», «doc: Планета Земля», «s-Гангстерленд»;
     *  • слова «мульт…», «документ…» в названии раздела.
     * Раздел-контейнер («Мультсериалы» со многими сериалами подряд) раскладывается по названиям сериалов.
     */
    fun catalog(p: Playlist.Parsed): Pair<Map<String, Map<String, List<Entry>>>, Set<String>> {
        if (catalogFor === p) return catalogVal
        val cats = linkedMapOf<String, LinkedHashMap<String, MutableList<Entry>>>(
            "Сериалы" to LinkedHashMap(), "Мультсериалы" to LinkedHashMap(), "Документальные сериалы" to LinkedHashMap())
        val used = HashSet<String>()
        for ((g, l) in p.groups) {
            if (g == Playlist.NO_GROUP || l.isEmpty()) continue
            val gl = g.lowercase()
            val m = codePrefix.find(g)
            val codeCat = m?.let { catOfCode(it.groupValues[1]) }
            val explicit = codeCat != null || l.any { it.kind.isNotEmpty() }
            if (!(explicit || isSeriesGroup(l) || gl.contains("сериал"))) continue
            used.add(g)
            val base = if (m != null) m.groupValues[2].trim() else g
            val parts = base.split('|', '/', '›', '>').map { it.trim() }.filter { it.isNotEmpty() }
            val groupCat = codeCat ?: catOf(if (parts.size > 1) parts.first() else base)
            val bl = base.lowercase()
            val container = parts.size <= 1 && (bl.contains("сериал") || bl.contains("мульт") || bl.contains("документ")) &&
                l.map { seriesName(it.name) }.toSet().size >= 3
            for (e in l) {
                val cat = catOfCode(e.kind) ?: groupCat
                val key = if (container) seriesName(e.name) else if (parts.size > 1) parts.last() else base
                cats.getValue(cat).getOrPut(key) { ArrayList() }.add(e)
            }
        }
        catalogFor = p
        catalogVal = cats.filterValues { it.isNotEmpty() } to used
        return catalogVal
    }


    /** Раздел похож на сериал: у большинства записей в названии есть сезон или серия. */
    fun isSeriesGroup(l: List<Entry>): Boolean {
        if (l.size < 2) return false
        val hit = l.count { Seasons.seasonOf(it.name) != null || Seasons.episodeOf(it.name) != null }
        return hit * 10 >= l.size * 6
    }

}
