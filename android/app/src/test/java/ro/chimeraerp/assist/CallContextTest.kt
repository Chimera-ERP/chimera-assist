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
        now = 30 * 60 * 1000L
        assertNull(calls.result(session))
        assertFalse(calls.complete(event, session, lookup))
    }

    @Test fun repeatedCallerRestoresItsPartnerPageDetailAndScroll() {
        val first=calls.begin("0700000001","RO",session)
        calls.complete(first,session,lookup)
        val id=calls.id(session)!!
        val cursor=CallContext.Cursor("supplier","synthetic","invoices",25,"invoice_1",25,140.0,240.0)
        assertTrue(calls.navigate(session,id,cursor))
        calls.expanded(session,true)
        val second=calls.begin("+447000000001",null,session)
        assertTrue(calls.expanded(session))
        calls.expanded(session,false)
        calls.complete(second,session,Lookup(listOf(Partner("client","other","Other","",listOf("payments"))),false))
        assertEquals(CallContext.Cursor(),calls.cursor(session))
        assertFalse(calls.navigate(session,id,cursor))
        now=29*60*1000L
        calls.begin("+40 (700) 000-001",null,session)
        assertEquals(id,calls.id(session))
        assertTrue(calls.expanded(session))
        assertEquals(cursor,calls.cursor(session))
        now+=29*60*1000L
        assertEquals(cursor,calls.cursor(session))
        now+=60*1000L
        assertNull(calls.current(session))
    }
    @Test fun activeAndHeldCallsCannotExpireAndIdleStartsThirtyMinutes() {
        val event=calls.begin("+40700000001",null,session)
        calls.complete(event,session,lookup)
        calls.callState(true)
        now=60*60*1000L
        calls.callState(true)
        now=2*60*60*1000L
        assertEquals(lookup,calls.result(session))
        calls.callState(false)
        now+=30*60*1000L-1
        assertEquals(lookup,calls.result(session))
        now++
        assertNull(calls.result(session))
    }
    @Test fun refreshedCredentialKeepsWorkspaceButAnotherLoginDoesNot() {
        val event=calls.begin("+40700000001",null,session)
        calls.complete(event,session,lookup)
        assertEquals(lookup,calls.result(session.copy(token="rotated")))
        assertNull(calls.result(session.copy(loginId="different-login")))
    }
    @Test fun navigationDoesNotExtendCallInactivityAndRevocationClearsCursor() {
        val event=calls.begin("+40700000001",null,session)
        calls.complete(event,session,lookup)
        val id=calls.id(session)!!
        now=29*60*1000L
        calls.navigate(session,id,CallContext.Cursor("supplier","synthetic","invoices",25))
        calls.complete(event,session,lookup.copy(partners=lookup.partners.map{it.copy(actions=emptyList())}))
        assertEquals(CallContext.Cursor(),calls.cursor(session))
        now=30*60*1000L
        assertNull(calls.result(session))
    }
}
