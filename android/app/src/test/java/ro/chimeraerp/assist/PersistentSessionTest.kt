package ro.chimeraerp.assist

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Intent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PersistentSessionTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val key=SecretKeySpec(ByteArray(32){(it+1).toByte()},"AES")
    private fun store()=SessionStore(context,key)
    private val session=Session("https://erp.example.test","access-synthetic","Synthetic","login-one",refreshToken="refresh-synthetic",expiresAt=0)
    @Before fun reset() {context.getSharedPreferences("session",0).edit().clear().commit();HelperState(context).clear()}
    @Test fun logoutAtomicallyRemovesActiveSessionButPersistsOnlyEncryptedRevocationProof() {
        store().save(session);store().logout()
        val restored=store()
        assertNull(restored.read())
        val proof=restored.revocations().single()
        assertEquals(session.refreshToken,proof.refreshToken)
        assertEquals("",proof.token);assertEquals("",proof.organization)
        val raw=context.getSharedPreferences("session",0).all.toString()
        assertFalse(raw.contains("refresh-synthetic"));assertFalse(raw.contains("erp.example.test"))
        assertFalse(restored.replace(session,session.copy(token="late")))
        assertNull(restored.read())
    }
    @Test fun offlineLogoutRetriesWithoutClearingAnotherLoginOrStartingHelper() {
        store().save(session);store().logout()
        val newer=session.copy(loginId="login-two",token="new-access",refreshToken="new-refresh")
        store().save(newer)
        val logout=SessionLogout(context).apply {
            pending=store()::revocations;acknowledge=store()::revoked
            send={throw java.io.IOException("Synthetic offline")}
        }
        assertFalse(logout.drain());assertEquals(1,store().revocations().size)
        assertEquals(newer,store().read())
        logout.send={assertEquals(session.loginId,it.loginId)}
        assertTrue(logout.drain());assertTrue(store().revocations().isEmpty())
        assertEquals(newer,store().read());assertFalse(HelperState(context).enabled)
    }
    @Test fun twoPendingLogoutsRemainIsolatedAndCiphertextsCannotChangePurpose() {
        store().save(session);store().logout()
        store().save(session.copy(loginId="login-two",refreshToken="second"));store().logout()
        store().revoked(session.loginId)
        assertEquals("login-two",store().revocations().single().loginId)
        val prefs=context.getSharedPreferences("session",0)
        prefs.edit().putString("browser-session",prefs.getString("logout-queue",null)).commit()
        assertNull(store().read());assertEquals(1,store().revocations().size)
    }
    @Test fun logoutRetryRequiresNetworkAndPersistsAcrossRebootWithoutForegroundService() {
        LogoutJob.request(context)
        val job=context.getSystemService(JobScheduler::class.java).getPendingJob(LogoutJob.ID)!!
        assertTrue(job.isPersisted);assertEquals(JobInfo.NETWORK_TYPE_ANY,job.networkType)
        assertEquals(JobInfo.BACKOFF_POLICY_EXPONENTIAL,job.backoffPolicy)
        assertNull(shadowOf(context).nextStartedService)
    }
    @Test fun bootAndUpdateRestoreOnlyAnEnabledSignedInPermittedHelper() {
        var starts=0
        val receiver=HelperRestartReceiver().apply {signedIn={true};permitted={true};start={starts++}}
        receiver.onReceive(context,Intent(Intent.ACTION_BOOT_COMPLETED));assertEquals(0,starts)
        HelperState(context).enabled=true
        for(action in listOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED))receiver.onReceive(context,Intent(action))
        assertEquals(2,starts)
        receiver.signedIn={false};receiver.onReceive(context,Intent(Intent.ACTION_BOOT_COMPLETED));assertEquals(2,starts)
        receiver.signedIn={true};receiver.permitted={false};receiver.onReceive(context,Intent(Intent.ACTION_BOOT_COMPLETED));assertEquals(2,starts)
        assertEquals("start_blocked",HelperState(context).status());assertTrue(HelperState(context).enabled)
        receiver.permitted={true};receiver.start={throw IllegalStateException("Synthetic Android refusal")}
        receiver.onReceive(context,Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals("start_blocked",HelperState(context).status());assertTrue(HelperState(context).enabled)
        assertNull(shadowOf(context).nextStartedActivity)
    }
}
