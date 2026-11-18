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
    fun openInvoice_scanMatch_debounce_wrongPick_suspendAndFinalize() {
        // 1. Open seeded invoice 20450101 (3 items: 114110055 req=4, 511107200 req=10, 310044112 req=6)
        assertTrue(viewModel.startOrOpenInvoice("20450101"))
        waitUntil { viewModel.activeSession.value?.invoiceNo == "20450101" }
        assertEquals(3, viewModel.activeSession.value?.items?.size)

        // 2. Scan matching item via scanner -> increments qty 0 -> 1
        viewModel.processAuditScan("114110055")
        waitUntil { viewModel.activeSession.value?.items?.get("114110055")?.actualQty == 1 }

        // 3. Immediate duplicate burst scan within 380ms is suppressed by ScanDebouncer
        viewModel.processAuditScan("114110055", bypassDebounce = false)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, viewModel.activeSession.value?.items?.get("114110055")?.actualQty)

        // 4. Explicit manual submit (bypassDebounce = true) increments immediately 1 -> 2
        viewModel.processAuditScan("114110055", qtyMultiplier = 3, bypassDebounce = true)
        waitUntil { viewModel.activeSession.value?.items?.get("114110055")?.actualQty == 4 }
        assertEquals(QtyStatus.EXACT, viewModel.activeSession.value?.items?.get("114110055")?.qtyStatus)

        // 5. Scanning item 211088401 (belongs to invoice 20450102) records cross-invoice WrongPick + Error banner
        viewModel.processAuditScan("211088401", bypassDebounce = true)
        waitUntil {
            viewModel.wrongPicks.value.any { it.itemCode == "211088401" && it.actualBelongingInvoiceNo == "20450102" }
        }
        assertTrue(viewModel.scanBanner.value is ScanFeedbackBanner.Error)
        assertEquals(
            CodeStatus.MISMATCH,
            viewModel.activeSession.value?.items?.get("211088401")?.codeStatus
        )

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

        // 8. Finalize session -> creates completed record, audit history, and discrepancies for unscanned/mismatch items
        viewModel.finalizeActiveAuditSession(signatureBase64 = "data:image/png;base64,TEST")
        waitUntil {
            viewModel.activeSession.value == null &&
                viewModel.completedInvoices.value.any { it.invoiceNo == "20450101" } &&
                viewModel.auditHistory.value.any { it.invoiceNo == "20450101" } &&
                viewModel.discrepancies.value.any { it.invoiceNo == "20450101" }
        }

        // 9. Completed invoice is blocked from re-opening directly until supervisor reopen
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
    fun returnsSyncMasterInvoice_selectToggleUpdateQtyPriceAndCondition() {
        viewModel.syncOriginalInvoiceForReturn("20450101")
        waitUntil { viewModel.pdfExtractionState.value is PdfExtractionUiState.Success }

        val success = viewModel.pdfExtractionState.value as PdfExtractionUiState.Success
        assertEquals("20450101", success.result.invoiceNo)
        assertEquals(3, success.result.items.size)
        val firstId = success.result.items.first().id

        viewModel.updateExtractedPdfItemReturnQty(firstId, 2)
        viewModel.updateExtractedPdfItemPrice(firstId, 45.5)
        viewModel.updateExtractedPdfItemCondition(firstId, ReturnItemCondition.TRANSFERRED_TO_LAB)
        viewModel.toggleExtractedPdfItem(firstId)

        val updated = (viewModel.pdfExtractionState.value as PdfExtractionUiState.Success)
            .result.items.first { it.id == firstId }
        assertEquals(2, updated.returnQty)
        assertEquals(45.5, updated.unitPrice, 0.01)
        assertEquals(ReturnItemCondition.TRANSFERRED_TO_LAB, updated.condition)
        assertFalse(updated.isSelected)

        viewModel.selectAllExtractedPdfItems(true)
        assertTrue((viewModel.pdfExtractionState.value as PdfExtractionUiState.Success).result.items.all { it.isSelected })

        viewModel.clearExtractedPdfInvoice()
        assertEquals(PdfExtractionUiState.Idle, viewModel.pdfExtractionState.value)
    }
}
