package ro.chimeraerp.assist

import android.content.Context
import android.graphics.Color
import android.view.View
import io.flutter.embedding.android.FlutterTextureView
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel

/** One Flutter surface in the overlay window; collapsing never launches an Activity. */
internal interface PanelSurface {
    val view: View
    fun visible(value: Boolean)
    fun destroy()
}
internal class FlutterPanel(context: Context, handler: MethodChannel.MethodCallHandler) : PanelSurface {
    private val engine = FlutterEngine(context)
    private val channel = MethodChannel(engine.dartExecutor.binaryMessenger, "ro.chimeraerp.assist/native")
    override val view = FlutterView(context, FlutterTextureView(context).apply { isOpaque = false })
    init {
        channel.setMethodCallHandler(handler)
        engine.navigationChannel.setInitialRoute("/overlay")
        engine.dartExecutor.executeDartEntrypoint(DartExecutor.DartEntrypoint.createDefault())
        view.setBackgroundColor(Color.TRANSPARENT)
        view.attachToFlutterEngine(engine)
    }
    override fun visible(value: Boolean) {
        if (value) engine.lifecycleChannel.appIsResumed() else engine.lifecycleChannel.appIsPaused()
    }
    override fun destroy() {
        channel.setMethodCallHandler(null)
        view.detachFromFlutterEngine()
        engine.lifecycleChannel.appIsDetached()
        engine.destroy()
    }
}
