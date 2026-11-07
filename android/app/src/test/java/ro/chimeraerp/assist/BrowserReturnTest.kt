package ro.chimeraerp.assist

import android.content.Intent
import android.net.Uri
import net.openid.appauth.AuthorizationManagementActivity
import net.openid.appauth.RedirectUriReceiverActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserReturnTest {
    private fun receive(query: String) {
        val uri = Uri.parse(BrowserPolicy.REDIRECT + query)
        val controller = Robolectric.buildActivity(
            RedirectUriReceiverActivity::class.java,
            Intent(Intent.ACTION_VIEW, uri)
        )
        val activity = controller.create().get()
        try {
            val forwarded = shadowOf(activity).nextStartedActivity
            assertEquals(AuthorizationManagementActivity::class.java.name, forwarded.component?.className)
            assertEquals(uri, forwarded.data)
            assertTrue(activity.isFinishing)
        } finally {
            controller.destroy()
        }
    }

    @Test fun successfulBrowserRedirectReachesAppAuthWithoutCrashing() {
        receive("?code=synthetic-one-use-code&state=synthetic-state")
    }

    @Test fun rejectedBrowserRedirectReachesAppAuthWithoutCrashing() {
        receive("?error=access_denied&state=synthetic-state")
    }
}
