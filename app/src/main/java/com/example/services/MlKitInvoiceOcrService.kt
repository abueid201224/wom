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

                    val pageLines = reconstructRtlSpatialRows(visionText)
                    recognizedLines.addAll(pageLines)
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

    /**
     * Reconstructs logical table rows from ML Kit Text blocks using vertical Y-center proximity
     * and orders columns Right-to-Left (RTL) so Arabic/bilingual invoice tables keep aligned
     * ItemCode, ItemName, Qty, UnitPrice, and LineTotal columns on the same line.
     */
    internal fun reconstructRtlSpatialRows(visionText: Text): List<String> {
        val rawLines = visionText.textBlocks.flatMap { it.lines }
            .filter { it.text.isNotBlank() }
        if (rawLines.isEmpty()) return emptyList()

        val sortedByY = rawLines.sortedBy { it.boundingBox?.centerY() ?: 0 }
        val rows = mutableListOf<MutableList<Text.Line>>()
        val avgHeight = rawLines.mapNotNull { it.boundingBox?.height()?.takeIf { h -> h > 0 } }
            .average()
            .takeIf { !it.isNaN() && it > 0.0 } ?: 24.0
        val yTolerance = (avgHeight * 0.65).roundToInt().coerceAtLeast(10)

        for (line in sortedByY) {
            val cy = line.boundingBox?.centerY()
            val targetRow = if (cy != null) {
                rows.lastOrNull { group ->
                    val refCy = group.firstOrNull()?.boundingBox?.centerY() ?: return@lastOrNull false
                    kotlin.math.abs(cy - refCy) <= yTolerance
                }
            } else null

            if (targetRow != null) {
                targetRow.add(line)
            } else {
                rows.add(mutableListOf(line))
            }
        }

        return rows.mapNotNull { row ->
            val rtlOrdered = row.sortedByDescending { it.boundingBox?.right ?: 0 }
            val joined = rtlOrdered.joinToString("  ") { it.text.trim() }.trim()
            joined.takeIf { it.isNotBlank() }
        }
    }

    internal fun parseExtractedText(
        rawDocumentText: String,
        lines: List<String>,
        displayName: String,
        masterItems: List<MasterInvoiceItem> = emptyList(),
        packagingRules: List<PackagingGroupRule> = emptyList()
    ): ExtractedPdfInvoiceResult {
        val normalizedDoc = normalizeArabicTextAndNumbers(rawDocumentText)

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

    internal fun normalizeArabicTextAndNumbers(input: String): String {
        val digitsNormalized = SpreadsheetService.normalizeDigits(input)
        return digitsNormalized
            .replace('\u066B', '.') // Arabic decimal separator ٫
            .replace('\u066C', ',') // Arabic thousands separator ٬
            .replace('\u200F', ' ') // RTL mark
            .replace('\u200E', ' ') // LTR mark
    }

    private fun extractInvoiceNumber(
        normalizedDoc: String,
        displayName: String,
        masterItems: List<MasterInvoiceItem>
    ): String {
        val patterns = listOf(
            Regex("""(?:فاتورة|رقم\s*الفاتورة|فاتورة\s*رقم|Invoice\s*No\.?|Invoice|INV|Bill\s*No\.?)[:\s#-]*([A-Za-z0-9\-]+)""", RegexOption.IGNORE_CASE),
            Regex("""\b(20\d{6,8})\b"""),
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

        for (item in masterItems) {
            if (item.invoiceNo.isNotBlank() && (normalizedDoc.contains(item.invoiceNo, ignoreCase = true) || displayName.contains(item.invoiceNo, ignoreCase = true))) {
                return item.invoiceNo
            }
        }

        val nameDigits = normalizeArabicTextAndNumbers(displayName).filter { it.isDigit() }
        if (nameDigits.length >= 4) return nameDigits.take(12)

        return ""
    }

    private fun extractCustomerName(lines: List<String>): String {
        val keywords = listOf("العميل", "السيد", "مؤسسة", "شركة", "Customer", "Client", "Sold To")
        for (line in lines) {
            val normalized = normalizeArabicTextAndNumbers(line)
            for (kw in keywords) {
                if (normalized.contains(kw, ignoreCase = true)) {
                    val cleaned = normalized.replace(Regex("""^(?:العميل|السيد|Customer|Client|Sold To)[:\s-]*""", RegexOption.IGNORE_CASE), "").trim()
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

        for (rawLine in lines) {
            val line = normalizeArabicTextAndNumbers(rawLine).trim()
            if (line.isBlank() || isHeaderOrFooterLine(line, invoiceNo)) continue

            // 1. Check if line contains a known Master DB item code
            val matchedMaster = (if (masterForInvoice.isNotEmpty()) masterForInvoice else masterItems).firstOrNull { master ->
                master.itemCode.length >= 3 && line.contains(master.itemCode, ignoreCase = true)
            }

            if (matchedMaster != null) {
                val numbersInLine = extractNumbersWithoutCode(line, matchedMaster.itemCode)
                val (qty, unitPrice, lineTotal) = resolveQtyPriceTotal(
                    numbers = numbersInLine,
                    fallbackQty = matchedMaster.requiredQty.coerceAtLeast(1)
                )

                if (items.none { it.itemCode == matchedMaster.itemCode }) {
                    items.add(
                        ExtractedPdfInvoiceItem(
                            id = "mlk-${UUID.randomUUID().toString().take(8)}",
                            itemCode = matchedMaster.itemCode,
                            itemName = matchedMaster.itemName,
                            unit = matchedMaster.unit,
                            invoicedQty = qty,
                            unitPrice = unitPrice,
                            lineTotal = lineTotal,
                            isSelected = true,
                            returnQty = qty,
                            condition = ReturnItemCondition.VALID_FOR_RESTOCK
                        )
                    )
                }
                continue
            }

            // 2. Structural Line Pattern: [Code] [Description] [Qty] [UnitPrice] [LineTotal]
            val tokens = line.split(Regex("""\s+""")).filter { it.isNotBlank() }
            if (tokens.size >= 3) {
                val codeCandidate = tokens.firstOrNull { tok ->
                    tok.length in 5..24 && tok.all { it.isLetterOrDigit() || it == '-' } && tok.any(Char::isDigit) && !tok.contains(".")
                }

                if (codeCandidate != null) {
                    val numbers = extractNumbersWithoutCode(line, codeCandidate)
                    val (qty, price, total) = resolveQtyPriceTotal(numbers = numbers, fallbackQty = 1)

                    val textTokens = tokens.filter { tok ->
                        tok != codeCandidate && tok.replace(",", "").toDoubleOrNull() == null
                    }
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

    private fun resolveQtyPriceTotal(numbers: List<Double>, fallbackQty: Int): Triple<Int, Double, Double> {
        // 1. Prefer exact trailing (qty, unitPrice, lineTotal) triple where qty * unitPrice == lineTotal
        if (numbers.size >= 3) {
            for (i in (numbers.size - 3) downTo 0) {
                val a = numbers[i]
                val b = numbers[i + 1]
                val c = numbers[i + 2]
                if (a in 1.0..9999.0 && a % 1.0 == 0.0 && b > 0.0 && c > 0.0 && kotlin.math.abs(a * b - c) <= 0.15) {
                    val q = a.toInt()
                    val p = (b * 100.0).roundToInt() / 100.0
                    val t = (c * 100.0).roundToInt() / 100.0
                    return Triple(q, p, t)
                }
            }
        }

        // 2. Fallback: parse trailing numbers when only qty + price or qty + total are present
        val qty = numbers.firstOrNull { it >= 1.0 && it <= 9999.0 && it % 1.0 == 0.0 }?.toInt() ?: fallbackQty
        val remainingNums = numbers.drop(if (numbers.isNotEmpty()) 1 else 0).filter { it >= 0.0 }
        var price = remainingNums.firstOrNull() ?: 0.0
        var total = if (remainingNums.size >= 2) remainingNums.last() else (qty * price * 100.0).roundToInt() / 100.0
        if (price == 0.0 && total > 0.0 && qty > 0) {
            price = (total / qty * 100.0).roundToInt() / 100.0
        } else if (total == 0.0 && price > 0.0) {
            total = (qty * price * 100.0).roundToInt() / 100.0
        }
        return Triple(qty, price, total)
    }

    private fun extractNumbersWithoutCode(line: String, code: String): List<Double> {
        val lineWithoutCode = line.replace(code, " ", ignoreCase = true)
            .replace(",", "")
        val matches = Regex("""\b\d+(?:\.\d+)?\b""").findAll(lineWithoutCode)
        return matches.mapNotNull { it.value.toDoubleOrNull() }.filter { it.isFinite() }.toList()
    }

    private fun isHeaderOrFooterLine(line: String, invoiceNo: String): Boolean {
        val lower = line.lowercase()
        val isInvoiceHeader = lower.contains("فاتورة") || lower.contains("invoice") ||
            lower.contains("رقم الطلب") || lower.contains("order no") ||
            lower.contains("العميل") || lower.contains("customer") ||
            lower.contains("sold to") || lower.contains("client") ||
            lower.contains("تاريخ") || lower.contains("date:") ||
            (invoiceNo.isNotBlank() && lower.trim() == invoiceNo.lowercase())
        return isInvoiceHeader ||
            lower.contains("page") || lower.contains("صفحة") ||
            lower.contains("subtotal") || lower.contains("المجموع") ||
            lower.contains("الإجمالي النهائي") || lower.contains("grand total") ||
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
