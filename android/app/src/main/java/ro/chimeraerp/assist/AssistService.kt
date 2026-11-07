package ro.chimeraerp.assist

import android.app.*
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
    internal var readSession: () -> Session? = { SessionStore(this).read() }
    internal var lookupRequest: (Session, String) -> Lookup = Api::lookup
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private val state by lazy { HelperState(this) }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) { dismiss(); return }
            val session = readSession()
            calls.result(session)?.takeIf { it.partners.isNotEmpty() }?.let { bubble(it) }
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
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop" || readSession() == null) {
            state.enabled = false; calls.clear(); state.report("stopped"); stopSelf(); return START_NOT_STICKY
        }
        when (intent?.action) {
            "test-overlay" -> { generation++; calls.clear(); report("testing", R.string.test_success); bubble(null) }
            "lookup" -> intent.getStringExtra("phone")?.let { phone -> readSession()?.let { lookup(calls.begin(phone, state.region(), it)) } }
            else -> {
                val event = calls.current(readSession())
                if (event != null && calls.result(event.session) == null) lookup(event)
                else if (state.enabled) report("ready", R.string.notification)
            }
        }
        if (!state.enabled) handler.postDelayed({ if (!state.enabled) stopSelf() }, 5 * 60 * 1000L)
        return if (state.enabled) START_STICKY else START_NOT_STICKY
    }
    override fun onDestroy() {
        if (active?.get() === this) active = null
        generation++; dismiss(); handler.removeCallbacksAndMessages(null)
        unregisterReceiver(receiver); worker.shutdownNow(); super.onDestroy()
    }
    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun report(code: String, message: Int) {
        state.report(code)
        getSystemService(NotificationManager::class.java).notify(1, notification(this, message))
    }
    private fun lookup(event: CallContext.Event) {
        val request = ++generation
        selection = null; selectedAction = null; dismiss()
        report("looking", R.string.identifying)
        worker.execute {
            try {
                val number = PhoneNumbers.canonical(event.phone, event.region)
                val result = lookupRequest(event.session, number)
                handler.post {
                    if (request != generation || active?.get() !== this || !calls.complete(event, readSession(), result)) return@post
                    if (result.partners.isEmpty()) report("no_match", R.string.no_match)
                    else {
                        report("matched", R.string.match_notification)
                        if (locked()) state.report("locked") else bubble(result)
                    }
                    handler.postDelayed({ if (request == generation) { calls.clear(); dismiss(); if (state.enabled) report("ready", R.string.notification) } }, 5 * 60 * 1000L)
                }
            } catch (error: Exception) {
                handler.post {
                    if (request != generation || calls.current(readSession()) != event) return@post
                    when {
                        error is ApiFailure && error.status == 401 -> {
                            SessionStore(this).clear(); calls.clear(); state.enabled = false
                            report("session_expired", R.string.session_expired)
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
    private fun dismiss() { overlay?.let { try { windows.removeView(it) } catch (_: IllegalArgumentException) {} }; overlay = null }
    private fun attach(view: View, width: Int, height: Int) {
        dismiss()
        if (locked()) { state.report("locked"); return }
        if (!Settings.canDrawOverlays(this)) { state.report("overlay_denied"); return }
        val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; x = dp(12) }
        try { windows.addView(view, params); overlay = view; state.report("overlay_shown") }
        catch (_: RuntimeException) { state.report("overlay_failed") }
    }
    private fun bubble(result: Lookup?) {
        val button = ImageButton(this).apply {
            setImageResource(R.drawable.chimera_logo); scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8)); contentDescription = getString(R.string.open_partner)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(2), Color.rgb(81, 68, 211)) }
            clipToOutline = true; elevation = dp(8).toFloat()
            setOnClickListener { panel(result) }
            setOnLongClickListener { dismiss(); true }
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
    private fun panel(result: Lookup?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.WHITE) }
        }
        fun title(text: String) { box.addView(TextView(this).apply { this.text = text; textSize = 18f; setTextColor(Color.BLACK); setPadding(0, dp(6), 0, dp(6)) }) }
        fun button(text: String, action: () -> Unit) { box.addView(Button(this).apply { this.text = text; isAllCaps = false; setOnClickListener { action() } }) }
        title(getString(R.string.app_name))
        if (result == null) {
            title(getString(R.string.test_success))
        } else if (result.partners.size == 1) {
            val partner = result.partners.single()
            title(partner.name)
            if (partner.actions.isEmpty()) title(getString(R.string.no_actions))
            partner.actions.forEach { action -> button(getString(MainActivity.actionLabel(action))) { open(partner, action) } }
        } else {
            title(getString(R.string.choose_partner))
            result.partners.forEach { partner -> button(partner.name + " · " + getString(if (partner.kind == "supplier") R.string.supplier else R.string.client)) { open(partner, null) } }
        }
        if (result?.hasMore == true) title(getString(R.string.more_matches))
        button(getString(R.string.collapse)) { bubble(result) }
        button(getString(R.string.close)) { dismiss() }
        val scroll = ScrollView(this).apply { addView(box) }
        val metrics = resources.displayMetrics
        attach(scroll, minOf(dp(320), metrics.widthPixels - dp(24)), minOf(dp(420), metrics.heightPixels - dp(100)))
    }
    private fun open(partner: Partner, action: String?) {
        if (locked()) { dismiss(); return }
        selection = partner
        selectedAction = action
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        dismiss()
    }
    companion object {
        val calls = CallContext({ SystemClock.elapsedRealtime() })
        private var active: WeakReference<AssistService>? = null
        private var selection: Partner? = null
        private var selectedAction: String? = null
        fun receive(context: Context, phone: String, region: String? = HelperState(context).region()) {
            val state = HelperState(context)
            state.report("received")
            if (!state.enabled) { state.report("stopped"); return }
            val session = SessionStore(context).read() ?: run { state.report("session_expired"); return }
            val event = calls.begin(phone, region, session)
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
        fun clear() { calls.clear(); selection = null; selectedAction = null }
        fun running() = active?.get() != null
        fun overlayAttached() = active?.get()?.overlay != null
        fun takeSelection(): Partner? = selection.also { selection = null }
        fun takeAction(): String? = selectedAction.also { selectedAction = null }
        private fun notifications(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("assist-status", context.getString(R.string.app_name), NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) })
        }
        private fun notification(context: Context, message: Int): Notification {
            val stop = PendingIntent.getService(context, 2, Intent(context, AssistService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(context, "assist-status").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_name)).setContentText(context.getString(message))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_SECRET)
                .addAction(Notification.Action.Builder(null, context.getString(R.string.disable), stop).build()).build()
        }
    }
}
