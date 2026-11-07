package ro.chimeraerp.assist

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import net.openid.appauth.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

data class PendingLogin(val origin: String, val organization: String, val orgId: String,
                        val request: AuthorizationRequest, val created: Long,
                        val authority: String = BrowserPolicy.AUTH_ORIGIN, val cloudRequest: String? = null) {
    fun json(): JSONObject = JSONObject().put("origin", origin).put("organization", organization)
        .put("org_id", orgId).put("request", request.jsonSerialize()).put("created", created)
        .put("authority", authority).put("cloud_request", cloudRequest)
    companion object {
        fun from(json: JSONObject) = PendingLogin(json.getString("origin"), json.getString("organization"),
            json.getString("org_id"), AuthorizationRequest.jsonDeserialize(json.getJSONObject("request")), json.getLong("created"),
            Endpoint.origin(json.optString("authority", BrowserPolicy.AUTH_ORIGIN)),
            if (json.isNull("cloud_request")) null else json.getString("cloud_request"))
    }
}

/** AppAuth owns browser return, S256, state/nonce and OIDC token response validation. */
class BrowserLogin(private val context: Context) {
    private var service: AuthorizationService? = null
    private var serviceAuthority: String? = null
    private fun service(authority: String): AuthorizationService {
        require(Endpoint.origin(authority) == authority)
        if (serviceAuthority == authority) return requireNotNull(service)
        service?.dispose()
        serviceAuthority = authority
        return AuthorizationService(context, AppAuthConfiguration.Builder().setConnectionBuilder { uri ->
        require(uri.scheme == "https" && uri.encodedAuthority == Uri.parse(authority).encodedAuthority)
        (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false; connectTimeout = 10_000; readTimeout = 10_000
        }
        }.build()).also { service = it }
    }

    fun prepareCloud(): PendingLogin {
        val authority = BrowserPolicy.AUTH_ORIGIN
        val configuration = AuthorizationServiceConfiguration(Uri.parse("$authority/auth/browser/assist/authorize"),
            Uri.parse("$authority/auth/browser/assist/resolve"))
        fun proof(): String = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val builder = AuthorizationRequest.Builder(configuration, "ro.chimeraerp.assist", ResponseTypeValues.CODE, Uri.parse(BrowserPolicy.REDIRECT))
            .setScope("openid profile chimera").setState(proof()).setNonce(proof())
        val original = builder.build()
        val response = Api.request(authority, "/auth/browser/assist/start", body = JSONObject()
            .put("state", original.state).put("nonce", original.nonce).put("code_challenge", original.codeVerifierChallenge)
            .put("code_challenge_method", "S256"))
        val requestId = response.getString("request")
        require(requestId.matches(Regex("[A-Za-z0-9_-]{43}")))
        return PendingLogin("", "", "", builder.setAdditionalParameters(mapOf("request" to requestId)).build(),
            System.currentTimeMillis(), authority, requestId)
    }

    fun prepare(originInput: String): PendingLogin {
        val origin = Endpoint.origin(if (originInput.contains("://")) originInput else "https://$originInput")
        val browser = Api.request(origin, "/api/auth/browser/config")
        require(browser.getBoolean("enabled"))
        val authority = Endpoint.origin(browser.getString("login_origin"))
        val config = Api.request(authority, "/auth/browser/native-config?client_origin=" + Uri.encode(origin))
        require(config.getString("client_origin") == origin)
        return configured(config, authority)
    }

    private fun configured(config: JSONObject, authority: String, original: PendingLogin? = null): PendingLogin {
        val origin = Endpoint.origin(config.getString("client_origin"))
        val org = config.getString("org_id")
        val issuer = BrowserPolicy.issuer(org, authority)
        require(config.getString("login_origin") == authority && config.getString("issuer") == issuer)
        require(config.getString("client_origin") == origin && config.getString("client_id") == "$origin/native/assist")
        require(config.getString("redirect_uri") == BrowserPolicy.REDIRECT)
        val discovery = Api.request(authority, "/auth/oidc/$org/.well-known/openid-configuration")
        require(discovery.getString("issuer") == issuer)
        for (key in listOf("authorization_endpoint", "token_endpoint", "jwks_uri")) BrowserPolicy.endpoint(discovery.getString(key), issuer, authority)
        val configuration = AuthorizationServiceConfiguration(AuthorizationServiceDiscovery(discovery))
        val builder = AuthorizationRequest.Builder(configuration, "$origin/native/assist", ResponseTypeValues.CODE, Uri.parse(BrowserPolicy.REDIRECT))
            .setScope("openid profile chimera")
        if (original != null) builder.setState(original.request.state).setNonce(original.request.nonce).setCodeVerifier(original.request.codeVerifier)
        val request = builder.build()
        require(request.codeVerifier != null && request.codeVerifierChallengeMethod == "S256" && request.nonce != null)
        return PendingLogin(origin, config.getString("organization"), org, request, original?.created ?: System.currentTimeMillis(), authority)
    }
    fun intent(pending: PendingLogin): Intent = service(pending.authority).getAuthorizationRequestIntent(pending.request)

    fun finish(data: Intent, pending: PendingLogin, done: (Session?, Exception?) -> Unit) {
        try {
            val response = AuthorizationResponse.fromIntent(data)
                ?: throw AuthorizationException.fromIntent(data) ?: IllegalArgumentException("Authorization cancelled")
            BrowserPolicy.completion(response.state, pending.request.state, pending.created, System.currentTimeMillis())
            require(!response.authorizationCode.isNullOrBlank() && response.accessToken == null)
            // The tenant comes from a one-use server choice bound to the original
            // verifier, never from a callback URL or untrusted Intent extras.
            val resolved = if (pending.cloudRequest == null) pending else {
                require(pending.authority == BrowserPolicy.AUTH_ORIGIN)
                configured(Api.request(pending.authority, "/auth/browser/assist/resolve", body = JSONObject()
                    .put("request", pending.cloudRequest).put("code_verifier", pending.request.codeVerifier)), pending.authority, pending)
            }
            response.additionalParameters["iss"]?.let { require(it == BrowserPolicy.issuer(resolved.orgId, resolved.authority)) }
            val exchange = AuthorizationResponse.Builder(resolved.request)
                .setAuthorizationCode(response.authorizationCode).build().createTokenExchangeRequest()
            service(resolved.authority).performTokenRequest(exchange) { token, error ->
                try {
                    if (token == null || error != null) throw error ?: IllegalArgumentException("Missing token")
                    require(!token.accessToken.isNullOrBlank() && token.tokenType.equals("Bearer", true))
                    // AppAuth validates issuer, audience, expiry and nonce over the pinned HTTPS token endpoint.
                    val claims = JSONObject(String(Base64.decode(requireNotNull(token.idToken).split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8))
                    require(claims.getString("org") == resolved.orgId)
                    done(Session(resolved.origin, token.accessToken!!, resolved.organization), null)
                } catch (failure: Exception) { done(null, failure) }
            }
        } catch (error: Exception) { done(null, error) }
    }
    fun dispose() { service?.dispose(); service = null; serviceAuthority = null }
}
