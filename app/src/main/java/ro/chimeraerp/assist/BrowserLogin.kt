package ro.chimeraerp.assist

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import net.openid.appauth.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PendingLogin(val origin: String, val organization: String, val orgId: String,
                        val request: AuthorizationRequest, val created: Long) {
    fun json(): JSONObject = JSONObject().put("origin", origin).put("organization", organization)
        .put("org_id", orgId).put("request", request.jsonSerialize()).put("created", created)
    companion object {
        fun from(json: JSONObject) = PendingLogin(Endpoint.origin(json.getString("origin")), json.getString("organization"),
            json.getString("org_id"), AuthorizationRequest.jsonDeserialize(json.getJSONObject("request")), json.getLong("created"))
    }
}

/** AppAuth owns browser return, S256, state/nonce and OIDC token response validation. */
class BrowserLogin(context: Context) {
    private val service = AuthorizationService(context, AppAuthConfiguration.Builder().setConnectionBuilder { uri ->
        require(uri.scheme == "https" && uri.encodedAuthority == "auth.chimeraerp.ro")
        (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false; connectTimeout = 10_000; readTimeout = 10_000
        }
    }.build())

    fun prepare(originInput: String): PendingLogin {
        val origin = Endpoint.origin(originInput)
        val config = Api.request(BrowserPolicy.AUTH_ORIGIN, "/auth/browser/native-config?client_origin=" + Uri.encode(origin))
        val org = config.getString("org_id")
        val issuer = BrowserPolicy.issuer(org)
        require(config.getString("login_origin") == BrowserPolicy.AUTH_ORIGIN && config.getString("issuer") == issuer)
        require(config.getString("client_origin") == origin && config.getString("client_id") == "$origin/native/assist")
        require(config.getString("redirect_uri") == BrowserPolicy.REDIRECT)
        val discovery = Api.request(BrowserPolicy.AUTH_ORIGIN, "/auth/oidc/$org/.well-known/openid-configuration")
        require(discovery.getString("issuer") == issuer)
        for (key in listOf("authorization_endpoint", "token_endpoint", "jwks_uri")) BrowserPolicy.endpoint(discovery.getString(key), issuer)
        val configuration = AuthorizationServiceConfiguration(AuthorizationServiceDiscovery(discovery))
        val request = AuthorizationRequest.Builder(configuration, "$origin/native/assist", ResponseTypeValues.CODE, Uri.parse(BrowserPolicy.REDIRECT))
            .setScope("openid profile chimera").build()
        require(request.codeVerifier != null && request.codeVerifierChallengeMethod == "S256" && request.nonce != null)
        return PendingLogin(origin, config.getString("organization"), org, request, System.currentTimeMillis())
    }
    fun intent(pending: PendingLogin): Intent = service.getAuthorizationRequestIntent(pending.request)

    fun finish(data: Intent, pending: PendingLogin, done: (Session?, Exception?) -> Unit) {
        try {
            val response = AuthorizationResponse.fromIntent(data)
                ?: throw AuthorizationException.fromIntent(data) ?: IllegalArgumentException("Authorization cancelled")
            BrowserPolicy.completion(response.state, pending.request.state, pending.created, System.currentTimeMillis())
            require(!response.authorizationCode.isNullOrBlank() && response.accessToken == null)
            // The encrypted initiating request owns all exchange parameters, never intent extras.
            val exchange = AuthorizationResponse.Builder(pending.request)
                .setAuthorizationCode(response.authorizationCode).build().createTokenExchangeRequest()
            service.performTokenRequest(exchange) { token, error ->
                try {
                    if (token == null || error != null) throw error ?: IllegalArgumentException("Missing token")
                    require(!token.accessToken.isNullOrBlank() && token.tokenType.equals("Bearer", true))
                    // AppAuth validates issuer, audience, expiry and nonce over the pinned HTTPS token endpoint.
                    val claims = JSONObject(String(Base64.decode(requireNotNull(token.idToken).split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8))
                    require(claims.getString("org") == pending.orgId)
                    done(Session(pending.origin, token.accessToken!!, pending.organization), null)
                } catch (failure: Exception) { done(null, failure) }
            }
        } catch (error: Exception) { done(null, error) }
    }
    fun dispose() = service.dispose()
}
