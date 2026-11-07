package ro.chimeraerp.assist

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AssistServiceTest {
    private lateinit var controller: ServiceController<AssistService>
    private lateinit var service: AssistService
    private val session = Session("https://erp.example.test", "synthetic", "Synthetic")
    private val match = Lookup(listOf(Partner("supplier", "synthetic", "Synthetic", "", listOf("invoices"))), false)
    @Before fun setup() {
        controller = Robolectric.buildService(AssistService::class.java).create()
        service = controller.get()
        service.readSession = { session }
        HelperState(service).clear()
        AssistService.clear()
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
    }
    @After fun cleanup() { controller.destroy(); AssistService.clear() }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) { Thread.sleep(10); ShadowLooper.idleMainLooper() }
        assertTrue(condition())
    }
    @Test fun explicitCircleTestUsesARealOverlayWithoutLookingUpBusinessData() {
        service.lookupRequest = { _, _ -> throw AssertionError("Demo must not call ERP") }
        service.onStartCommand(Intent().setAction("test-overlay"), 0, 1)
        assertTrue(AssistService.overlayAttached())
        assertNull(AssistService.calls.result(session))
        assertNotNull(service.getSystemService(NotificationManager::class.java).getNotificationChannel("assist-status"))
    }
    @Test fun rejectedOverlayIsReportedInsteadOfClaimingSuccess() {
        ShadowSettings.setCanDrawOverlays(false)
        service.onStartCommand(Intent().setAction("test-overlay"), 0, 1)
        assertFalse(AssistService.overlayAttached())
        assertEquals("overlay_denied", HelperState(service).status())
    }
    @Test fun nationalNumberTestUsesCanonicalLookupAndExposesSameContextToFlutter() {
        HelperState(service).regionOverride = "GB"
        service.lookupRequest = { actual, number -> assertEquals(session,actual); assertEquals("+442079460123",number); match }
        service.onStartCommand(Intent().setAction("lookup").putExtra("phone","020 7946 0123"),0,1)
        await { AssistService.calls.result(session) != null }
        assertEquals(match, AssistService.calls.result(session))
        assertTrue(AssistService.overlayAttached())
    }
    @Test fun callWhileLockedCanDisplayAfterUnlockWithoutRepeatingTheLookup() {
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        service.lookupRequest = { _, _ -> match }
        service.onStartCommand(Intent().setAction("lookup").putExtra("phone","+442079460123"),0,1)
        await { HelperState(service).status() == "locked" }
        assertFalse(AssistService.overlayAttached())
        assertEquals(match,AssistService.calls.result(session))
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        service.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT)); ShadowLooper.idleMainLooper()
        assertTrue(AssistService.overlayAttached())
    }
    @Test fun failuresAreVisibleAndOptedInHelperSurvivesSystemRestart() {
        HelperState(service).enabled = true
        assertEquals(Service.START_STICKY,service.onStartCommand(null,0,1))
        service.lookupRequest = { _, _ -> throw java.io.IOException("synthetic offline") }
        service.onStartCommand(Intent().setAction("lookup").putExtra("phone","+442079460123"),0,2)
        await { HelperState(service).status() == "network_error" }
        assertNull(AssistService.calls.result(session))
        assertEquals(Service.START_NOT_STICKY,service.onStartCommand(Intent().setAction("stop"),0,3))
        assertFalse(HelperState(service).enabled)
    }

    @Test fun outsideTouchMinimizesWithoutLaunchingActivityOrDiscardingCallerNavigation() {
        var created=0
        val content=FrameLayout(service)
        service.panelFactory={ created++; object:PanelSurface {
            override val view:View=content
            override fun visible(value:Boolean) {}
            override fun destroy() {}
        } }
        service.lookupRequest={_,_->match}
        service.onStartCommand(Intent().setAction("lookup").putExtra("phone","+442079460123"),0,1)
        await { AssistService.calls.result(session)!=null }
        val id=AssistService.calls.id(session)!!
        val cursor=CallContext.Cursor("supplier","synthetic","invoices",25,"invoice_1",0,99.0,0.0)
        AssistService.calls.navigate(session,id,cursor)
        service.expand()
        val container=content.parent as View
        val params=container.layoutParams as WindowManager.LayoutParams
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH != 0)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
        assertTrue(params.width < service.resources.displayMetrics.widthPixels)
        val event=MotionEvent.obtain(0,0,MotionEvent.ACTION_OUTSIDE,0f,0f,0)
        container.dispatchTouchEvent(event);event.recycle()
        assertTrue(AssistService.overlayAttached())
        assertFalse(AssistService.calls.expanded(session))
        assertEquals(cursor,AssistService.calls.cursor(session))
        service.expand()
        assertEquals(1,created)
        assertEquals(cursor,AssistService.calls.cursor(session))
        assertNull(shadowOf(service).nextStartedActivity)
    }

    @Test fun changingCallerAndExpiryDiscardPausedPixelsButPreserveUnexpiredNavigation() {
        var created=0; var destroyed=0
        service.panelFactory={ created++; object:PanelSurface {
            override val view:View=FrameLayout(service)
            override fun visible(value:Boolean) {}
            override fun destroy() { destroyed++ }
        } }
        service.lookupRequest={_,_->match}
        service.onStartCommand(Intent().setAction("lookup").putExtra("phone","+442079460123"),0,1)
        await { AssistService.calls.result(session)!=null }
        val cursor=CallContext.Cursor("supplier","synthetic","invoices",25,"invoice_1")
        AssistService.calls.navigate(session,AssistService.calls.id(session)!!,cursor)
        service.expand();service.minimize()
        service.onStartCommand(Intent().setAction("lookup").putExtra("phone","+40700000001"),0,2)
        assertEquals(1,destroyed)
        await { AssistService.calls.result(session)!=null }
        service.expand();service.minimize()
        service.onStartCommand(Intent().setAction("lookup").putExtra("phone","+442079460123"),0,3)
        await { AssistService.calls.result(session)!=null }
        assertEquals(cursor,AssistService.calls.cursor(session))
        service.expand();service.minimize()
        assertEquals(3,created);assertEquals(2,destroyed)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(31))
        ShadowLooper.idleMainLooper(2,java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(3,destroyed)
        assertFalse(AssistService.overlayAttached())
        assertNull(AssistService.calls.current(session))
    }
    @Test fun matchedNotificationReopensOverlayWithoutLaunchingFullScreenActivity() {
        val content = FrameLayout(service)
        service.panelFactory = { object: PanelSurface {
            override val view: View = content
            override fun visible(value: Boolean) {}
            override fun destroy() {}
        } }
        service.lookupRequest = { _, _ -> match }
        service.onStartCommand(Intent().setAction("lookup").putExtra("phone","+442079460123"),0,1)
        await { AssistService.calls.result(session) != null }
        val cursor = CallContext.Cursor("supplier","synthetic","invoices",25,"invoice_1")
        AssistService.calls.navigate(session,AssistService.calls.id(session)!!,cursor)
        val notification = shadowOf(service.getSystemService(NotificationManager::class.java)).getNotification(1)
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(AssistService::class.java.name,intent.component!!.className)
        assertEquals("open-workspace",intent.action)
        service.onStartCommand(intent,0,2)
        assertNotNull(content.parent)
        assertTrue(AssistService.calls.expanded(session))
        assertEquals(cursor,AssistService.calls.cursor(session))
        assertNull(shadowOf(service).nextStartedActivity)
    }

    @Test @Config(qualifiers = "w393dp-h851dp")
    fun detailsKeepBackgroundVisibleAndGrowDownFromTheSameAnchor() {
        val content = FrameLayout(service)
        service.panelFactory = { object: PanelSurface {
            override val view: View = content
            override fun visible(value: Boolean) {}
            override fun destroy() {}
        } }
        service.onStartCommand(Intent().setAction("test-overlay"),0,1)
        service.expand()
        val container = content.parent as View
        val before = (container.layoutParams as WindowManager.LayoutParams).y
        service.resizePanel(10000)
        val params = container.layoutParams as WindowManager.LayoutParams
        val metrics = service.resources.displayMetrics
        assertEquals(before,params.y)
        assertTrue(params.height <= metrics.heightPixels * .62)
        assertTrue(params.width <= metrics.widthPixels - 48 * metrics.density)
        assertTrue(params.y + params.height < metrics.heightPixels)
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,params.type)
        assertNull(shadowOf(service).nextStartedActivity)
    }

    @Test fun manualSelectionReopensSamePartnerCursorAndRejectsStaleLoginOrForeignPartner() {
        AssistService.presentManual(service,session,"+442079460123",match,"supplier","synthetic")
        val cursor=CallContext.Cursor("supplier","synthetic","invoices",25,"invoice_1")
        AssistService.calls.navigate(session,AssistService.calls.id(session)!!,cursor)
        AssistService.presentManual(service,session,"+442079460123",match,"supplier","synthetic")
        assertEquals(cursor,AssistService.calls.cursor(session))
        val context=AssistService.calls.id(session)
        try {AssistService.presentManual(service,session.copy(loginId="foreign"),"+442079460456",match,"supplier","synthetic");fail("Must reject old login")}catch(_:IllegalArgumentException){}
        try {AssistService.presentManual(service,session,"+442079460456",match,"supplier","unknown");fail("Must reject unknown partner")}catch(_:IllegalArgumentException){}
        assertEquals(context,AssistService.calls.id(session))
        assertEquals(cursor,AssistService.calls.cursor(session))
        assertNull(shadowOf(service).nextStartedActivity)
    }

}
