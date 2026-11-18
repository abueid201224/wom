package com.example.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScannerControllerTest {

    @Test
    fun scannerParser_extractsFromJsonUrlAndPlainText() {
        assertEquals("114110055", ScannerParser.extractCode("""{"itemCode":"114110055","qty":2}"""))
        assertEquals("511107200", ScannerParser.extractCode("https://wms.example.com/scan?code=511107200&loc=A1"))
        assertEquals("20450101", ScannerParser.extractCode("https://wms.example.com/inv/20450101"))
        assertEquals("310044112", ScannerParser.extractCode("  310044112\r\n\t "))
        assertEquals("", ScannerParser.extractCode("   "))
    }

    @Test
    fun scannerParser_stripLeadingZeros() {
        assertEquals("114110055", ScannerParser.stripLeadingZeros("000114110055\n"))
        assertEquals("0", ScannerParser.stripLeadingZeros("000"))
    }

    @Test
    fun scanDebouncer_blocksDuplicateBurstWithinWindowAndAllowsDifferentOrExpired() {
        var clock = 1000L
        val debouncer = ScanDebouncer(debounceWindowMs = 380L, timeProvider = { clock })

        assertFalse(debouncer.shouldAccept("   "))
        assertTrue(debouncer.shouldAccept("114110055"))

        // Duplicate within 380ms rejected (case-insensitive)
        clock = 1200L
        assertFalse(debouncer.shouldAccept("114110055"))

        clock = 1379L
        assertFalse(debouncer.shouldAccept("114110055"))

        // Different code within window accepted immediately
        assertTrue(debouncer.shouldAccept("511107200"))

        // Same code after window expires is accepted again
        clock = 1760L
        assertTrue(debouncer.shouldAccept("511107200"))

        // Reset clears previous lock immediately
        debouncer.reset()
        assertTrue(debouncer.shouldAccept("511107200"))
    }
}
