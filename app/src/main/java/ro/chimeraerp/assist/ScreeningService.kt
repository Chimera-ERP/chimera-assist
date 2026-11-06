package ro.chimeraerp.assist

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager

class ScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return
        // Telecom must hear back within five seconds. Never wait for ERP or UI.
        respondToCall(details, CallResponse.Builder().setDisallowCall(false).setRejectCall(false)
            .setSilenceCall(false).setSkipCallLog(false).setSkipNotification(false).build())
        if (details.handlePresentation != TelecomManager.PRESENTATION_ALLOWED || details.handle?.scheme != "tel") return
        val phone = details.handle?.schemeSpecificPart ?: return
        AssistService.identify(phone)
    }
}
