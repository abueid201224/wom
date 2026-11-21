package com.example.ui.viewmodel

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.WmsApplication
import com.example.model.CodeStatus
import com.example.model.QtyStatus
import com.example.model.ReturnItemCondition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WmsViewModelRobolectricTest {

    private lateinit var app: WmsApplication
    private lateinit var viewModel: WmsViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
        runBlocking {
            app.database.ensureSeeded()
        }
        viewModel = WmsViewModel(app)
        waitUntil { viewModel.masterItems.value.isNotEmpty() && viewModel.packagingRules.value.isNotEmpty() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun waitUntil(timeoutMs: Long = 5000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate() && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Condition did not become true within ${timeoutMs}ms", predicate())
    }

    @Test
    fun openInvoice_strictLockRejectsOutsideBarcode_tracksProgressTo100Percent_suspendAndFinalize() {
        // 1. Open seeded invoice 20450101 (3 items: 114110055 req=4, 511107200 req=10, 310044112 req=6 -> total 20)
        assertTrue(viewModel.startOrOpenInvoice("20450101"))
        waitUntil { viewModel.activeSession.value?.invoiceNo == "20450101" }
        assertEquals(3, viewModel.activeSession.value?.items?.size)
        assertEquals(0, viewModel.activeSession.value?.progressPercent)

        // 2. Scan matching item via scanner -> increments qty 0 -> 1
        viewModel.processAuditScan("114110055")
        waitUntil { viewModel.activeSession.value?.items?.get("114110055")?.actualQty == 1 }
        assertEquals(5, viewModel.activeSession.value?.progressPercent)

        // 3. Immediate duplicate burst scan within 380ms is suppressed by ScanDebouncer
        viewModel.processAuditScan("114110055", bypassDebounce = false)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, viewModel.activeSession.value?.items?.get("114110055")?.actualQty)

        // 4. Explicit manual submit (bypassDebounce = true) increments 1 -> 4 (EXACT)
        viewModel.processAuditScan("114110055", qtyMultiplier = 3, bypassDebounce = true)
        waitUntil { viewModel.activeSession.value?.items?.get("114110055")?.actualQty == 4 }
        assertEquals(QtyStatus.EXACT, viewModel.activeSession.value?.items?.get("114110055")?.qtyStatus)
        assertEquals(20, viewModel.activeSession.value?.progressPercent)

        // 5. Strict lock: scanning outside barcode 211088401 (belongs to 20450102) MUST NOT record or add to session items
        viewModel.processAuditScan("211088401", bypassDebounce = true)
        waitUntil { viewModel.scanBanner.value is ScanFeedbackBanner.Error }
        assertNull(viewModel.activeSession.value?.items?.get("211088401"))
        assertEquals(3, viewModel.activeSession.value?.items?.size)
        assertFalse(viewModel.wrongPicks.value.any { it.itemCode == "211088401" })

        // Scanning unknown outside barcode also rejected without recording
        viewModel.processAuditScan("999888777", bypassDebounce = true)
        waitUntil {
            (viewModel.scanBanner.value as? ScanFeedbackBanner.Error)?.messageEn?.contains("999888777") == true
        }
        assertNull(viewModel.activeSession.value?.items?.get("999888777"))
        assertEquals(3, viewModel.activeSession.value?.items?.size)

        // 6. Suspend session -> stored in incompleteInvoices and activeSession cleared
        viewModel.suspendCurrentSessionToIncomplete()
        waitUntil {
            viewModel.activeSession.value == null &&
                viewModel.incompleteInvoices.value.any { it.invoiceNo == "20450101" }
        }

        // 7. Reopen from suspended -> restores previous qty=4 on 114110055
        assertTrue(viewModel.startOrOpenInvoice("20450101"))
        waitUntil { viewModel.activeSession.value?.invoiceNo == "20450101" }
        assertEquals(4, viewModel.activeSession.value?.items?.get("114110055")?.actualQty)

        // 8. Scan remaining items to reach 100% completion (511107200 x 10, 310044112 x 6)
        viewModel.processAuditScan("511107200", qtyMultiplier = 10, bypassDebounce = true)
        viewModel.processAuditScan("310044112", qtyMultiplier = 6, bypassDebounce = true)
        waitUntil {
            viewModel.activeSession.value?.isFullyCompleted == true &&
                viewModel.auditScanningKpis.value.activeInvoiceProgressPercent == 100
        }
        assertEquals(100, viewModel.activeSession.value?.progressPercent)
        assertEquals(20, viewModel.auditScanningKpis.value.activeInvoiceScannedQty)
        assertEquals(0, viewModel.auditScanningKpis.value.activeInvoiceRemainingQty)
        assertTrue(viewModel.auditScanningKpis.value.totalInvoices >= 1)
        assertTrue(viewModel.auditScanningKpis.value.totalScannedQty >= 20)
        assertTrue((viewModel.scanBanner.value as? ScanFeedbackBanner.Success)?.messageEn?.contains("100%") == true)

        // 9. Close invoice without per-invoice signature -> creates completed record and audit history with 0 discrepancies
        viewModel.closeActiveInvoice()
        waitUntil {
            viewModel.activeSession.value == null &&
                viewModel.completedInvoices.value.any { it.invoiceNo == "20450101" } &&
                viewModel.auditHistory.value.any { it.invoiceNo == "20450101" && it.status == "EXACT" }
        }
        assertNull(viewModel.completedInvoices.value.first { it.invoiceNo == "20450101" }.auditorSignature)
        assertFalse(viewModel.showSessionApprovalModal.value)

        // 10. Completed invoice is blocked from re-opening directly until supervisor reopen
        assertFalse(viewModel.startOrOpenInvoice("20450101"))
        assertTrue(viewModel.scanBanner.value is ScanFeedbackBanner.Warning)

        viewModel.reopenCompletedInvoice("20450101")
        waitUntil { viewModel.activeSession.value?.invoiceNo == "20450101" }
        assertNotNull(viewModel.activeSession.value)
        assertNull(viewModel.completedInvoices.value.find { it.invoiceNo == "20450101" })
    }

    @Test
    fun inventoryBookSync_scanAndAdjustCounts_computesLiveVariance() {
        viewModel.syncInventoryBookFromMaster()
        waitUntil { viewModel.inventoryDraftItems.value.isNotEmpty() }

        val target = viewModel.inventoryDraftItems.value.first { it.itemCode == "114110055" }
        assertEquals(6, target.bookQty) // 4 in inv 20450101 + 2 in inv 20450103
        assertEquals(-6, target.varianceQty)
        assertEquals("SHORTAGE", target.status)

        // Scan barcode directly into draft -> increments piecesCount by 1
        assertTrue(viewModel.scanBarcodeIntoInventoryDraft("114110055"))
        waitUntil { viewModel.inventoryDraftItems.value.first { it.id == target.id }.piecesCount == 1 }

        // Adjust cartons/pieces to hit exact book quantity
        viewModel.adjustInventoryDraftCounts(target.id, deltaCartons = 0, deltaPacks = 0, deltaPieces = 5)
        waitUntil { viewModel.inventoryDraftItems.value.first { it.id == target.id }.varianceQty == 0 }
        assertEquals("EXACT", viewModel.inventoryDraftItems.value.first { it.id == target.id }.status)
    }

    @Test
    fun returnsSyncMasterInvoice_preliminarySelection_finalizesToFinalReturnReport() {
        viewModel.syncOriginalInvoiceForReturn("20450101")
        waitUntil { viewModel.pdfExtractionState.value is PdfExtractionUiState.Success }

        val success = viewModel.pdfExtractionState.value as PdfExtractionUiState.Success
        assertEquals("20450101", success.result.invoiceNo)
        assertEquals(3, success.result.items.size)
        val firstId = success.result.items[0].id
        val secondId = success.result.items[1].id

        viewModel.updateExtractedPdfHeader(customerName = "مؤسسة الاختبار العربية")
        viewModel.updateExtractedPdfItemReturnQty(firstId, 2)
        viewModel.updateExtractedPdfItemPrice(firstId, 45.5)
        viewModel.updateExtractedPdfItemCondition(firstId, ReturnItemCondition.TRANSFERRED_TO_LAB)
        // Deselect second item so only selected items transfer to final return report
        viewModel.toggleExtractedPdfItem(secondId)

        val updated = (viewModel.pdfExtractionState.value as PdfExtractionUiState.Success)
            .result.items.first { it.id == firstId }
        assertEquals(2, updated.returnQty)
        assertEquals(45.5, updated.unitPrice, 0.01)
        assertEquals(ReturnItemCondition.TRANSFERRED_TO_LAB, updated.condition)

        val finalized = viewModel.finalizePreliminaryReturnReport(signatureBase64 = "data:image/png;base64,SIG")
        assertNotNull(finalized)
        waitUntil {
            viewModel.pdfExtractionState.value is PdfExtractionUiState.Idle &&
                viewModel.returnReports.value.any { it.id == finalized?.id }
        }
        val saved = viewModel.returnReports.value.first { it.id == finalized?.id }
        assertEquals("20450101", saved.originalInvoiceNo)
        assertEquals("مؤسسة الاختبار العربية", saved.customerName)
        assertEquals(2, saved.items.size) // 3 minus the 1 deselected item
        assertEquals(91.0, saved.totalRefundAmount, 0.01)
    }

    @Test
    fun closeInvoicesViaButtonOrNewInvoiceScan_whenAllFinished_opensAndApprovesFullSessionReport() {
        // 1. Audit invoice 20450101 completely (20 pcs) and close via Close Invoice button
        assertTrue(viewModel.startOrOpenInvoice("20450101"))
        waitUntil { viewModel.activeSession.value?.invoiceNo == "20450101" }
        viewModel.processAuditScan("114110055", qtyMultiplier = 4, bypassDebounce = true)
        viewModel.processAuditScan("511107200", qtyMultiplier = 10, bypassDebounce = true)
        viewModel.processAuditScan("310044112", qtyMultiplier = 6, bypassDebounce = true)
        waitUntil { viewModel.activeSession.value?.isFullyCompleted == true }

        viewModel.closeActiveInvoice()
        waitUntil {
            viewModel.activeSession.value == null &&
                viewModel.completedInvoices.value.any { it.invoiceNo == "20450101" }
        }
        assertFalse(viewModel.showSessionApprovalModal.value)

        // 2. Open invoice 20450102, scan partial (10 of 211088401 out of 12, 0 of 512001122 out of 20)
        assertTrue(viewModel.startOrOpenInvoice("20450102"))
        waitUntil { viewModel.activeSession.value?.invoiceNo == "20450102" }
        viewModel.processAuditScan("211088401", qtyMultiplier = 10, bypassDebounce = true)
        waitUntil { viewModel.activeSession.value?.items?.get("211088401")?.actualQty == 10 }

        // Scan new invoice 20450103 directly -> auto-closes 20450102 without signature & opens 20450103
        viewModel.processAuditScan("20450103", bypassDebounce = true)
        waitUntil {
            viewModel.activeSession.value?.invoiceNo == "20450103" &&
                viewModel.completedInvoices.value.any { it.invoiceNo == "20450102" } &&
                viewModel.discrepancies.value.count { it.invoiceNo == "20450102" } == 2
        }
        assertNull(viewModel.completedInvoices.value.first { it.invoiceNo == "20450102" }.auditorSignature)

        // 3. Complete remaining invoice 20450103 (8 pcs) and press Close Invoice
        viewModel.processAuditScan("810033445", qtyMultiplier = 6, bypassDebounce = true)
        viewModel.processAuditScan("114110055", qtyMultiplier = 2, bypassDebounce = true)
        waitUntil { viewModel.activeSession.value?.isFullyCompleted == true }

        viewModel.closeActiveInvoice()
        waitUntil {
            viewModel.activeSession.value == null &&
                viewModel.showSessionApprovalModal.value &&
                viewModel.sessionApprovalSummary.value.isAllMasterInvoicesFinished
        }

        val summary = viewModel.sessionApprovalSummary.value
        assertEquals(3, summary.totalMasterInvoices)
        assertEquals(3, summary.auditedInvoicesCount)
        assertEquals(0, summary.remainingInvoicesCount)
        assertEquals(2, summary.exactInvoicesCount)
        assertEquals(1, summary.discrepancyInvoicesCount)
        assertEquals(60, summary.totalRequiredQty)
        assertEquals(38, summary.totalScannedQty)
        assertEquals(2, summary.totalDiscrepanciesCount)
        assertEquals(22, summary.totalShortageQty)
        assertEquals(0, summary.totalSurplusQty)
        assertEquals(3, summary.invoiceDetails.size)
        assertEquals(2, summary.discrepancyDetails.size)
        assertFalse(summary.isSessionApproved)

        // 4. Approve and sign the entire session report once
        val approved = viewModel.approveFullSessionReport(signatureBase64 = "data:image/png;base64,FULLSIG")
        waitUntil {
            !viewModel.showSessionApprovalModal.value &&
                viewModel.dailySnapshots.value.any { it.id == approved.id } &&
                viewModel.sessionApprovalSummary.value.isSessionApproved
        }

        val savedSnapshot = viewModel.dailySnapshots.value.first { it.id == approved.id }
        assertEquals("data:image/png;base64,FULLSIG", savedSnapshot.auditorSignature)
        assertEquals(3, savedSnapshot.completedInvoices)
        assertEquals(38, savedSnapshot.totalScannedQty)
        assertEquals(2, savedSnapshot.totalDiscrepancies)
        assertEquals(2, savedSnapshot.exactInvoicesCount)
        assertEquals(1, savedSnapshot.discrepancyInvoicesCount)
        assertEquals(3, savedSnapshot.invoiceDetails.size)
    }
}
