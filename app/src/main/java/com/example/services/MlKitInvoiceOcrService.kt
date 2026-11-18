package com.example.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.model.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.util.UUID
import kotlin.math.roundToInt

/**
 * On-Device Integration Service using Google ML Kit Text Recognition to extract
 * text, line items, quantities, and unit prices from PDF invoice documents
 * for the Returns tab, with full Arabic RTL and Western/Arabic-Indic digit handling.
 */
object MlKitInvoiceOcrService {
    private const val TAG = "MlKitInvoiceOcr"
    private const val MAX_RENDER_PAGES = 6
    private const val TARGET_RENDER_WIDTH_PX = 1800

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun extractFromPdf(
        context: Context,
        uri: Uri,
        displayName: String,
        masterItems: List<MasterInvoiceItem> = emptyList(),
        packagingRules: List<PackagingGroupRule> = emptyList()
    ): Result<ExtractedPdfInvoiceResult> {
        return runCatching {
            val pageBitmaps = renderPdfPagesToBitmaps(context, uri)
            require(pageBitmaps.isNotEmpty()) {
                "تعذر فتح صفحات ملف PDF للعرض / Failed to render PDF pages"
            }

            try {
                val fullTextBuilder = StringBuilder()
                val recognizedLines = mutableListOf<String>()

                for ((pageIdx, bitmap) in pageBitmaps.withIndex()) {
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    val visionText: Text = recognizer.process(inputImage).await()
                    fullTextBuilder.append("\n--- PAGE ${pageIdx + 1} ---\n")
                    fullTextBuilder.append(visionText.text)

                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            val cleanLine = line.text.trim()
                            if (cleanLine.isNotBlank()) {
                                recognizedLines.add(cleanLine)
                            }
                        }
                    }
                }

                val rawDocumentText = fullTextBuilder.toString()
                parseExtractedText(
                    rawDocumentText = rawDocumentText,
                    lines = recognizedLines,
                    displayName = displayName,
                    masterItems = masterItems,
                    packagingRules = packagingRules
                )
            } finally {
                pageBitmaps.forEach { if (!it.isRecycled) it.recycle() }
            }
        }
    }

    internal fun parseExtractedText(
        rawDocumentText: String,
        lines: List<String>,
        displayName: String,
        masterItems: List<MasterInvoiceItem> = emptyList(),
        packagingRules: List<PackagingGroupRule> = emptyList()
    ): ExtractedPdfInvoiceResult {
        val normalizedDoc = SpreadsheetService.normalizeDigits(rawDocumentText)

        // 1. Extract Invoice Number
        val invoiceNo = extractInvoiceNumber(normalizedDoc, displayName, masterItems)

        // 2. Extract Customer Name
        val customerName = extractCustomerName(lines)

        // 3. Extract Line Items (Codes, Names, Quantities, Prices)
        val extractedItems = extractLineItems(
            lines = lines,
            invoiceNo = invoiceNo,
            masterItems = masterItems,
            packagingRules = packagingRules
        )

        // If line items were sparse, check if the detected invoice matches Master DB rows
        val finalItems = if (extractedItems.isEmpty() && invoiceNo.isNotBlank()) {
            val masterRows = masterItems.filter { it.invoiceNo.equals(invoiceNo, ignoreCase = true) }
            masterRows.map { row ->
                ExtractedPdfInvoiceItem(
                    id = "mlk-${UUID.randomUUID().toString().take(8)}",
                    itemCode = row.itemCode,
                    itemName = row.itemName,
                    unit = row.unit,
                    invoicedQty = row.requiredQty.coerceAtLeast(1),
                    unitPrice = 0.0,
                    lineTotal = 0.0,
                    isSelected = true,
                    returnQty = row.requiredQty.coerceAtLeast(1),
                    condition = ReturnItemCondition.VALID_FOR_RESTOCK
                )
            }
        } else {
            extractedItems
        }

        return ExtractedPdfInvoiceResult(
            invoiceNo = invoiceNo,
            customerName = customerName,
            currency = detectCurrency(normalizedDoc),
            sourceLabel = "$displayName (ML Kit OCR)",
            extractionEngine = "ML_KIT_OCR",
            items = finalItems
        )
    }

    private fun extractInvoiceNumber(
        normalizedDoc: String,
        displayName: String,
        masterItems: List<MasterInvoiceItem>
    ): String {
        // Look for explicit invoice regex patterns
        val patterns = listOf(
            Regex("""(?:فاتورة|رقم\s*الفاتورة|فاتورة\s*رقم|Invoice|INV|Bill\s*No\.?)[:\s#-]*([A-Za-z0-9\-]+)""", RegexOption.IGNORE_CASE),
            Regex("""\b(20\d{6,8})\b"""), // Common 20xxxxxx invoice format
            Regex("""\b(INV-\d+)\b""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(normalizedDoc)
            if (match != null) {
                val candidate = match.groupValues.last().trim()
                if (candidate.length in 3..32) {
                    return candidate
                }
            }
        }

        // Match against known master invoices in doc text or file name
        for (item in masterItems) {
            if (item.invoiceNo.isNotBlank() && (normalizedDoc.contains(item.invoiceNo, ignoreCase = true) || displayName.contains(item.invoiceNo, ignoreCase = true))) {
                return item.invoiceNo
            }
        }

        // Fallback from file name digits
        val nameDigits = SpreadsheetService.normalizeDigits(displayName).filter { it.isDigit() }
        if (nameDigits.length >= 4) return nameDigits.take(12)

        return ""
    }

    private fun extractCustomerName(lines: List<String>): String {
        val keywords = listOf("العميل", "السيد", "مؤسسة", "شركة", "Customer", "Client", "Sold To")
        for (line in lines) {
            val normalized = SpreadsheetService.normalizeDigits(line)
            for (kw in keywords) {
                if (normalized.contains(kw, ignoreCase = true)) {
                    val cleaned = line.replace(Regex("""^(?:العميل|السيد|Customer|Client|Sold To)[:\s-]*"""), "").trim()
                    if (cleaned.length in 3..60) {
                        return cleaned
                    }
                }
            }
        }
        return ""
    }

    private fun extractLineItems(
        lines: List<String>,
        invoiceNo: String,
        masterItems: List<MasterInvoiceItem>,
        packagingRules: List<PackagingGroupRule>
    ): List<ExtractedPdfInvoiceItem> {
        val items = mutableListOf<ExtractedPdfInvoiceItem>()
        val masterForInvoice = masterItems.filter { it.invoiceNo.equals(invoiceNo, ignoreCase = true) }

        // Line-by-line item extraction
        for (rawLine in lines) {
            val line = SpreadsheetService.normalizeDigits(rawLine).trim()
            if (line.isBlank() || isHeaderOrFooterLine(line)) continue

            // 1. Check if line contains a known Master DB item code
            val matchedMaster = (if (masterForInvoice.isNotEmpty()) masterForInvoice else masterItems).firstOrNull { master ->
                master.itemCode.length >= 3 && line.contains(master.itemCode, ignoreCase = true)
            }

            if (matchedMaster != null) {
                val numbersInLine = extractNumbersFromLine(line)
                val qty = numbersInLine.firstOrNull { it in 1..9999 } ?: matchedMaster.requiredQty.coerceAtLeast(1)
                val price = numbersInLine.lastOrNull { it != qty.toDouble() && it > 0.0 } ?: 0.0
                val total = (qty * price * 100.0).roundToInt() / 100.0

                if (items.none { it.itemCode == matchedMaster.itemCode }) {
                    items.add(
                        ExtractedPdfInvoiceItem(
                            id = "mlk-${UUID.randomUUID().toString().take(8)}",
                            itemCode = matchedMaster.itemCode,
                            itemName = matchedMaster.itemName,
                            unit = matchedMaster.unit,
                            invoicedQty = qty,
                            unitPrice = price,
                            lineTotal = total,
                            isSelected = true,
                            returnQty = qty,
                            condition = ReturnItemCondition.VALID_FOR_RESTOCK
                        )
                    )
                }
                continue
            }

            // 2. Structural Line Pattern: [Code] [Description] [Qty] [Price] [Total]
            val tokens = line.split(Regex("""\s+""")).filter { it.isNotBlank() }
            if (tokens.size >= 3) {
                val codeCandidate = tokens.firstOrNull { tok ->
                    tok.length in 4..24 && tok.any(Char::isDigit) && !tok.contains("/")
                }

                if (codeCandidate != null) {
                    val numbers = extractNumbersFromLine(line)
                    val qty = numbers.firstOrNull { it >= 1.0 && it % 1.0 == 0.0 }?.toInt() ?: 1
                    val price = numbers.getOrNull(1)?.takeIf { it > 0.0 } ?: 0.0
                    val total = (qty * price * 100.0).roundToInt() / 100.0

                    val textTokens = tokens.filter { it != codeCandidate && it.toDoubleOrNull() == null }
                    val nameCandidate = textTokens.joinToString(" ").trim()
                    val matchedRule = PackagingEngine.matchBarcodeToPackagingRule(codeCandidate, packagingRules)

                    val finalName = when {
                        nameCandidate.isNotBlank() -> nameCandidate
                        matchedRule != null -> matchedRule.name
                        else -> "صنف $codeCandidate"
                    }

                    if (items.none { it.itemCode == codeCandidate }) {
                        items.add(
                            ExtractedPdfInvoiceItem(
                                id = "mlk-${UUID.randomUUID().toString().take(8)}",
                                itemCode = codeCandidate,
                                itemName = finalName,
                                unit = matchedRule?.unitName ?: "PCS",
                                invoicedQty = qty,
                                unitPrice = price,
                                lineTotal = total,
                                isSelected = true,
                                returnQty = qty,
                                condition = ReturnItemCondition.VALID_FOR_RESTOCK
                            )
                        )
                    }
                }
            }
        }

        return items
    }

    private fun extractNumbersFromLine(line: String): List<Double> {
        val matches = Regex("""\b\d+(?:\.\d+)?\b""").findAll(line)
        return matches.mapNotNull { it.value.toDoubleOrNull() }.filter { it.isFinite() }.toList()
    }

    private fun isHeaderOrFooterLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("page") || lower.contains("صفحة") ||
            lower.contains("subtotal") || lower.contains("المجموع") ||
            lower.contains("tax") || lower.contains("ضريبة") ||
            lower.contains("vat") || lower.contains("phone") ||
            lower.contains("هاتف") || lower.contains("سجل تجاري") ||
            lower.contains("cr no")
    }

    private fun detectCurrency(text: String): String {
        return when {
            text.contains("SAR", true) || text.contains("ريال") || text.contains("ر.س") -> "SAR"
            text.contains("AED", true) || text.contains("درهم") -> "AED"
            text.contains("USD", true) || text.contains("$") -> "USD"
            text.contains("EGP", true) || text.contains("جنيه") -> "EGP"
            else -> "SAR"
        }
    }

    private fun renderPdfPagesToBitmaps(context: Context, uri: Uri): List<Bitmap> {
        val bitmaps = mutableListOf<Bitmap>()
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
            renderer = PdfRenderer(pfd)
            val count = minOf(renderer.pageCount, MAX_RENDER_PAGES)
            for (i in 0 until count) {
                val page = renderer.openPage(i)
                try {
                    val scale = (TARGET_RENDER_WIDTH_PX.toFloat() / page.width.coerceAtLeast(1))
                        .coerceIn(1f, 3.5f)
                    val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                    val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmaps.add(bmp)
                } finally {
                    page.close()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed rendering PDF pages for ML Kit", e)
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
            bitmaps.clear()
        } finally {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
        return bitmaps
    }
}
