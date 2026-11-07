package ro.chimeraerp.assist

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager

class ScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return
        try {
            // Enqueue while Telecom still holds its binding. Network/UI work is asynchronous.
            if (details.handlePresentation == TelecomManager.PRESENTATION_ALLOWED && details.handle?.scheme == "tel") {
                details.handle?.schemeSpecificPart?.let { AssistService.receive(this, it) }
            }
        } finally {
            // Never delay or block the call on ERP availability or helper failure.
            respondToCall(details, CallResponse.Builder().setDisallowCall(false).setRejectCall(false)
                .setSilenceCall(false).setSkipCallLog(false).setSkipNotification(false).build())
        }
    }
}
