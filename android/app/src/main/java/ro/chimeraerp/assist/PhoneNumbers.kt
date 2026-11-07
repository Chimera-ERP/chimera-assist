package ro.chimeraerp.assist

import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

/** Transport normalization only. ERP remains the authority for scoped partner matching. */
object PhoneNumbers {
    private val util = PhoneNumberUtil.getInstance()
    val regions: Set<String> get() = util.supportedRegions

    fun canonical(input: String, region: String?): String {
        require(input.length <= 64 && input.matches(Regex("[+0-9 ().\\-]+")))
        val compact = input.replace(Regex("[ ().\\-]"), "")
        val number = if (compact.startsWith("00")) "+" + compact.drop(2) else compact
        require(number.matches(Regex("\\+?[0-9]+")))
        val country = region?.uppercase(Locale.ROOT)?.takeIf { it in regions }
        require(number.startsWith("+") || country != null) { "Choose the country for a national number" }
        val parsed = try { util.parse(number, country) } catch (_: Exception) { throw IllegalArgumentException("Invalid phone number") }
        require(!parsed.hasExtension() && util.isPossibleNumber(parsed)) { "Use a complete phone number" }
        return util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164).also {
            require(it.matches(Regex("\\+[1-9][0-9]{7,14}")))
        }
    }
}
