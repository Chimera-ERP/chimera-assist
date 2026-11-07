package ro.chimeraerp.assist

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.Executors

/** Flutter owns presentation; native code keeps browser proofs, tokens and Android lifecycle. */
class MainActivity : FlutterActivity() {
    private data class ManualResult(val session: Session, val phone: String, val lookup: Lookup)
    @Volatile private var manualResult: ManualResult? = null
    private val store by lazy { SessionStore(this) }
    private val helper by lazy { HelperState(this) }
    private val browserLogin by lazy { BrowserLogin(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var loginBusy = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    override fun configureFlutterEngine(engine: FlutterEngine) {
        super.configureFlutterEngine(engine)
        MethodChannel(engine.dartExecutor.binaryMessenger, "ro.chimeraerp.assist/native").setMethodCallHandler { call, reply ->
            try {
                when (call.method) {
                    "state" -> reply.success(snapshot(call.argument<Boolean>("consumeSelection") != false))
                    "regions" -> reply.success(PhoneNumbers.regions.sortedBy { Locale.Builder().setRegion(it).build().displayCountry }.map {
                        mapOf("code" to it, "name" to Locale.Builder().setRegion(it).build().displayCountry)
                    })
                    "region" -> { helper.regionOverride = call.argument<String>("country"); reply.success(snapshot()) }
                    "permission" -> {
                        when (call.argument<String>("kind")) {
                            "role" -> {
                                val manager = getSystemService(RoleManager::class.java)
                                require(manager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING))
                                startActivityForResult(manager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 1)
                            }
                            "overlay" -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                            "phoneState" -> requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 4)
                            "contacts" -> requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 2)
                            "notifications" -> if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
                            else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                            else -> throw IllegalArgumentException()
                        }
                        reply.success(null)
                    }
                    "enable" -> {
                        require(store.read() != null && ready())
                        helper.enabled = true
                        try { startForegroundService(Intent(this, AssistService::class.java)) }
                        catch (error: RuntimeException) { helper.enabled = false; helper.report("start_blocked"); throw error }
                        reply.success(null)
                    }
                    "openWorkspace" -> {
                        if (!Settings.canDrawOverlays(this)) { reply.error("overlay_denied",null,null); return@setMethodCallHandler }
                        if (call.argument<Boolean>("manual") == true) {
                            val saved = requireNotNull(manualResult)
                            AssistService.presentManual(this,saved.session,saved.phone,saved.lookup,requireNotNull(call.argument("kind")),requireNotNull(call.argument("id")))
                        } else AssistService.openWorkspace(this,call.argument("kind"),call.argument("id"))
                        reply.success(null)
                    }
                    "disable" -> { disable(); reply.success(snapshot()) }
                    "testOverlay" -> {
                        require(store.read() != null && Settings.canDrawOverlays(this))
                        startForegroundService(Intent(this, AssistService::class.java).setAction("test-overlay"))
                        reply.success(null)
                    }
                    "testNumber" -> {
                        require(store.read() != null && Settings.canDrawOverlays(this))
                        val phone = PhoneNumbers.canonical(requireNotNull(call.argument<String>("phone")), helper.region())
                        startForegroundService(Intent(this, AssistService::class.java).setAction("lookup").putExtra("phone", phone))
                        reply.success(null)
                    }
                    "lookup" -> {
                        val session = requireNotNull(store.read())
                        val phone = PhoneNumbers.canonical(requireNotNull(call.argument<String>("phone")), helper.region())
                        background(reply, session) {
                            val result = Api.lookup(SessionTokens(this).valid(session), phone)
                            if (session.sameLogin(store.read())) manualResult = ManualResult(session,phone,result)
                            lookupMap(result)
                        }
                    }
                    "rows", "detail" -> {
                        val session = requireNotNull(store.read())
                        val path = Endpoint.path(requireNotNull(call.argument("kind")), requireNotNull(call.argument("id")),
                            requireNotNull(call.argument("action")), call.argument<Int>("offset") ?: 0, if (call.method == "detail") requireNotNull(call.argument("item")) else null)
                        background(reply, session) { jsonValue(Api.request(session.origin, path, SessionTokens(this).valid(session).token)) }
                    }
                    "login" -> {
                        check(!loginBusy)
                        val origin = call.argument<String>("origin")?.let(Endpoint::origin)
                        helper.rememberLogin(origin)
                        loginBusy = true
                        val request = ++generation
                        worker.execute {
                            try {
                                val pending = if (origin == null) browserLogin.prepareCloud() else browserLogin.prepare(origin)
                                runOnUiThread {
                                    if (isDestroyed || request != generation) { reply.error("cancelled", null, null); return@runOnUiThread }
                                    try {
                                        disable(); store.begin(pending)
                                        startActivityForResult(browserLogin.intent(pending), 100)
                                        reply.success(null)
                                    } catch (_: Exception) { loginBusy = false; store.takePending(); reply.error("login_failed", null, null) }
                                }
                            } catch (error: Exception) { runOnUiThread { loginBusy = false; reply.error(errorCode(error), null, null) } }
                        }
                    }
                    "logout" -> { generation++; disable(); store.clear(); helper.clear(); reply.success(snapshot()) }
                    else -> reply.notImplemented()
                }
            } catch (error: Exception) { reply.error(errorCode(error), null, null) }
        }
    }
    override fun onResume() {
        super.onResume()
        if (helper.enabled && ready() && store.read() != null && !AssistService.running()) {
            try { startForegroundService(Intent(this, AssistService::class.java)) }
            catch (_: RuntimeException) { helper.report("start_blocked") }
        }
    }
    override fun onDestroy() { generation++; worker.shutdownNow(); browserLogin.dispose(); super.onDestroy() }
    @Deprecated("Android activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100) return
        val pending = store.takePending()
        if (resultCode != RESULT_OK || data == null || pending == null) { loginBusy = false; helper.report("login_cancelled"); return }
        val request = ++generation
        worker.execute { browserLogin.finish(data, pending) { session, error ->
            runOnUiThread {
                if (!isDestroyed && request == generation) {
                    loginBusy = false
                    if (session != null && error == null) { store.save(session); helper.report("signed_in") }
                    else helper.report("login_failed")
                }
            }
        } }
    }
    private fun disable() {
        manualResult = null; helper.enabled = false; AssistService.clear(); stopService(Intent(this, AssistService::class.java)); helper.report("stopped")
        getSystemService(NotificationManager::class.java).cancel(2)
    }
    private fun ready() = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED && Settings.canDrawOverlays(this) && getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    private fun snapshot(consumeSelection: Boolean = false): Map<String, Any?> {
        val session = store.read()
        helper.seedLogin(session)
        val manager = getSystemService(NotificationManager::class.java)
        return mapOf("enterpriseOrigin" to helper.enterpriseOrigin, "enterpriseLogin" to helper.enterpriseLogin, "platform" to "android", "session" to session?.let { mapOf("origin" to it.origin, "organization" to it.organization) },
            "overlayAttached" to AssistService.overlayAttached(), "enabled" to helper.enabled, "running" to AssistService.running(), "role" to getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING),
            "roleAvailable" to getSystemService(RoleManager::class.java).isRoleAvailable(RoleManager.ROLE_CALL_SCREENING),
            "overlay" to Settings.canDrawOverlays(this), "contacts" to (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED),
            "notifications" to (manager.areNotificationsEnabled() && manager.getNotificationChannel("assist-status")?.importance != NotificationManager.IMPORTANCE_NONE),
            "lastCallTime" to helper.lastCallTime(), "status" to helper.status(), "statusTime" to helper.statusTime(), "country" to helper.region(), "countryOverride" to helper.regionOverride,
            "loginBusy" to loginBusy, "current" to AssistService.calls.result(session)?.let { lookupMap(it) },
            "phoneState" to (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED))
    }
    private fun background(reply: MethodChannel.Result, session: Session, task: () -> Any?) {
        val request = generation
        worker.execute {
            try {
                val value = task()
                runOnUiThread { if (!isDestroyed && request == generation && session.sameLogin(store.read())) reply.success(value) else reply.error("cancelled", null, null) }
            } catch (error: Exception) {
                runOnUiThread {
                    if (error is ApiFailure && error.status == 401 && session.sameLogin(store.read())) { disable(); store.clear(); helper.report(SessionTokens.expiryCode(error)) }
                    reply.error(errorCode(error), null, null)
                }
            }
        }
    }
    private fun errorCode(error: Exception) = when {
        error is ApiFailure && error.status == 401 -> SessionTokens.expiryCode(error)
        error is ApiFailure && error.status == 403 -> "forbidden"
        error is IllegalArgumentException || error is ApiFailure && error.status == 400 -> "invalid_phone"
        else -> "network_error"
    }
    companion object {
        fun partnerMap(p: Partner) = mapOf("kind" to p.kind, "id" to p.id, "name" to p.name, "tax_id" to p.taxId, "actions" to p.actions)
        fun lookupMap(l: Lookup) = mapOf("partners" to l.partners.map { partnerMap(it) }, "has_more" to l.hasMore)
        fun jsonValue(value: Any?): Any? = when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> value.keys().asSequence().associateWith { jsonValue(value.get(it)) }
            is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
            else -> value
        }
        fun actionLabel(action: String): Int = when (action) { "invoices" -> R.string.invoices; "payments" -> R.string.payments; else -> R.string.products }
    }
}
