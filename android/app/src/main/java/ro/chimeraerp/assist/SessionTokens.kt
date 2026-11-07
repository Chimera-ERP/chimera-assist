package ro.chimeraerp.assist

import android.content.Context
import android.net.Uri
import android.util.Base64
import net.openid.appauth.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Refresh is serialized across the activity and floating surface; proofs never cross into Flutter. */
class SessionTokens(private val context: Context) {
    internal var read: () -> Session? = { SessionStore(context).read() }
    internal var replace: (Session,Session) -> Boolean = { old,next -> SessionStore(context).replace(old,next) }
    internal var renew: (Session) -> Session = ::refresh
    fun valid(expected: Session): Session = synchronized(lock) {
        val current = read()?.takeIf { expected.sameLogin(it) } ?: throw ApiFailure(401,"session_expired")
        HelperState(context).seedLogin(current)
        // 0.2.x stored no expiry/refresh metadata. Do not treat that old grant as infinite.
        if (current.expiresAt == Long.MAX_VALUE && current.refreshToken == null) throw ApiFailure(401,"session_upgrade_required")
        if (current.expiresAt > System.currentTimeMillis() + 60_000) return@synchronized current
        val next = renew(current)
        require(current.sameLogin(next))
        if (!replace(current,next)) throw ApiFailure(401,"session_expired")
        next
    }
    private fun refresh(current: Session): Session {
        val refresh = current.refreshToken ?: throw ApiFailure(401,"session_expired")
        val authority = Endpoint.origin(requireNotNull(current.authority))
        val issuer = BrowserPolicy.issuer(requireNotNull(current.orgId),authority)
        val config = AuthorizationServiceConfiguration.fromJson(requireNotNull(current.configuration))
        BrowserPolicy.endpoint(config.tokenEndpoint.toString(),issuer,authority)
        require(config.discoveryDoc?.issuer == issuer)
        val service = AuthorizationService(context, AppAuthConfiguration.Builder().setConnectionBuilder { uri ->
            require(uri.scheme == "https" && uri.encodedAuthority == Uri.parse(authority).encodedAuthority)
            (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 10_000; readTimeout = 10_000
            }
        }.build())
        val latch = CountDownLatch(1)
        var response: TokenResponse? = null
        var failure: AuthorizationException? = null
        try {
            val request = TokenRequest.Builder(config, "${current.origin}/native/assist")
                .setGrantType(GrantTypeValues.REFRESH_TOKEN).setRefreshToken(refresh).build()
            service.performTokenRequest(request) { token,error -> response=token;failure=error;latch.countDown() }
            if (!latch.await(25,TimeUnit.SECONDS)) throw java.io.IOException("Refresh unavailable")
            failure?.let {
                if (it.error == "invalid_grant" || it.error == "invalid_client" || it.error == "unauthorized_client") throw ApiFailure(401,"session_expired")
                throw java.io.IOException("Refresh unavailable")
            }
            val token = requireNotNull(response)
            require(!token.accessToken.isNullOrBlank() && token.tokenType.equals("Bearer",true))
            token.idToken?.let {
                val claims = JSONObject(String(Base64.decode(it.split('.')[1],Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),Charsets.UTF_8))
                require(claims.getString("org") == current.orgId && claims.getString("sub") == current.subject)
            }
            return current.copy(token=token.accessToken!!,refreshToken=token.refreshToken ?: refresh,
                expiresAt=token.accessTokenExpirationTime ?: System.currentTimeMillis())
        } finally { service.dispose() }
    }
    companion object {
        private val lock = Any()
        fun expiryCode(error: ApiFailure) = if (error.code == "session_upgrade_required") error.code else "session_expired"
    }
}
