package ro.chimeraerp.assist

import android.content.Context
import net.openid.appauth.AuthorizationServiceConfiguration
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** RFC7009 revocation. Pending proofs are encrypted, never sent through Flutter. */
class SessionLogout(context: Context) {
    private val store=SessionStore(context)
    internal var pending: () -> List<Session> = store::revocations
    internal var acknowledge: (String) -> Unit = store::revoked
    internal var send: (Session) -> Unit = ::revoke
    fun drain(): Boolean = synchronized(lock) {
        var complete=true
        for(session in pending()) {
            if(Thread.currentThread().isInterrupted)return@synchronized false
            try {send(session);acknowledge(session.loginId)} catch(_:Exception) {complete=false}
        }
        complete
    }
    private fun revoke(session: Session) {
        val authority=Endpoint.origin(requireNotNull(session.authority))
        val issuer=BrowserPolicy.issuer(requireNotNull(session.orgId),authority)
        val config=AuthorizationServiceConfiguration.fromJson(requireNotNull(session.configuration))
        require(config.discoveryDoc?.issuer==issuer)
        var metadata=config.discoveryDoc!!.docJson
        if(!metadata.has("revocation_endpoint")) {
            // Old installations may have cached discovery before revocation was enabled.
            val connection=open("$issuer/.well-known/openid-configuration",issuer,authority)
            try {
                if(connection.responseCode!=200)throw IOException("Discovery unavailable")
                val data=connection.inputStream.use {input ->
                    val bytes=java.io.ByteArrayOutputStream()
                    val buffer=ByteArray(8192)
                    while(true) {
                        val count=input.read(buffer)
                        if(count<0)break
                        require(bytes.size()+count<=262144)
                        bytes.write(buffer,0,count)
                    }
                    bytes.toByteArray()
                }
                metadata=JSONObject(String(data,Charsets.UTF_8))
                require(metadata.getString("issuer")==issuer)
            } finally {connection.disconnect()}
        }
        val target=metadata.getString("revocation_endpoint")
        val connection=open(target,issuer,authority)
        try {
            connection.requestMethod="POST";connection.doOutput=true
            connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded")
            fun encode(value:String)=URLEncoder.encode(value,"UTF-8")
            val body="client_id=${encode(session.origin+"/native/assist")}&token_type_hint=refresh_token&token=${encode(requireNotNull(session.refreshToken))}"
            connection.outputStream.use {it.write(body.toByteArray(Charsets.UTF_8))}
            if(connection.responseCode!=200)throw IOException("Revocation unavailable")
        } finally {connection.disconnect()}
    }
    private fun open(target:String,issuer:String,authority:String): HttpURLConnection {
        BrowserPolicy.endpoint(target,issuer,authority)
        return (URL(target).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects=false;connectTimeout=10_000;readTimeout=10_000
        }
    }
    companion object {private val lock=Any()}
}
