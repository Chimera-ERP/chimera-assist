package ro.chimeraerp.assist

import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** Transport for the same volatile workspace in Flutter and the Android overlay. */
internal class WorkspaceBridge(
    private val session: () -> Session?,
    private val status: () -> String,
    private val demo: () -> Boolean,
    private val minimize: () -> Unit,
    private val resize: (Int) -> Unit,
    private val background: (MethodChannel.Result, Session, () -> Any?) -> Unit,
    private val read: (Session, String) -> Any?
) : MethodChannel.MethodCallHandler {
    override fun onMethodCall(call: MethodCall, reply: MethodChannel.Result) {
        try {
            val current = session()
            val calls = AssistService.calls
            when (call.method) {
                "workspace" -> {
                    val cursor = calls.cursor(current)
                    reply.success(mapOf("id" to calls.id(current), "revision" to calls.current(current)?.sequence,
                        "lookup" to calls.result(current)?.let(MainActivity::lookupMap), "status" to status(), "demo" to demo(),
                        "cursor" to mapOf("kind" to cursor.kind,"partner" to cursor.partner,"action" to cursor.action,
                            "offset" to cursor.offset,"item" to cursor.item,"detailOffset" to cursor.detailOffset,
                            "scroll" to cursor.scroll,"detailScroll" to cursor.detailScroll)))
                }
                "minimize" -> { minimize(); reply.success(null) }
                "resize" -> { resize((call.argument<Number>("height")?.toInt() ?: 240).coerceIn(160, 680)); reply.success(null) }
                "navigate" -> {
                    val cursor = CallContext.Cursor(call.argument("kind"),call.argument("partner"),call.argument("action"),
                        call.argument<Int>("offset") ?: 0,call.argument("item"),call.argument<Int>("detailOffset") ?: 0,
                        call.argument<Number>("scroll")?.toDouble() ?: 0.0,call.argument<Number>("detailScroll")?.toDouble() ?: 0.0)
                    check(calls.navigate(current,requireNotNull(call.argument<Number>("contextId")).toLong(),cursor))
                    reply.success(null)
                }
                "rows", "detail" -> {
                    val identity = requireNotNull(current)
                    val contextId = requireNotNull(call.argument<Number>("contextId")).toLong()
                    check(contextId == calls.id(identity))
                    val partner = calls.result(identity)?.partners?.find {
                        it.kind == call.argument<String>("kind") && it.id == call.argument<String>("id")
                    } ?: throw IllegalArgumentException()
                    val action = requireNotNull(call.argument<String>("action"))
                    require(action in partner.actions)
                    val path = Endpoint.path(partner.kind, partner.id, action, call.argument<Int>("offset") ?: 0,
                        if (call.method == "detail") requireNotNull(call.argument<String>("item")) else null)
                    background(reply,identity) { read(identity,path) }
                }
                else -> reply.notImplemented()
            }
        } catch (_: Exception) { reply.error("cancelled",null,null) }
    }
}
