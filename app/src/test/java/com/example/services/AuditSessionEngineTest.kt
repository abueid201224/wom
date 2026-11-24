package com.example.services

import com.example.model.*
import org.junit.Assert.*
import org.junit.Test

class AuditSessionEngineTest {

    private fun sampleMaster(): List<MasterInvoiceItem> = listOf(
        MasterInvoiceItem(1L, "ORD-1", "INV-1001", "114110055", "Item A", "PCS", 10, "2026-01-01T00:00:00Z", 0),
        MasterInvoiceItem(2L, "ORD-1", "INV-1001", "511107200", "Item B", "PCS", 10, "2026-01-01T00:00:00Z", 1),
        MasterInvoiceItem(3L, "ORD-2", "INV-1002", "211088401", "Item C", "PCS", 5, "2026-01-01T00:00:00Z", 2)
    )

    @Test
    fun noCrossItemCompensation_shortageAndSurplusAreReportedIndependently() {
        val master = sampleMaster()
        var session = AuditSessionEngine.buildNewSession(
            invoiceRows = master.filter { it.invoiceNo == "INV-1001" },
            auditorName = "Auditor",
            auditorId = "AUD-1"
        )
        assertTrue(session.sessionId.startsWith("ses-"))
        assertTrue(session.lastCheckpointAtMs > 0L)

        // Scan Item A 7 out of 10 (-3 shortage) and Item B 13 out of 10 (+3 surplus)
        // Net qty scanned is 20 == 20, yet there MUST be 2 discrepancies and ZERO cross-item compensation!
        session = (AuditSessionEngine.applyItemScan(session, "114110055", 7, master)
            as AuditSessionEngine.ItemScanOutcome.Updated).updatedSession
        session = (AuditSessionEngine.applyItemScan(session, "511107200", 13, master)
            as AuditSessionEngine.ItemScanOutcome.Updated).updatedSession

        val (discs, completed, history) = AuditSessionEngine.buildClosedInvoiceArtifacts(session, "Auditor", "AUD-1")
        assertEquals(20, completed.totalQty)
        assertEquals(2, discs.size)
        assertEquals("DISCREPANCY", history.status)
        assertEquals(-3, discs.first { it.itemCode == "114110055" }.difference)
        assertEquals(3, discs.first { it.itemCode == "511107200" }.difference)

        val summary = AuditSessionEngine.buildSessionApprovalSummary(
            master = master.filter { it.invoiceNo == "INV-1001" },
            completed = listOf(completed),
            history = listOf(history),
            rawDiscrepancies = discs,
            snapshots = emptyList(),
            activeSession = null
        )
        assertEquals(3, summary.totalShortageQty)
        assertEquals(3, summary.totalSurplusQty)
        assertEquals(70, summary.matchAccuracyPercent) // (20 - (3 + 3)) / 20 = 70%, NOT 100%!
        assertEquals(1, summary.discrepancyInvoicesCount)
        assertEquals(0, summary.exactInvoicesCount)
    }

    @Test
    fun strictLockRejectsOutsideBarcode_andDeduplicatesDiscrepancies() {
        val master = sampleMaster()
        val session = AuditSessionEngine.buildNewSession(
            invoiceRows = master.filter { it.invoiceNo == "INV-1001" },
            auditorName = "Auditor",
            auditorId = "AUD-1"
        )

        val outcome = AuditSessionEngine.applyItemScan(session, "211088401", 1, master)
        assertTrue(outcome is AuditSessionEngine.ItemScanOutcome.RejectedOutsideInvoice)
        val rejected = outcome as AuditSessionEngine.ItemScanOutcome.RejectedOutsideInvoice
        assertEquals("INV-1001", rejected.activeInvoiceNo)
        assertEquals("INV-1002", rejected.belongingInvoiceNo)

        val dupes = listOf(
            AuditDiscrepancy(1L, null, "INV-1001", "00114110055", "Item A", "PCS", 10, 7, CodeStatus.MATCH, QtyStatus.SHORTAGE, -3, "t1"),
            AuditDiscrepancy(2L, null, "inv-1001", "114110055", "Item A", "PCS", 10, 7, CodeStatus.MATCH, QtyStatus.SHORTAGE, -3, "t2")
        )
        assertEquals(1, AuditSessionEngine.deduplicateDiscrepancies(dupes).size)
    }

    @Test
    fun doubleScanSwitchGuard_requiresSecondScanWithin2SecondsWhenEnabled() {
        val master = sampleMaster()
        val session = AuditSessionEngine.buildNewSession(
            invoiceRows = master.filter { it.invoiceNo == "INV-1001" },
            auditorName = "Auditor",
            auditorId = "AUD-1"
        )

        // 1. Standard mode (requireDoubleScan = false) proceeds immediately
        assertEquals(
            AuditSessionEngine.SwitchSafetyDecision.ProceedImmediately,
            AuditSessionEngine.evaluateInvoiceSwitchGuard(session, "INV-1002", false, null, 1000L)
        )

        // 2. Guard enabled -> first scan requests second scan
        val first = AuditSessionEngine.evaluateInvoiceSwitchGuard(session, "INV-1002", true, null, 1000L)
        assertTrue(first is AuditSessionEngine.SwitchSafetyDecision.RequireSecondScan)
        val prompt = (first as AuditSessionEngine.SwitchSafetyDecision.RequireSecondScan).prompt

        // 3. Second scan of different invoice resets prompt
        val wrongSecond = AuditSessionEngine.evaluateInvoiceSwitchGuard(session, "INV-1003", true, prompt, 1500L)
        assertTrue(wrongSecond is AuditSessionEngine.SwitchSafetyDecision.RequireSecondScan)

        // 4. Second scan after 2000ms window expires requests confirmation again
        val expired = AuditSessionEngine.evaluateInvoiceSwitchGuard(session, "INV-1002", true, prompt, 3500L)
        assertTrue(expired is AuditSessionEngine.SwitchSafetyDecision.RequireSecondScan)

        // 5. Second scan within 2000ms proceeds
        val confirmed = AuditSessionEngine.evaluateInvoiceSwitchGuard(session, "INV-1002", true, prompt, 2500L)
        assertEquals(AuditSessionEngine.SwitchSafetyDecision.ProceedImmediately, confirmed)
    }

    @Test
    fun deferralRequiresSupervisorAfterFifthAttempt_andBlocksFullSessionCompletionWhileDeferred() {
        val master = sampleMaster()
        var session = AuditSessionEngine.buildNewSession(
            invoiceRows = master.filter { it.invoiceNo == "INV-1001" },
            auditorName = "Trial Auditor",
            auditorId = "AUD-TRIAL"
        )

        // Attempts 1..5 (currentDeferCount 0..4) are free for non-supervisor
        for (c in 0 until 5) {
            assertFalse(AuditSessionEngine.requiresSupervisorForDefer(c, UserRole.AUDITOR, requireSupervisorOverride = true))
        }
        // Attempt 6+ (currentDeferCount >= 5) requires Supervisor PIN when gate is active
        assertTrue(AuditSessionEngine.requiresSupervisorForDefer(5, UserRole.AUDITOR, requireSupervisorOverride = true))
        assertTrue(AuditSessionEngine.requiresSupervisorForDefer(6, UserRole.WAREHOUSE_KEEPER, requireSupervisorOverride = true))
        // Supervisor or disabled gate (trial bypass) never requires extra PIN prompt
        assertFalse(AuditSessionEngine.requiresSupervisorForDefer(5, UserRole.SUPERVISOR, requireSupervisorOverride = true))
        assertFalse(AuditSessionEngine.requiresSupervisorForDefer(5, UserRole.AUDITOR, requireSupervisorOverride = false))

        // Build deferred record with shortages snapshot
        session = (AuditSessionEngine.applyItemScan(session, "114110055", 6, master)
            as AuditSessionEngine.ItemScanOutcome.Updated).updatedSession
        val deferred = AuditSessionEngine.buildDeferredInvoiceRecord(
            session = session,
            auditorName = "Trial Auditor",
            auditorId = "AUD-TRIAL",
            recordShortagesReport = true
        )
        assertEquals(1, deferred.deferCount)
        assertTrue(deferred.recordShortagesReport)
        assertEquals(14, deferred.missingQty) // (10-6) + 10 = 14
        assertEquals(2, deferred.shortageItemsSnapshot.size)

        // Even if INV-1002 was closed, session is NOT finished while INV-1001 remains deferred
        val s2 = AuditSessionEngine.buildNewSession(master.filter { it.invoiceNo == "INV-1002" }, "Trial Auditor", "AUD-TRIAL")
        val (_, comp2, hist2) = AuditSessionEngine.buildClosedInvoiceArtifacts(s2, "Trial Auditor", "AUD-TRIAL")
        val summary = AuditSessionEngine.buildSessionApprovalSummary(
            master = master,
            completed = listOf(comp2),
            history = listOf(hist2),
            rawDiscrepancies = emptyList(),
            snapshots = emptyList(),
            activeSession = null,
            incomplete = listOf(deferred)
        )
        assertFalse(summary.isAllMasterInvoicesFinished)
        assertEquals(1, summary.deferredInvoicesCount)
        assertEquals(1, summary.incompleteShortageInvoicesCount)
    }
}
