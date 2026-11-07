package ro.chimeraerp.assist

import android.app.*
import android.Manifest
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.lang.ref.WeakReference

/** User-enabled foreground helper; no call logs, address-book reads or background activity launch. */
class AssistService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1), ThreadPoolExecutor.DiscardOldestPolicy())
    private var generation = 0
    private var overlay: View? = null
    private var panelSurface: PanelSurface? = null
    private var panelContainer: View? = null
    private var panelHeight = 260
    private var panelContextId: Long? = null
    private var expanded = false
    private var demo = false
    private var bubbleX = 12
    private var bubbleY = 0
    private val dataWorker = Executors.newSingleThreadExecutor()
    internal var panelFactory: () -> PanelSurface = { FlutterPanel(this, workspace) }
    internal var isCallActive: () -> Boolean = {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) false
        else try { getSystemService(TelecomManager::class.java).isInCall } catch (_: SecurityException) { false }
    }
    private val workspace by lazy { WorkspaceBridge(readSession, { state.status() }, { demo }, ::minimize, ::resizePanel,
        ::readBackground, { session, path -> MainActivity.jsonValue(Api.request(session.origin, path, SessionTokens(this).valid(session).token)) }) }
    private val maintenance = object : Runnable {
        override fun run() {
            updateCallState()
            if (!demo && calls.current(readSession()) == null) releasePanel()
            handler.postDelayed(this, 2000)
        }
    }
    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { updateCallState() }
    }
    internal var readSession: () -> Session? = { SessionStore(this).read() }
    internal var lookupRequest: (Session, String) -> Lookup = { session, phone -> Api.lookup(SessionTokens(this).valid(session),phone) }
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private val state by lazy { HelperState(this) }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) { dismiss(); return }
            val session = readSession()
            calls.result(session)?.takeIf { it.partners.isNotEmpty() }?.let { render(it) }
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        active = WeakReference(this)
        notifications(this)
        getSystemService(NotificationManager::class.java).cancel(2)
        startForeground(1, notification(this, R.string.notification))
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_USER_PRESENT) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
        val phoneFilter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        // Protected system broadcast; no number is read. Telecom gives aggregate state across SIMs.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(phoneReceiver, phoneFilter, RECEIVER_EXPORTED) else registerReceiver(phoneReceiver, phoneFilter)
        handler.post(maintenance)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop" || readSession() == null) {
            state.enabled = false; calls.clear(); state.report("stopped"); stopSelf(); return START_NOT_STICKY
        }
        when (intent?.action) {
            "open-workspace" -> {
                if (demo || calls.result(readSession())?.partners?.isNotEmpty() == true) {
                    if (panelContextId != calls.id(readSession())) releasePanel()
                    expand()
                }
            }
            "test-overlay" -> { releasePanel(); demo = true; report("testing", R.string.test_success); bubble(null) }
            "lookup" -> intent.getStringExtra("phone")?.let { phone -> readSession()?.let { lookup(calls.begin(phone, state.region(), it)) } }
            else -> {
                val event = calls.current(readSession())
                if (event != null && calls.result(event.session) == null) lookup(event)
                else if (event != null) calls.result(event.session)?.let { render(it) }
                else if (state.enabled) report("ready", R.string.notification)
            }
        }
        if (!state.enabled) handler.postDelayed({ if (!state.enabled) stopSelf() }, 5 * 60 * 1000L)
        return if (state.enabled) START_STICKY else START_NOT_STICKY
    }
    override fun onDestroy() {
        if (active?.get() === this) active = null
        generation++; releasePanel(); handler.removeCallbacksAndMessages(null)
        unregisterReceiver(receiver); unregisterReceiver(phoneReceiver); worker.shutdownNow(); dataWorker.shutdownNow()
        super.onDestroy()
    }
    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun report(code: String, message: Int) {
        state.report(code)
        getSystemService(NotificationManager::class.java).notify(1, notification(this, message))
    }
    private fun lookup(event: CallContext.Event) {
        val request = ++generation
        demo = false; updateCallState(); expanded = calls.expanded(readSession())
        // A paused renderer must never flash the previous caller before its next bridge poll.
        if (panelContextId != calls.id(readSession())) releasePanel()
        if (!expanded) dismiss()
        report("looking", R.string.identifying)
        worker.execute {
            try {
                val number = PhoneNumbers.canonical(event.phone, event.region)
                val result = lookupRequest(event.session, number)
                handler.post {
                    if (request != generation || active?.get() !== this || !calls.complete(event, readSession(), result)) return@post
                    if (result.partners.isEmpty()) { dismiss(); report("no_match", R.string.no_match) }
                    else {
                        report("matched", R.string.match_notification)
                        if (locked()) state.report("locked") else render(result)
                    }
                }
            } catch (error: Exception) {
                handler.post {
                    if (request != generation || calls.current(readSession()) != event) return@post
                    when {
                        error is ApiFailure && error.status == 401 -> {
                            SessionStore(this).clear(); calls.clear(); state.enabled = false
                            report(SessionTokens.expiryCode(error), if (error.code == "session_upgrade_required") R.string.session_upgrade_required else R.string.session_expired)
                            stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
                        }
                        error is ApiFailure && error.status == 403 -> report("forbidden", R.string.forbidden)
                        error is IllegalArgumentException || error is ApiFailure && error.status == 400 -> report("invalid_phone", R.string.invalid_input)
                        else -> report("network_error", R.string.network_error)
                    }
                }
            }
        }
    }
    private fun dismiss() { panelSurface?.visible(false); overlay?.let { try { windows.removeView(it) } catch (_: IllegalArgumentException) {} }; overlay = null }
    private fun releasePanel() {
        dismiss(); panelSurface?.destroy(); panelSurface = null; panelContainer = null; panelContextId = null
    }
    private fun attach(view: View, width: Int, height: Int) {
        dismiss()
        if (locked()) { state.report("locked"); return }
        if (!Settings.canDrawOverlays(this)) { state.report("overlay_denied"); return }
        val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; x = dp(bubbleX); y = bubbleY }
        try { windows.addView(view, params); overlay = view; state.report("overlay_shown") }
        catch (_: RuntimeException) { state.report("overlay_failed") }
    }
    private fun bubble(result: Lookup?) {
        val button = ImageButton(this).apply {
            setImageResource(R.drawable.chimera_logo); scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8)); contentDescription = getString(R.string.open_partner)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(2), Color.rgb(81, 68, 211)) }
            clipToOutline = true; elevation = dp(8).toFloat()
            setOnClickListener { expand() }
        }
        var downX = 0f; var downY = 0f; var initialX = 0; var initialY = 0; var dragged = false
        button.setOnTouchListener { view, event ->
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; initialX = params.x; initialY = params.y; dragged = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > ViewConfiguration.get(this).scaledTouchSlop) dragged = true
                    if (dragged) {
                        view.cancelLongPress()
                        params.x = (initialX - dx.toInt()).coerceIn(0, (resources.displayMetrics.widthPixels - dp(64)).coerceAtLeast(0))
                        val limit = (resources.displayMetrics.heightPixels / 2 - dp(72)).coerceAtLeast(0)
                        params.y = (initialY + dy.toInt()).coerceIn(-limit, limit)
                        bubbleX = (params.x / resources.displayMetrics.density).toInt(); bubbleY = params.y
                        if (overlay === view) windows.updateViewLayout(view, params)
                    }
                    dragged
                }
                MotionEvent.ACTION_UP -> { if (!dragged && overlay === view) view.performClick(); true }
                else -> false
            }
        }
        attach(button, dp(64), dp(64))
    }
    private fun render(result: Lookup) { if (expanded) expand() else bubble(result) }
    internal fun expand() {
        if (locked()) { dismiss(); return }
        expanded = true
        if (!demo) calls.expanded(readSession(),true)
        if (panelSurface == null) { panelSurface = panelFactory(); panelContextId = if (demo) null else calls.id(readSession()) }
        if (panelContainer == null) {
            panelContainer = object : FrameLayout(this) {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { minimize(); return true }
                    return super.dispatchTouchEvent(event)
                }
            }.apply { addView(panelSurface!!.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)) }
        }
        if (overlay !== panelContainer) {
            attach(panelContainer!!, panelWidth(), minOf(dp(panelHeight), panelLimit()))
            resizePanel(panelHeight)
        }
        if (overlay === panelContainer) panelSurface?.visible(true)
    }
    internal fun minimize() {
        expanded = false
        if (!demo) calls.expanded(readSession(),false)
        if (demo) bubble(null) else calls.result(readSession())?.let { bubble(it) } ?: dismiss()
    }
    private fun panelWidth() = minOf(dp(336), resources.displayMetrics.widthPixels - dp(48)).coerceAtLeast(dp(160))
    private fun panelLimit(): Int {
        val metrics = resources.displayMetrics
        val available = metrics.heightPixels - dp(72)
        return minOf(dp(480), if (metrics.heightPixels > metrics.widthPixels) (metrics.heightPixels * .62).toInt() else available).coerceAtMost(available)
    }
    internal fun resizePanel(height: Int) {
        panelHeight = height.coerceIn(160,480)
        val view = overlay?.takeIf { it === panelContainer } ?: return
        val params = view.layoutParams as WindowManager.LayoutParams
        val metrics = resources.displayMetrics
        params.width = panelWidth()
        params.height = minOf(dp(panelHeight), panelLimit())
        params.gravity = Gravity.END or Gravity.TOP
        // Reserve room for details, so expansion grows down from a stable anchor.
        params.y = (metrics.heightPixels/2+bubbleY-dp(32)).coerceIn(dp(12),maxOf(dp(12),metrics.heightPixels-panelLimit()-dp(48)))
        params.x = dp(bubbleX).coerceIn(dp(12),maxOf(dp(12),metrics.widthPixels-params.width-dp(12)))
        windows.updateViewLayout(view,params)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (overlay === panelContainer) resizePanel(panelHeight)
        else if (overlay != null) bubble(calls.result(readSession()))
    }
    private fun updateCallState() { calls.callState(try { isCallActive() } catch (_: SecurityException) { false }) }
    private fun readBackground(reply: MethodChannel.Result, session: Session, read: () -> Any?) {
        val event = calls.current(session)
        dataWorker.execute {
            try {
                val value = read()
                handler.post {
                    if (active?.get() === this && session.sameLogin(readSession()) && calls.current(session) == event) reply.success(value)
                    else reply.error("cancelled",null,null)
                }
            } catch (error: Exception) {
                handler.post {
                    if (session.sameLogin(readSession()) && error is ApiFailure && error.status == 401) {
                        SessionStore(this).clear(); calls.clear(); state.enabled = false; dismiss()
                        report(SessionTokens.expiryCode(error),if (error.code == "session_upgrade_required") R.string.session_upgrade_required else R.string.session_expired); stopSelf()
                    }
                    reply.error(if (error is ApiFailure) when(error.status) {401 -> SessionTokens.expiryCode(error);403 -> "forbidden";404 -> "not_found";else -> "network_error"} else "network_error",null,null)
                }
            }
        }
    }
    companion object {
        val calls = CallContext({ SystemClock.elapsedRealtime() })
        internal fun restartBlocked(context: Context) {
            HelperState(context).report("start_blocked")
            notifications(context)
            context.getSystemService(NotificationManager::class.java).notify(2,notification(context,R.string.restart_required))
        }
        private var active: WeakReference<AssistService>? = null
        fun receive(context: Context, phone: String, region: String? = HelperState(context).region()) {
            val state = HelperState(context)
            state.report("received")
            if (!state.enabled) { state.report("stopped"); return }
            val session = SessionStore(context).read() ?: run { state.report("session_expired"); return }
            val event = try { calls.begin(phone, region, session) } catch (_: IllegalArgumentException) { state.report("invalid_phone"); return }
            val service = active?.get()
            if (service != null) service.handler.post { service.lookup(event) }
            else if (state.enabled) {
                // Telecom may permit starting while bound; Android15 can refuse it. Never swallow refusal.
                try { context.startForegroundService(Intent(context, AssistService::class.java)) }
                catch (_: RuntimeException) {
                    state.report("start_blocked")
                    notifications(context)
                    context.getSystemService(NotificationManager::class.java).notify(2, notification(context, R.string.restart_required))
                }
            } else state.report("stopped")
        }
        fun clear() { calls.clear(); active?.get()?.dismiss() }
        fun running() = active?.get() != null
        fun overlayAttached() = active?.get()?.overlay != null
        private fun currentSession(context: Context) = active?.get()?.readSession() ?: SessionStore(context).read()
        fun openWorkspace(context: Context, kind: String? = null, id: String? = null) {
            val session = requireNotNull(currentSession(context))
            require(Settings.canDrawOverlays(context))
            require(calls.result(session)?.partners?.isNotEmpty() == true)
            if (kind != null || id != null) {
                val selected = calls.result(session)!!.partners.first { it.kind == kind && it.id == id }
                val cursor = calls.cursor(session)
                if (cursor.kind != kind || cursor.partner != id) {
                    check(calls.navigate(session,calls.id(session)!!,CallContext.Cursor(selected.kind,selected.id)))
                }
            }
            context.startForegroundService(Intent(context,AssistService::class.java).setAction("open-workspace"))
        }
        fun presentManual(context: Context, session: Session, phone: String, result: Lookup, kind: String, id: String) {
            require(session.sameLogin(currentSession(context)))
            require(Settings.canDrawOverlays(context))
            require(result.partners.any { it.kind == kind && it.id == id })
            val event = calls.begin(phone,null,session)
            check(calls.complete(event,session,result))
            openWorkspace(context,kind,id)
        }
        private fun notifications(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("assist-status", context.getString(R.string.app_name), NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) })
        }
        private fun notification(context: Context, message: Int): Notification {
            val stop = PendingIntent.getService(context, 2, Intent(context, AssistService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
            val service = active?.get()
            val hasWorkspace = service != null && (service.demo || calls.result(service.readSession())?.partners?.isNotEmpty() == true)
            val open = if (hasWorkspace && Settings.canDrawOverlays(context))
                PendingIntent.getService(context, 3, Intent(context, AssistService::class.java).setAction("open-workspace"), PendingIntent.FLAG_IMMUTABLE)
            else PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(context, "assist-status").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_name)).setContentText(context.getString(message))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_SECRET)
                .addAction(Notification.Action.Builder(null, context.getString(R.string.disable), stop).build()).build()
        }
    }
}
