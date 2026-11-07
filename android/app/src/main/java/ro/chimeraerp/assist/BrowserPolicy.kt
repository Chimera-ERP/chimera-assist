package ro.chimeraerp.assist

import java.net.URI

/** Cloud is fixed; an explicitly selected enterprise server may supply its own HTTPS authority. */
object BrowserPolicy {
    const val AUTH_ORIGIN = "https://auth.chimeraerp.ro"
    const val REDIRECT = "ro.chimeraerp.assist:/oauth2redirect"
    fun issuer(org: String, authority: String = AUTH_ORIGIN): String {
        require(org.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        require(Endpoint.origin(authority) == authority)
        return "$authority/auth/oidc/$org"
    }
    fun endpoint(value: String, issuer: String, authority: String = AUTH_ORIGIN) {
        val uri = URI(value)
        require(Endpoint.origin(authority) == authority && issuer.startsWith("$authority/auth/oidc/"))
        require(uri.scheme == "https" && uri.rawAuthority == URI(authority).rawAuthority)
        require(uri.rawPath.startsWith(URI(issuer).rawPath + "/") && uri.rawPath == uri.normalize().rawPath)
        require(!uri.rawPath.contains('%') && !uri.rawPath.contains('\\'))
        require(uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null)
    }
    fun completion(actualState: String?, expectedState: String?, created: Long, now: Long) {
        require(!expectedState.isNullOrBlank() && actualState == expectedState)
        require(now >= created && now - created <= 600_000)
    }
}
