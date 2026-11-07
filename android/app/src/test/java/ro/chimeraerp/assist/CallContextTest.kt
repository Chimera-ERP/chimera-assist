package ro.chimeraerp.assist

import org.junit.Assert.*
import org.junit.Test

class CallContextTest {
    private var now = 0L
    private val calls = CallContext({ now })
    private val session = Session("https://erp.example.test", "synthetic-token", "Synthetic")
    private val lookup = Lookup(listOf(Partner("supplier", "synthetic", "Synthetic", "", listOf("invoices"))), false)
    @Test fun coldServiceCanRecoverPendingCallAndKeepDetailsAvailableInTheApp() {
        val event = calls.begin("0700000001", "RO", session)
        now += 5000
        assertEquals(event, calls.current(session))
        assertTrue(calls.complete(event, session, lookup))
        // The same context serves an unlocked overlay and the foreground Flutter screen.
        assertEquals(lookup, calls.result(session))
        now += 5000
        assertEquals(lookup, calls.result(session))
    }
    @Test fun newCallCannotBeReplacedByALateReply() {
        val first = calls.begin("0700000001", "RO", session)
        val next = calls.begin("0700000002", "RO", session)
        assertFalse(calls.complete(first, session, lookup))
        assertNull(calls.result(session))
        assertTrue(calls.complete(next, session, Lookup(emptyList(), false)))
        assertTrue(calls.result(session)!!.partners.isEmpty())
    }
    @Test fun logoutAndChangedTenantInvalidateOutstandingWork() {
        val event = calls.begin("0700000001", "RO", session)
        assertFalse(calls.complete(event, session.copy(origin = "https://other.example.test"), lookup))
        assertNull(calls.current(session))
        val another = calls.begin("0700000001", "RO", session)
        calls.clear()
        assertFalse(calls.complete(another, session, lookup))
        assertNull(calls.result(null))
    }
    @Test fun contextExpiresAndIsNeverACallHistory() {
        val event = calls.begin("0700000001", "RO", session)
        calls.complete(event, session, lookup)
        now = 300000
        assertNull(calls.result(session))
        assertFalse(calls.complete(event, session, lookup))
    }
}
