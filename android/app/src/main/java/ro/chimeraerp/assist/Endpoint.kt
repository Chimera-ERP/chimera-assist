package ro.chimeraerp.assist

import java.net.URI

/** User-selected HTTPS origin only; credentials never follow redirects. */
object Endpoint {
    fun origin(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in listOf("", "/") &&
            (uri.port == -1 || uri.port in 1..65535))
        return uri.toASCIIString().trimEnd('/')
    }
    fun path(kind: String, id: String, action: String, offset: Int, item: String? = null): String {
        require(kind in listOf("supplier", "client") && action in listOf("invoices", "payments", "products"))
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && offset in 0..10000)
        require(item == null || item.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        return "/api/assist/partners/$kind/$id/$action" + (item?.let { "/$it" } ?: "") + "?offset=$offset"
    }
}
