package com.example.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFeedbackManagerTest {

    @Test
    fun allAudioTonesHaveDistinctAcousticAndHapticSpecs() {
        val specs = WmsAudioTone.entries.associateWith { AudioFeedbackManager.specFor(it) }

        // Every tone must have at least 1 acoustic note and a positive vibration waveform
        for ((tone, spec) in specs) {
            assertTrue("Notes missing for $tone", spec.notes.isNotEmpty())
            assertTrue("Notes have invalid frequency/duration for $tone", spec.notes.all { it.frequencyHz > 100.0 && it.durationMs > 0 })
            assertTrue("Vibration pattern missing for $tone", spec.vibrationPattern.isNotEmpty())
            assertTrue("Vibration pattern has no active pulse for $tone", spec.vibrationPattern.any { it > 0L })
        }

        // Core operational cues must be acoustically distinct
        val matchSpec = specs.getValue(WmsAudioTone.MATCH)
        val exactSpec = specs.getValue(WmsAudioTone.EXACT_COMPLETE)
        val mismatchSpec = specs.getValue(WmsAudioTone.MISMATCH_ALERT)
        val surplusSpec = specs.getValue(WmsAudioTone.SURPLUS_WARNING)
        val invoiceFinishedSpec = specs.getValue(WmsAudioTone.INVOICE_FINISHED)
        val fanfareSpec = specs.getValue(WmsAudioTone.SESSION_COMPLETED_FANFARE)

        assertEquals(1, matchSpec.notes.size)
        assertEquals(2, exactSpec.notes.size)
        assertEquals(3, invoiceFinishedSpec.notes.size)
        assertEquals(4, fanfareSpec.notes.size)

        // Exact & Invoice finished & Session fanfare ascend in pitch
        assertTrue(exactSpec.notes.last().frequencyHz > exactSpec.notes.first().frequencyHz)
        assertTrue(invoiceFinishedSpec.notes.last().frequencyHz > invoiceFinishedSpec.notes.first().frequencyHz)
        assertTrue(fanfareSpec.notes.last().frequencyHz > fanfareSpec.notes.first().frequencyHz)

        // Error/Mismatch alert is harsh & low-pitched, descending, distinct from MATCH
        assertTrue(mismatchSpec.notes.last().frequencyHz < mismatchSpec.notes.first().frequencyHz)
        assertTrue(mismatchSpec.notes.first().frequencyHz < matchSpec.notes.first().frequencyHz)
        assertNotEquals(matchSpec.notes, mismatchSpec.notes)
        assertNotEquals(surplusSpec.notes, exactSpec.notes)
    }
}
