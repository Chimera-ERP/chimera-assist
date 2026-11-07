package ro.chimeraerp.assist

import org.junit.Assert.*
import org.junit.Test

class EndpointTest {
    @Test fun requiresAnExplicitHttpsOrigin() {
        assertEquals("https://erp.example.test", Endpoint.origin(" https://erp.example.test/ "))
        assertEquals("https://erp.example.test:8443", Endpoint.origin("https://erp.example.test:8443"))
        listOf("http://erp.example.test", "https://user:pass@erp.example.test", "https://erp.example.test/path", "https://erp.example.test?redirect=x", "https://erp.example.test#secret", "file:///secret", "https:///path").forEach {
            assertThrows(Exception::class.java) { Endpoint.origin(it) }
        }
    }
    @Test fun cannotEscapePartnerReadEndpoints() {
        assertEquals("/api/assist/partners/client/client_abc/payments?offset=25", Endpoint.path("client", "client_abc", "payments", 25))
        listOf("../other", "https://other", "x?org_id=other", "x/../y", "").forEach {
            assertThrows(Exception::class.java) { Endpoint.path("client", it, "payments", 0) }
        }
        assertThrows(Exception::class.java) { Endpoint.path("client", "id", "delete", 0) }
        assertThrows(Exception::class.java) { Endpoint.path("other", "id", "payments", 0) }
        assertThrows(Exception::class.java) { Endpoint.path("client", "id", "payments", -1) }
    }
}
