package ro.chimeraerp.assist

import java.net.URI

/** Fixed central authority and exact tenant binding; no URL supplied by a callback is used. */
object BrowserPolicy {
    const val AUTH_ORIGIN = "https://auth.chimeraerp.ro"
    const val REDIRECT = "ro.chimeraerp.assist:/oauth2redirect"
    fun issuer(org: String): String {
        require(org.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        return "$AUTH_ORIGIN/auth/oidc/$org"
    }
    fun endpoint(value: String, issuer: String) {
        val uri = URI(value)
        require(issuer.startsWith("$AUTH_ORIGIN/auth/oidc/"))
        require(uri.scheme == "https" && uri.rawAuthority == "auth.chimeraerp.ro")
        require(uri.rawPath.startsWith(URI(issuer).rawPath + "/") && uri.rawPath == uri.normalize().rawPath)
        require(!uri.rawPath.contains('%') && !uri.rawPath.contains('\\'))
        require(uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null)
    }
    fun completion(actualState: String?, expectedState: String?, created: Long, now: Long) {
        require(!expectedState.isNullOrBlank() && actualState == expectedState)
        require(now >= created && now - created <= 600_000)
    }
}
