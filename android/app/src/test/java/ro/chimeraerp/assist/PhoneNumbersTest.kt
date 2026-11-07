package ro.chimeraerp.assist

import org.junit.Assert.*
import org.junit.Test

class PhoneNumbersTest {
    @Test fun nationalAndInternationalFormsShareOneCanonicalNumber() {
        listOf("0700 000 001", "700000001", "+40 (700) 000-001", "0040700000001").forEach {
            assertEquals("+40700000001", PhoneNumbers.canonical(it, "RO"))
        }
        assertEquals("+442079460123", PhoneNumbers.canonical("020 7946 0123", "GB"))
        assertEquals("+493012345678", PhoneNumbers.canonical("030 12345678", "DE"))
        assertEquals("+16502530000", PhoneNumbers.canonical("(650) 253-0000", "US"))
        assertEquals("+390669820000", PhoneNumbers.canonical("06 6982 0000", "IT"))
        assertEquals("+34911234567", PhoneNumbers.canonical("911 23 45 67", "ES"))
    }
    @Test fun explicitInternationalPrefixAlwaysWins() {
        assertEquals("+442079460123", PhoneNumbers.canonical("+44 20 7946 0123", "RO"))
        assertEquals("+493012345678", PhoneNumbers.canonical("0049 (30) 12345678", "US"))
        assertEquals("+442079460123", PhoneNumbers.canonical("+442079460123", null))
    }
    @Test fun neverGuessesAnUnknownCountryOrMatchesSuffixes() {
        listOf("123", "anonymous", "0700000001 ext 2", "0700000001;0700000002", "++40700000001").forEach {
            assertThrows(Exception::class.java) { PhoneNumbers.canonical(it, "RO") }
        }
        assertThrows(Exception::class.java) { PhoneNumbers.canonical("02079460123", null) }
        assertThrows(Exception::class.java) { PhoneNumbers.canonical("02079460123", "XX") }
        assertNotEquals(PhoneNumbers.canonical("02079460123", "GB"), PhoneNumbers.canonical("02079460123", "DE"))
    }
}
