package ro.chimeraerp.assist

import android.Manifest
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/** Restore the user's existing opt-in after unlock at boot or an app update. */
class HelperRestartReceiver: BroadcastReceiver() {
    internal var signedIn:(Context)->Boolean={SessionStore(it).read()!=null}
    internal var permitted:(Context)->Boolean={
        it.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)==PackageManager.PERMISSION_GRANTED &&
            Settings.canDrawOverlays(it) && it.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }
    internal var start:(Context)->Unit={it.startForegroundService(Intent(it,AssistService::class.java));Unit}
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED))return
        val state=HelperState(context)
        if(!state.enabled||!signedIn(context))return
        if(!permitted(context)){AssistService.restartBlocked(context);return}
        try {start(context)}catch(_:RuntimeException){AssistService.restartBlocked(context)}
    }
}
