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

/** Started only by the foreground setup screen. No background-start workaround. */
class AssistService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1), ThreadPoolExecutor.DiscardOldestPolicy())
    private var generation = 0
    private var overlay: View? = null
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { generation++; dismiss() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        active = WeakReference(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("assist", getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 2, Intent(this, AssistService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "assist").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.notification))
            .setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(Notification.Action.Builder(null, getString(R.string.disable), stop).build()).build())
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop" || SessionStore(this).read() == null) stopSelf()
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        active = null; selection = null; selectedAction = null; generation++; dismiss(); handler.removeCallbacksAndMessages(null)
        unregisterReceiver(receiver); worker.shutdownNow(); super.onDestroy()
    }
    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun lookup(phone: String) {
        generation++; val request = generation
        selection = null; dismiss()
        if (locked() || !Settings.canDrawOverlays(this)) return
        val session = SessionStore(this).read() ?: return
        worker.execute {
            try {
                val result = Api.lookup(session, phone)
                handler.post {
                    if (request == generation && active?.get() === this && !locked() && SessionStore(this).read() == session && result.partners.isNotEmpty()) {
                        bubble(result)
                        handler.postDelayed({ if (request == generation) dismiss() }, 5 * 60 * 1000L)
                    }
                }
            } catch (error: Exception) {
                handler.post { if (request == generation && error is ApiFailure && error.status == 401) { SessionStore(this).clear(); stopSelf() } }
            }
        }
    }
    private fun dismiss() { overlay?.let { try { windows.removeView(it) } catch (_: IllegalArgumentException) {} }; overlay = null }
    private fun attach(view: View, width: Int, height: Int) {
        dismiss()
        if (locked() || !Settings.canDrawOverlays(this)) return
        val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; x = dp(12) }
        try { windows.addView(view, params); overlay = view } catch (_: RuntimeException) { overlay = null }
    }
    private fun bubble(result: Lookup) {
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
                MotionEvent.ACTION_UP -> { if (!dragged) view.performClick(); true }
                else -> false
            }
        }
        attach(button, dp(64), dp(64))
    }
    private fun panel(result: Lookup) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.WHITE) }
        }
        fun title(text: String) { box.addView(TextView(this).apply { this.text = text; textSize = 18f; setTextColor(Color.BLACK); setPadding(0, dp(6), 0, dp(6)) }) }
        fun button(text: String, action: () -> Unit) { box.addView(Button(this).apply { this.text = text; isAllCaps = false; setOnClickListener { action() } }) }
        title(getString(R.string.app_name))
        if (result.partners.size == 1) {
            val partner = result.partners.single()
            title(partner.name)
            if (partner.actions.isEmpty()) title(getString(R.string.no_actions))
            partner.actions.forEach { action -> button(getString(MainActivity.actionLabel(action))) { open(partner, action) } }
        } else {
            title(getString(R.string.choose_partner))
            result.partners.forEach { partner -> button(partner.name + " · " + getString(if (partner.kind == "supplier") R.string.supplier else R.string.client)) { open(partner, null) } }
        }
        if (result.hasMore) title(getString(R.string.more_matches))
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
        private var active: WeakReference<AssistService>? = null
        private var selection: Partner? = null
        private var selectedAction: String? = null
        fun identify(phone: String) { active?.get()?.let { service -> service.handler.post { service.lookup(phone) } } }
        fun running() = active?.get() != null
        fun takeSelection(): Partner? = selection.also { selection = null }
        fun takeAction(): String? = selectedAction.also { selectedAction = null }
    }
}
