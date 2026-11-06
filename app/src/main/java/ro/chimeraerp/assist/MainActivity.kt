package ro.chimeraerp.assist

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var store: SessionStore
    private lateinit var browserLogin: BrowserLogin
    private lateinit var content: LinearLayout
    private var status: TextView? = null
    private val worker = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var busy = false
    private var enterprise = false
    private var enterpriseAddress = ""

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        store = SessionStore(this)
        browserLogin = BrowserLogin(this)
        enterprise = state?.getBoolean("enterprise") ?: false
        enterpriseAddress = state?.getString("enterprise_address") ?: ""
        home()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("enterprise", enterprise)
        outState.putString("enterprise_address", enterpriseAddress)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        updateStatus()
        AssistService.takeSelection()?.let { partner ->
            val action = AssistService.takeAction()
            if (action != null && action in partner.actions) showRows(partner, action, 0) else showPartner(partner)
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent) }
    override fun onDestroy() { generation++; worker.shutdownNow(); browserLogin.dispose(); super.onDestroy() }
    @Deprecated("Android activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100) return
        val pending = store.takePending()
        home()
        val message = label(getString(R.string.login_cancelled))
        if (resultCode != RESULT_OK || data == null || pending == null) return
        busy = true; message.text = getString(R.string.loading)
        val request = ++generation
        worker.execute { browserLogin.finish(data, pending) { session, error ->
            runOnUiThread {
                if (!isFinishing && request == generation) {
                    busy = false
                    if (session != null && error == null) { store.save(session); home() }
                    else message.text = getString(R.string.login_failed)
                }
            }
        } }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun page(title: String) {
        generation++
        busy = false
        status = null
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(24)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(android.view.WindowInsets.Type.systemBars()) else null
            view.setPadding(0, bars?.top ?: insets.systemWindowInsetTop, 0, bars?.bottom ?: insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
        label(title, 28)
    }
    private fun label(text: String, size: Int = 16): TextView = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(Color.rgb(26, 29, 48)); setPadding(0, dp(8), 0, dp(8))
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun button(text: String, work: () -> Unit): Button = Button(this).apply {
        this.text = text; isAllCaps = false; minHeight = dp(48)
        setOnClickListener { if (!busy) work() }
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }
    private fun field(label: Int, type: Int, value: String = ""): EditText {
        val title = label(getString(label), 14)
        return EditText(this).apply {
            id = View.generateViewId(); title.labelFor = id
            inputType = type; setSingleLine(); setText(value); minHeight = dp(48)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }
    private fun errorText(error: Exception): String = getString(when {
        error is ApiFailure && error.code in listOf("browser_login_unavailable", "browser_login_invalid") -> R.string.login_unavailable
        error is ApiFailure && error.status == 401 -> R.string.session_expired
        error is ApiFailure && error.status == 403 -> R.string.forbidden
        error is ApiFailure && error.status == 400 -> R.string.invalid_input
        error is IllegalArgumentException -> R.string.invalid_input
        else -> R.string.network_error
    })
    private fun <T> load(message: TextView, work: () -> T, done: (T) -> Unit) {
        busy = true; message.text = getString(R.string.loading)
        val request = ++generation
        worker.execute {
            try {
                val result = work()
                runOnUiThread { if (!isFinishing && request == generation) { busy = false; message.text = ""; done(result) } }
            } catch (error: Exception) {
                runOnUiThread { if (!isFinishing && request == generation) {
                    busy = false; message.text = errorText(error)
                    if (error is ApiFailure && error.status == 401) {
                        stopService(Intent(this, AssistService::class.java)); store.clear()
                        button(getString(R.string.sign_in)) { home() }
                    }
                } }
            }
        }
    }
    private fun home() {
        val session = store.read()
        if (session == null) { welcome(); return }
        page(getString(R.string.app_name))
        label(getString(R.string.tagline))
        label(session.organization, 20)
        label(session.origin, 13)
        status = label("")
        button(getString(R.string.caller_role)) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 1)
            else status?.text = getString(R.string.role_unavailable)
        }
        button(getString(R.string.overlay_permission)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        label(getString(R.string.contacts_explanation), 14)
        button(getString(R.string.contacts_permission)) { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 2) }
        button(getString(R.string.enable)) {
            if (!ready()) { status?.text = getString(R.string.setup_required); return@button }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
            startForegroundService(Intent(this, AssistService::class.java))
            status?.postDelayed({ updateStatus() }, 300)
        }
        button(getString(R.string.disable)) { stopService(Intent(this, AssistService::class.java)); status?.postDelayed({ updateStatus() }, 200) }
        label(getString(R.string.manual_lookup), 20)
        val phone = field(R.string.phone, InputType.TYPE_CLASS_PHONE)
        val message = label("")
        button(getString(R.string.lookup)) {
            val number = phone.text.toString().trim()
            load(message, { Api.lookup(session, number) }) { result -> showLookup(result) }
        }
        button(getString(R.string.sign_out)) { stopService(Intent(this, AssistService::class.java)); store.clear(); home() }
        updateStatus()
    }
    private fun welcome() {
        page("")
        content.removeAllViews()
        content.gravity = Gravity.CENTER
        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.chimera_logo)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(112), dp(112)).apply { bottomMargin = dp(24) })
        label(getString(R.string.welcome), 28).gravity = Gravity.CENTER
        label(getString(R.string.tagline)).apply { gravity = Gravity.CENTER; setTextColor(Color.rgb(99, 102, 118)) }
        label(getString(if (enterprise) R.string.enterprise_server else R.string.cloud), 14).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(28), 0, dp(12))
        }
        val origin = if (enterprise) field(R.string.erp_address, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, enterpriseAddress).apply {
            hint = "https://chimera.firma.ro"
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { enterpriseAddress = s.toString() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        } else null
        val message = TextView(this).apply {
            gravity = Gravity.CENTER; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTextColor(Color.rgb(99, 102, 118)); setPadding(0, dp(16), 0, 0)
        }
        button(getString(R.string.sign_in)) {
            val url = origin?.text?.toString()?.trim()
            load(message, { if (url == null) browserLogin.prepareCloud() else browserLogin.prepare(url) }) { pending ->
                try {
                    stopService(Intent(this, AssistService::class.java))
                    store.begin(pending)
                    startActivityForResult(browserLogin.intent(pending), 100)
                    message.text = getString(R.string.login_browser)
                } catch (_: Exception) { store.takePending(); message.text = getString(R.string.browser_unavailable) }
            }
        }.apply {
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), GradientDrawable().apply {
                setColor(Color.rgb(81, 68, 211)); cornerRadius = dp(12).toFloat()
            }, null)
            elevation = 0f; stateListAnimator = null
            setTextColor(Color.WHITE); minHeight = dp(56)
        }
        button(getString(if (enterprise) R.string.use_cloud else R.string.enterprise_option)) {
            enterprise = !enterprise; welcome()
        }.apply {
            val selectable = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
            setBackgroundResource(selectable.resourceId)
            elevation = 0f; stateListAnimator = null; setTextColor(Color.rgb(81, 68, 211))
        }
        content.addView(message, LinearLayout.LayoutParams(-1, -2))
    }
    private fun ready(): Boolean = Settings.canDrawOverlays(this) && getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    private fun updateStatus() {
        status?.text = getString(R.string.setup_status, getString(if (AssistService.running()) R.string.running else R.string.stopped),
            getString(if (ready()) R.string.setup_ready else R.string.setup_required))
    }
    private fun showLookup(result: Lookup) {
        page(getString(R.string.partners))
        button(getString(R.string.back)) { home() }
        if (result.partners.isEmpty()) label(getString(R.string.no_match))
        else label(getString(R.string.choose_partner))
        result.partners.forEach { partner -> button("${partner.name}\n${getString(if (partner.kind == "supplier") R.string.supplier else R.string.client)} · ${partner.taxId}") { showPartner(partner) } }
        if (result.hasMore) label(getString(R.string.more_matches))
    }
    private fun showPartner(partner: Partner) {
        if (store.read() == null) { home(); return }
        page(partner.name)
        label(getString(if (partner.kind == "supplier") R.string.supplier else R.string.client) + " · " + partner.taxId)
        button(getString(R.string.back)) { home() }
        if (partner.actions.isEmpty()) label(getString(R.string.no_actions))
        partner.actions.forEach { action -> button(getString(actionLabel(action))) { showRows(partner, action, 0) } }
    }
    private fun showRows(partner: Partner, action: String, offset: Int) {
        val session = store.read() ?: return home()
        page(getString(actionLabel(action)))
        label(partner.name, 20)
        button(getString(R.string.back)) { showPartner(partner) }
        val message = label("")
        load(message, { Api.request(session.origin, Endpoint.path(partner.kind, partner.id, action, offset), session.token) }) { result ->
            val rows = result.getJSONArray("rows")
            if (rows.length() == 0) label(getString(R.string.empty))
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                label(row.getString("label"), 18)
                val detail = row.getString("detail")
                label(if (detail == "invoiced") getString(R.string.invoiced_product) else detail, 14)
                if (!row.isNull("amount")) label(row.getString("amount") + " " + row.optString("currency", ""), 18)
                if (!row.isNull("outstanding")) label(getString(R.string.outstanding, row.getString("outstanding"), row.optString("currency", "")), 14)
                content.addView(View(this).apply { setBackgroundColor(Color.LTGRAY) }, LinearLayout.LayoutParams(-1, dp(1)))
            }
            if (offset > 0) button(getString(R.string.previous)) { showRows(partner, action, offset - 25) }
            if (result.getBoolean("has_more") && offset < 10000) button(getString(R.string.next)) { showRows(partner, action, offset + 25) }
        }
        button(getString(R.string.retry)) { showRows(partner, action, offset) }
    }
    companion object {
        fun actionLabel(action: String): Int = when (action) { "invoices" -> R.string.invoices; "payments" -> R.string.payments; else -> R.string.products }
    }
}
