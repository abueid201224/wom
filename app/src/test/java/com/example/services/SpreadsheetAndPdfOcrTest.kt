package com.example.services

import com.example.data.local.SeedData
import com.example.model.ReturnItemCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpreadsheetAndPdfOcrTest {

    @Test
    fun parseInventoryBookFile_supportsArabicHeadersArabicDigitsAndPackagingRules() {
        val csv = """
            الباركود,اسم الصنف,الوحدة,الرصيد الدفتري,كرتون,بوكس,حبة
            114110055,طقم سكاكين ستيل,HDZ,٢٤,١,٠,٠
            310044112,عبوة حفظ بلاستيك,PCS,30,1,1,0
        """.trimIndent()

        val parsed = SpreadsheetService.parseInventoryBookFile(
            inputStream = ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)),
            fileName = "inventory_book.csv",
            packagingRules = SeedData.defaultPackagingRules
        )

        assertEquals(2, parsed.size)
        val first = parsed[0]
        assertEquals("114110055", first.itemCode)
        assertEquals(24, first.bookQty)
        assertEquals("rule-1141", first.groupId)
        assertEquals(1, first.cartonsCount)
        assertTrue(first.calculatedActualQty > 0)

        val second = parsed[1]
        assertEquals("310044112", second.itemCode)
        assertEquals(30, second.bookQty)
        assertEquals("rule-3100", second.groupId)
        // rule-3100 cartonFactor=24, packFactor=6 -> 1CTN + 1BOX = 30
        assertEquals(30, second.calculatedActualQty)
        assertEquals(0, second.varianceQty)
        assertEquals("EXACT", second.status)
    }

    @Test
    fun parseStructuredInvoiceJson_handlesArabicRtlTextAndCalculatesMissingUnitPrice() {
        val json = """
            {
              "invoiceNo": "٢٠٤٥٠١٠١",
              "orderNo": "ORD-88",
              "customerName": "مؤسسة الأفق التجارية",
              "currency": "SAR",
              "items": [
                {
                  "itemCode": "١١٤١١٠٠٥٥",
                  "itemName": "طقم سكاكين ستيل فاخر 6 قطع",
                  "unit": "HDZ",
                  "invoicedQty": 4,
                  "unitPrice": 120.5,
                  "lineTotal": 0.0
                },
                {
                  "itemCode": "511107200",
                  "itemName": "قدر ضغط ألمنيوم 9 لتر",
                  "unit": "PCS",
                  "invoicedQty": 2,
                  "unitPrice": 0.0,
                  "lineTotal": 350.0
                }
              ]
            }
        """.trimIndent()

        val result = PdfInvoiceOcrService.parseStructuredInvoiceJson(json, "invoice_20450101.pdf")
        assertEquals("20450101", result.invoiceNo)
        assertEquals("ORD-88", result.orderNo)
        assertEquals("مؤسسة الأفق التجارية", result.customerName)
        assertEquals("SAR", result.currency)
        assertEquals("GEMINI_RTL_OCR", result.extractionEngine)
        assertEquals(2, result.items.size)

        val item1 = result.items[0]
        assertEquals("114110055", item1.itemCode)
        assertEquals(4, item1.invoicedQty)
        assertEquals(120.5, item1.unitPrice, 0.01)
        assertEquals(482.0, item1.lineTotal, 0.01)
        assertTrue(item1.isSelected)
        assertEquals(ReturnItemCondition.VALID_FOR_RESTOCK, item1.condition)

        val item2 = result.items[1]
        assertEquals("511107200", item2.itemCode)
        assertEquals(175.0, item2.unitPrice, 0.01)
        assertEquals(350.0, item2.lineTotal, 0.01)
    }

    @Test
    fun buildFromMasterInvoice_andFallbackRawBytes_extractsInvoiceItems() {
        val master = SeedData.defaultDemoMasterItems
        val synced = PdfInvoiceOcrService.buildFromMasterInvoice("20450101", master)
        assertEquals("20450101", synced.invoiceNo)
        assertEquals(3, synced.items.size)
        assertEquals("MASTER_SYNC", synced.extractionEngine)

        val pdfPayload = "%PDF-1.4\nInvoice No: ٢٠٢٤-20450102\nItem: 211088401".toByteArray(Charsets.UTF_8)
        val fallback = PdfInvoiceOcrService.extractFallbackFromRawBytes(pdfPayload, "scan.pdf", master)
        assertNotNull(fallback)
        assertEquals("20450102", fallback?.invoiceNo)
        assertTrue((fallback?.items?.size ?: 0) > 0)
    }
}
