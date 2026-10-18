package dev.dquest.aichanger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingInputTest {

    @Test
    fun codeOnly() {
        assertEquals(null to "123456", parsePairingInput("123456"))
    }

    @Test
    fun portAndCode() {
        assertEquals(37123 to "123456", parsePairingInput("37123 123456"))
    }

    @Test
    fun pastedAddressLineUsesNumberBeforeCode() {
        assertEquals(37123 to "123456", parsePairingInput("192.168.1.5:37123 123456"))
    }

    @Test
    fun portOutOfRangeIsIgnored() {
        assertEquals(null to "123456", parsePairingInput("99999 123456"))
    }

    @Test
    fun codeMustHaveSixDigits() {
        assertNull(parsePairingInput(""))
        assertNull(parsePairingInput("код"))
        assertNull(parsePairingInput("37123 1234"))
    }
}
