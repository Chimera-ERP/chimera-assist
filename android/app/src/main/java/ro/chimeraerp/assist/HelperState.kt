package ro.chimeraerp.assist

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/** Only operational state is persisted: never caller numbers, names, tokens or API responses. */
class HelperState(private val context: Context) {
    private val prefs = context.getSharedPreferences("helper", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var regionOverride: String?
        get() = prefs.getString("region", null)
        set(value) { require(value == null || value in PhoneNumbers.regions); prefs.edit().putString("region", value).apply() }
    fun region(): String? {
        regionOverride?.let { return it }
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val network = try { telephony?.networkCountryIso } catch (_: SecurityException) { null }
        val sim = try { telephony?.simCountryIso } catch (_: SecurityException) { null }
        return listOf(network, sim, Locale.getDefault().country).mapNotNull { it?.uppercase(Locale.ROOT) }
            .firstOrNull { it in PhoneNumbers.regions }
    }
    fun report(code: String) {
        val edit = prefs.edit().putString("status", code).putLong("status_time", System.currentTimeMillis())
        if (code == "received") edit.putLong("last_call_time", System.currentTimeMillis())
        edit.apply()
    }
    fun lastCallTime(): Long = prefs.getLong("last_call_time", 0)
    fun status(): String = prefs.getString("status", "none") ?: "none"
    fun statusTime(): Long = prefs.getLong("status_time", 0)
    fun clear() { prefs.edit().clear().apply() }
}
