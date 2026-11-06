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

data class Session(val origin: String, val token: String, val organization: String)

/** Keystore-encrypted session; no password, phone history or business cache. */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("assist-session", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("assist-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun write(name: String, json: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val data = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(name, Base64.encodeToString(data, Base64.NO_WRAP)).commit())
    }
    private fun readJson(name: String): JSONObject? = try {
        prefs.getString(name, null)?.let {
            val data = Base64.decode(it, Base64.NO_WRAP)
            require(data.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12))) }
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        }
    } catch (_: Exception) { check(prefs.edit().remove(name).commit()); null }
    @Synchronized fun save(session: Session) = write("browser-session", JSONObject().put("origin", session.origin)
        .put("token", session.token).put("organization", session.organization))
    @Synchronized fun read(): Session? = readJson("browser-session")?.let {
        Session(Endpoint.origin(it.getString("origin")), it.getString("token"), it.getString("organization"))
    }
    @Synchronized fun begin(pending: PendingLogin) { clear(); write("browser-pending", pending.json()) }
    /** Consume locally before exchange; cancelled/replayed returns cannot revive a session. */
    @Synchronized fun takePending(): PendingLogin? {
        val json = readJson("browser-pending")
        check(prefs.edit().remove("browser-pending").commit())
        return json?.let(PendingLogin::from)
    }
    @Synchronized fun clear() { check(prefs.edit().clear().commit()) }
}
