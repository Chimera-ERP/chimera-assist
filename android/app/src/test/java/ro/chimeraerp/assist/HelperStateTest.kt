package ro.chimeraerp.assist

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class HelperStateTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun reset() {context.getSharedPreferences("login-preferences",0).edit().clear().commit()}
    @Test fun selfHostedChoiceSurvivesLogoutAndRecreation() {
        HelperState(context).apply {rememberLogin("https://erp.example.test/");enabled=true;clear()}
        val restored=HelperState(context)
        assertFalse(restored.enabled)
        assertTrue(restored.enterpriseLogin)
        assertEquals("https://erp.example.test",restored.enterpriseOrigin)
    }
    @Test fun cloudChoiceKeepsLastDomainWithoutSelectingItOrOverwritingExplicitChoice() {
        val state=HelperState(context)
        state.rememberLogin("https://erp.example.test")
        state.rememberLogin(null)
        state.seedLogin(Session("https://old.example.test","synthetic","Synthetic"))
        assertFalse(HelperState(context).enterpriseLogin)
        assertEquals("https://erp.example.test",HelperState(context).enterpriseOrigin)
    }
    @Test fun rejectedAddressCannotReplaceKnownGoodPreference() {
        val state=HelperState(context)
        state.rememberLogin("https://erp.example.test")
        for (invalid in listOf("http://erp.example.test","https://user:password@erp.example.test","https://erp.example.test/?token=synthetic")) {
            try{state.rememberLogin(invalid);fail("Must reject unsafe origin")}catch(_:IllegalArgumentException){}
            assertEquals("https://erp.example.test",state.enterpriseOrigin)
        }
    }
    @Test fun upgradesSeedExistingEnterpriseSessionButCloudStaysDefault() {
        val state=HelperState(context)
        state.seedLogin(Session("https://app.chimeraerp.ro","synthetic","Synthetic"))
        assertFalse(state.enterpriseLogin);assertNull(state.enterpriseOrigin)
        state.seedLogin(Session("https://erp.example.test","synthetic","Synthetic"))
        assertTrue(state.enterpriseLogin);assertEquals("https://erp.example.test",state.enterpriseOrigin)
    }
}
