package ro.chimeraerp.assist

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SessionTokensTest {
    private val initial=Session("https://erp.example.test","expired","Synthetic","login-one",refreshToken="synthetic-proof",expiresAt=0)
    private fun tokens(current:AtomicReference<Session?>)=SessionTokens(RuntimeEnvironment.getApplication()).apply {
        read={current.get()}
        replace={old,next -> current.get()?.takeIf {old.sameLogin(it)}?.let {current.compareAndSet(it,next)} ?: false}
    }
    @Test fun simultaneousActivityAndOverlayRequestsUseOneRotatedCredential() {
        val state=AtomicReference<Session?>(initial)
        val count=AtomicInteger()
        val tokens=tokens(state).apply { renew={count.incrementAndGet();it.copy(token="rotated",expiresAt=Long.MAX_VALUE)} }
        val executor=Executors.newFixedThreadPool(2)
        try {
            val first=executor.submit<Session>{tokens.valid(initial)}
            val second=executor.submit<Session>{tokens.valid(initial)}
            assertEquals("rotated",first.get(5,TimeUnit.SECONDS).token)
            assertEquals(first.get(),second.get())
            assertEquals(1,count.get())
            assertTrue(initial.sameLogin(state.get()))
        } finally {executor.shutdownNow()}
    }
    @Test fun logoutDuringRefreshCannotReviveSession() {
        val state=AtomicReference<Session?>(initial)
        val started=CountDownLatch(1);val finish=CountDownLatch(1)
        val tokens=tokens(state).apply {renew={started.countDown();check(finish.await(5,TimeUnit.SECONDS));it.copy(token="late",expiresAt=Long.MAX_VALUE)}}
        val executor=Executors.newSingleThreadExecutor()
        try {
            val request=executor.submit<Boolean>{try{tokens.valid(initial);false}catch(e:ApiFailure){e.status==401}}
            assertTrue(started.await(5,TimeUnit.SECONDS));state.set(null);finish.countDown()
            assertTrue(request.get(5,TimeUnit.SECONDS));assertNull(state.get())
        } finally {finish.countDown();executor.shutdownNow()}
    }
    @Test fun anotherLoginCannotBorrowCurrentCredentials() {
        val state=AtomicReference<Session?>(initial.copy(loginId="another-login",expiresAt=Long.MAX_VALUE))
        val tokens=tokens(state).apply {renew={throw AssertionError("must not refresh")}}
        try{tokens.valid(initial);fail("Must reject old login")}catch(e:ApiFailure){assertEquals(401,e.status)}
    }
    @Test fun legacyGrantRequiresOneLoginAndPreservesItsSelfHostedAddress() {
        val old=Session("https://legacy.example.test","synthetic","Synthetic")
        val state=AtomicReference<Session?>(old)
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("login-preferences",0).edit().clear().commit()
        val tokens=tokens(state).apply {renew={throw AssertionError("Legacy grant has no refresh proof")}}
        try{tokens.valid(old);fail("Must reject an unknown expiry")}catch(e:ApiFailure){
            assertEquals(401,e.status);assertEquals("session_upgrade_required",SessionTokens.expiryCode(e))
        }
        assertEquals(old.origin,HelperState(context).enterpriseOrigin)
    }
    @Test fun temporaryNetworkFailurePreservesSessionAndCanRetryRenewal() {
        val state=AtomicReference<Session?>(initial)
        val tokens=tokens(state).apply {renew={throw java.io.IOException("Synthetic offline")}}
        try{tokens.valid(initial);fail("Must report connection failure")}catch(_:java.io.IOException){}
        assertEquals(initial,state.get())
        tokens.renew={it.copy(token="retried",refreshToken="rotated-proof",expiresAt=System.currentTimeMillis()+900_000)}
        assertEquals("retried",tokens.valid(initial).token)
        assertEquals("rotated-proof",state.get()!!.refreshToken)
    }
}
