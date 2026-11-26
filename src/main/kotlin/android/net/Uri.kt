package android.net

/** Минимальная замена Android Uri (нужен только хост адреса). */
class Uri private constructor(private val s: String) {
    val host: String? get() = try { java.net.URI(s.replace(" ", "%20").replace("|", "%7C")).host } catch (e: Exception) { Regex("^[a-zA-Z]+://([^/:?#]+)").find(s)?.groupValues?.get(1) }
    override fun toString() = s
    companion object { fun parse(s: String) = Uri(s) }
}
