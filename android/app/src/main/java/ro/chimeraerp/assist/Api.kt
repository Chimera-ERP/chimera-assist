package ro.chimeraerp.assist

import org.json.JSONObject
import java.net.URL
import java.io.ByteArrayOutputStream
import javax.net.ssl.HttpsURLConnection

class ApiFailure(val status: Int, val code: String) : Exception("API request failed")
data class Partner(val kind: String, val id: String, val name: String, val taxId: String, val actions: List<String>)
data class Lookup(val partners: List<Partner>, val hasMore: Boolean)

object Api {
    fun request(origin: String, path: String, token: String? = null, body: JSONObject? = null): JSONObject {
        require(path.startsWith("/api/") || (token == null &&
            (path.startsWith("/auth/browser/native-config?") || path.startsWith("/auth/oidc/") ||
             path in listOf("/auth/browser/assist/start", "/auth/browser/assist/resolve"))))
        val connection = URL(Endpoint.origin(origin) + path).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 512 * 1024)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            val json = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) { JSONObject() }
            if (status !in 200..299) throw ApiFailure(status, json.optString("code", json.optString("error")))
            return json
        } finally { connection.disconnect() }
    }
    fun lookup(session: Session, phone: String): Lookup {
        val result = request(session.origin, "/api/assist/lookup", session.token, JSONObject().put("phone", phone))
        val rows = result.getJSONArray("partners")
        require(rows.length() <= 20)
        return Lookup((0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val actions = row.getJSONArray("actions")
            Partner(row.getString("kind"), row.getString("id"), row.getString("name"), row.optString("tax_id", ""),
                (0 until actions.length()).map { actions.getString(it) }.filter { it in listOf("invoices", "payments", "products") })
        }, result.getBoolean("has_more"))
    }
}
