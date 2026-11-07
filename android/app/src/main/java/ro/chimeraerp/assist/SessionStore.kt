package ro.chimeraerp.assist

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Session(val origin: String, val token: String, val organization: String, val loginId: String = token,
    val refreshToken: String? = null, val expiresAt: Long = Long.MAX_VALUE,
    val authority: String? = null, val orgId: String? = null, val subject: String? = null,
    val configuration: String? = null) {
    fun sameLogin(other: Session?) = other != null && origin == other.origin && loginId == other.loginId
}

/** Keystore-encrypted session; no password, phone history or business cache. */
class SessionStore internal constructor(context: Context, private val suppliedKey: SecretKey? = null) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        suppliedKey?.let {return it}
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("assist-session", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("assist-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun seal(name: String, json: JSONObject): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val data = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }
    private fun write(name: String, json: JSONObject) { check(prefs.edit().putString(name,seal(name,json)).commit()) }
    private fun readJson(name: String): JSONObject? = try {
        prefs.getString(name, null)?.let {
            val data = Base64.decode(it, Base64.NO_WRAP)
            require(data.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12))) }
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        }
    } catch (_: Exception) { check(prefs.edit().remove(name).commit()); null }
    private fun json(session: Session) = JSONObject().put("origin", session.origin)
        .put("token", session.token).put("organization", session.organization).put("loginId",session.loginId)
        .put("refreshToken",session.refreshToken).put("expiresAt",session.expiresAt).put("authority",session.authority)
        .put("orgId",session.orgId).put("subject",session.subject).put("configuration",session.configuration)
    fun save(session: Session) = synchronized(lock) { write("browser-session",json(session)) }
    private fun session(it: JSONObject): Session =
        Session(Endpoint.origin(it.getString("origin")), it.getString("token"), it.getString("organization"),
            it.optString("loginId",it.getString("token")), it.nullable("refreshToken"),it.optLong("expiresAt",Long.MAX_VALUE),
            it.nullable("authority"),it.nullable("orgId"),it.nullable("subject"),it.nullable("configuration"))

    fun read(): Session? = synchronized(lock) { readJson("browser-session")?.let(::session) }
    fun replace(expected: Session, next: Session): Boolean = synchronized(lock) {
        if (!expected.sameLogin(read())) false else { save(next); true }
    }
    private fun JSONObject.nullable(key: String): String? = if (isNull(key)) null else getString(key)
    fun begin(pending: PendingLogin) = synchronized(lock) { logout(); write("browser-pending", pending.json()) }
    /** Consume locally before exchange; cancelled/replayed returns cannot revive a session. */
    fun takePending(): PendingLogin? = synchronized(lock) {
        val json = readJson("browser-pending")
        check(prefs.edit().remove("browser-pending").commit())
        json?.let(PendingLogin::from)
    }
    fun clear() = synchronized(lock) { check(prefs.edit().remove("browser-session").remove("browser-pending").commit()) }
    /** Atomically stop local sign-in and retain only an encrypted server-revocation proof. */
    fun logout() = synchronized(lock) {
        val queue=readJson("logout-queue") ?: JSONObject()
        read()?.takeIf {it.refreshToken != null}?.let {queue.put(it.loginId,json(it.copy(token="",organization="",expiresAt=0,subject=null)))}
        val edit=prefs.edit().remove("browser-session").remove("browser-pending")
        if(queue.length()>0)edit.putString("logout-queue",seal("logout-queue",queue))
        check(edit.commit())
    }
    fun revocations(): List<Session> = synchronized(lock) {
        val queue=readJson("logout-queue") ?: return@synchronized emptyList()
        queue.keys().asSequence().map {session(queue.getJSONObject(it))}.toList()
    }
    fun revoked(loginId: String) = synchronized(lock) {
        val queue=readJson("logout-queue") ?: return@synchronized
        queue.remove(loginId)
        if(queue.length()==0)check(prefs.edit().remove("logout-queue").commit()) else write("logout-queue",queue)
    }
    companion object { private val lock = Any() }
}
