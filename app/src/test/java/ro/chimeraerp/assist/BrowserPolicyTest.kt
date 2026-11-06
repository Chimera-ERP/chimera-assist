package ro.chimeraerp.assist

import org.junit.Assert.*
import org.junit.Test

class BrowserPolicyTest {
    @Test fun enterpriseAuthorityMustBeExplicitAndRemainPinned() {
        val authority = "https://auth.company.test:8443"
        val issuer = BrowserPolicy.issuer("tenant-a", authority)
        BrowserPolicy.endpoint("$issuer/token", issuer, authority)
        assertThrows(IllegalArgumentException::class.java) { BrowserPolicy.endpoint("$issuer/token", issuer) }
        assertThrows(IllegalArgumentException::class.java) { BrowserPolicy.endpoint("${BrowserPolicy.AUTH_ORIGIN}/auth/oidc/tenant-a/token", issuer, authority) }
        assertThrows(IllegalArgumentException::class.java) { BrowserPolicy.issuer("tenant-a", "http://auth.company.test") }
    }
    @Test fun pinsAuthorityAndTenantEndpoints() {
        val issuer = BrowserPolicy.issuer("tenant-a")
        BrowserPolicy.endpoint("$issuer/token", issuer)
        for (url in listOf("http://auth.chimeraerp.ro/auth/oidc/tenant-a/token", "https://evil.test/auth/oidc/tenant-a/token",
            "https://auth.chimeraerp.ro.evil.test/auth/oidc/tenant-a/token", "https://auth.chimeraerp.ro/auth/oidc/tenant-b/token",
            "$issuer/../tenant-b/token", "$issuer/%2e%2e/tenant-b/token", "$issuer/%2ftoken",
            "$issuer/token?redirect=evil", "$issuer/token#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { BrowserPolicy.endpoint(url, issuer) }
        }
        assertThrows(IllegalArgumentException::class.java) { BrowserPolicy.issuer("../other") }
    }
    @Test fun rejectsMissingWrongExpiredAndFutureState() {
        BrowserPolicy.completion("state-a", "state-a", 1_000, 601_000)
        for ((actual, expected) in listOf(null to "state-a", "state-b" to "state-a", "" to ""))
            assertThrows(IllegalArgumentException::class.java) { BrowserPolicy.completion(actual, expected, 1_000, 2_000) }
        assertThrows(IllegalArgumentException::class.java) { BrowserPolicy.completion("s", "s", 1_000, 601_001) }
        assertThrows(IllegalArgumentException::class.java) { BrowserPolicy.completion("s", "s", 1_000, 999) }
    }
}
