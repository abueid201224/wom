package com.example.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Log
import com.example.model.*
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

object PdfInvoiceOcrService {
    private const val TAG = "PdfInvoiceOcrService"
    private const val MAX_RENDER_PAGES = 6
    private const val TARGET_RENDER_WIDTH_PX = 1600

    private val invoiceExtractionSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "invoiceNo" to Schema.string(description = "Invoice number printed on the document"),
                "orderNo" to Schema.string(description = "Order reference number if visible", nullable = true),
                "customerName" to Schema.string(description = "Customer or client name in natural Arabic or English order"),
                "currency" to Schema.string(description = "Currency code like SAR, AED, EGP, USD"),
                "items" to Schema.array(
                    items = Schema.obj(
                        properties = mapOf(
                            "itemCode" to Schema.string(description = "SKU, item code, or barcode digits"),
                            "itemName" to Schema.string(description = "Full item description in logical Arabic/English reading order"),
                            "unit" to Schema.string(description = "Unit of measure e.g. PCS, BOX, CTN, HDZ, DZ, EA"),
                            "invoicedQty" to Schema.integer(description = "Quantity invoiced as integer"),
                            "unitPrice" to Schema.double(description = "Price per single unit"),
                            "lineTotal" to Schema.double(description = "Line total price = invoicedQty * unitPrice")
                        ),
                        optionalProperties = listOf("unit", "lineTotal")
                    ),
                    description = "All product line items extracted from the invoice table"
                )
            ),
            optionalProperties = listOf("orderNo", "currency")
        )
    }

    private val model by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = invoiceExtractionSchema
            }
        )
    }

    suspend fun extractOriginalInvoiceFromPdf(
        context: Context,
        uri: Uri,
        displayName: String,
        masterItems: List<MasterInvoiceItem> = emptyList(),
        packagingRules: List<PackagingGroupRule> = emptyList(),
        engine: String = "AUTO"
    ): Result<ExtractedPdfInvoiceResult> {
        var mlKitCached: ExtractedPdfInvoiceResult? = null

        // 1. Run ML Kit Text Recognition integration service (on-device spatial RTL OCR)
        if (engine.equals("ML_KIT", ignoreCase = true) || engine.equals("AUTO", ignoreCase = true)) {
            val mlKitResult = MlKitInvoiceOcrService.extractFromPdf(
                context = context,
                uri = uri,
                displayName = displayName,
                masterItems = masterItems,
                packagingRules = packagingRules
            )
            if (mlKitResult.isSuccess) {
                val extracted = mlKitResult.getOrThrow()
                mlKitCached = extracted
                val hasMeaningfulNames = extracted.items.isNotEmpty() &&
                    extracted.items.none { it.itemName.startsWith("صنف ") }
                if (engine.equals("ML_KIT", ignoreCase = true) || hasMeaningfulNames) {
                    return Result.success(extracted)
                }
            } else if (engine.equals("ML_KIT", ignoreCase = true)) {
                return mlKitResult
            }
        }

        // 2. Cloud Gemini AI Logic (multimodal Arabic RTL fallback / enrichment)
        val rawBytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return if (mlKitCached != null && mlKitCached.items.isNotEmpty()) {
            Result.success(mlKitCached)
        } else {
            Result.failure(IllegalArgumentException("تعذر قراءة ملف PDF المحدد / Cannot read selected PDF file."))
        }

        val pageBitmaps = renderPdfPagesToBitmaps(context, uri)
        if (pageBitmaps.isEmpty()) {
            val localOnly = mlKitCached?.takeIf { it.items.isNotEmpty() }
                ?: extractFallbackFromRawBytes(rawBytes, displayName, masterItems)
            return if (localOnly != null) Result.success(localOnly)
            else Result.failure(IllegalStateException("الملف لا يحتوي على صفحات PDF صالحة للعرض / Invalid or empty PDF file."))
        }

        return try {
            val promptText = """
                You are an expert bilingual Arabic & English warehouse invoice OCR and RTL table parser.
                Analyze the attached invoice page image(s) carefully:
                1. Pay strict attention to Right-to-Left (RTL) Arabic invoice layout and table columns (typically arranged right-to-left: رقم الصنف/الباركود | اسم الصنف/البيان | الوحدة | الكمية | سعر الوحدة | الإجمالي, or check exact column headers).
                2. Preserve natural logical Arabic word order (never output reversed Arabic characters or swapped right-to-left columns).
                3. Convert any Arabic-Indic numerals (٠١٢٣٤٥٦٧٨٩) to standard Western digits (0-9).
                4. Extract invoiceNo, orderNo (if present), customerName, currency, and EVERY product line item with itemCode, itemName, unit, invoicedQty, unitPrice, and lineTotal.
                5. If unitPrice is missing while lineTotal and invoicedQty > 0 exist, compute unitPrice = lineTotal / invoicedQty.
                6. If lineTotal is missing, compute lineTotal = invoicedQty * unitPrice.
                Do not invent items that are not printed on the invoice.
            """.trimIndent()

            val response = withTimeout(60_000L) {
                model.generateContent(
                    content {
                        pageBitmaps.forEach { bmp -> image(bmp) }
                        text(promptText)
                    }
                )
            }
            val jsonText = response.text
                ?: throw IllegalStateException("أعاد نموذج OCR استجابة فارغة / OCR returned an empty response.")
            val parsed = parseStructuredInvoiceJson(jsonText, displayName)
            if (parsed.items.isEmpty()) {
                val fallback = mlKitCached?.takeIf { it.items.isNotEmpty() }
                    ?: extractFallbackFromRawBytes(rawBytes, displayName, masterItems)
                if (fallback != null) Result.success(fallback)
                else Result.failure(IllegalStateException("لم يتم العثور على بنود أصناف داخل الفاتورة / No line items detected in invoice PDF."))
            } else {
                Result.success(parsed)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Arabic PDF OCR timed out", e)
            val fallback = mlKitCached?.takeIf { it.items.isNotEmpty() }
                ?: extractFallbackFromRawBytes(rawBytes, displayName, masterItems)
            if (fallback != null) Result.success(fallback)
            else Result.failure(IllegalStateException("انتهت مهلة قراءة الفاتورة (60 ثانية). تحقق من الاتصال وأعد المحاولة."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Firebase AI PDF OCR failed", e)
            val fallback = mlKitCached?.takeIf { it.items.isNotEmpty() }
                ?: extractFallbackFromRawBytes(rawBytes, displayName, masterItems)
            if (fallback != null) Result.success(fallback)
            else Result.failure(e)
        } finally {
            pageBitmaps.forEach { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    internal fun parseStructuredInvoiceJson(
        jsonString: String,
        sourceLabel: String
    ): ExtractedPdfInvoiceResult {
        val obj = JSONObject(jsonString)
        val invoiceNo = SpreadsheetService.normalizeDigits(obj.optString("invoiceNo", "")).trim()
        val orderNo = obj.optString("orderNo", "").takeIf { it.isNotBlank() && it != "null" }
        val customerName = obj.optString("customerName", "").trim()
        val currency = obj.optString("currency", "SAR").ifBlank { "SAR" }

        val arr = obj.optJSONArray("items")
        val items = mutableListOf<ExtractedPdfInvoiceItem>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val code = SpreadsheetService.normalizeDigits(row.optString("itemCode", "")).trim()
                val name = row.optString("itemName", "").trim()
                if (code.isBlank() && name.isBlank()) continue

                val qty = row.optInt("invoicedQty", 1).coerceAtLeast(1)
                var unitPrice = row.optDouble("unitPrice", 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                var lineTotal = row.optDouble("lineTotal", 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                if (unitPrice == 0.0 && lineTotal > 0.0 && qty > 0) {
                    unitPrice = (lineTotal / qty * 100.0).roundToInt() / 100.0
                } else if (lineTotal == 0.0 && unitPrice > 0.0) {
                    lineTotal = (qty * unitPrice * 100.0).roundToInt() / 100.0
                }
                val unit = row.optString("unit", "PCS").ifBlank { "PCS" }.uppercase()

                items.add(
                    ExtractedPdfInvoiceItem(
                        id = "pdf-${UUID.randomUUID().toString().take(8)}",
                        itemCode = code.ifBlank { "ITEM-${i + 1}" },
                        itemName = name.ifBlank { code.ifBlank { "Item ${i + 1}" } },
                        unit = unit,
                        invoicedQty = qty,
                        unitPrice = unitPrice,
                        lineTotal = lineTotal,
                        isSelected = true,
                        returnQty = qty,
                        condition = ReturnItemCondition.VALID_FOR_RESTOCK
                    )
                )
            }
        }

        return ExtractedPdfInvoiceResult(
            invoiceNo = invoiceNo,
            orderNo = orderNo,
            customerName = customerName,
            currency = currency,
            sourceLabel = sourceLabel,
            extractionEngine = "GEMINI_RTL_OCR",
            items = items
        )
    }

    internal fun extractFallbackFromRawBytes(
        rawBytes: ByteArray,
        displayName: String,
        masterItems: List<MasterInvoiceItem>
    ): ExtractedPdfInvoiceResult? {
        if (rawBytes.isEmpty()) return null
        val textUtf8 = rawBytes.toString(Charsets.UTF_8)
        val textUtf16Be = runCatching { rawBytes.toString(Charsets.UTF_16BE) }.getOrDefault("")
        val combined = "$textUtf8\n$textUtf16Be\n$displayName"
        val normalizedCombined = SpreadsheetService.normalizeDigits(combined)

        // 1. Match known invoice number in Master DB
        val distinctInvoices = masterItems.map { it.invoiceNo }.distinct()
        val matchedInvoiceNo = distinctInvoices.firstOrNull { inv ->
            inv.isNotBlank() && normalizedCombined.contains(inv, ignoreCase = true)
        }
        if (matchedInvoiceNo != null) {
            return buildFromMasterInvoice(
                invoiceNo = matchedInvoiceNo,
                masterItems = masterItems,
                sourceLabel = "$displayName (Master DB Match)",
                extractionEngine = "LOCAL_MASTER_FALLBACK"
            )
        }

        // 2. Match individual item codes embedded in raw PDF text streams
        val matchedRows = masterItems.filter { item ->
            item.itemCode.length >= 4 && normalizedCombined.contains(item.itemCode, ignoreCase = true)
        }
        if (matchedRows.isNotEmpty()) {
            val invNo = matchedRows.first().invoiceNo
            return ExtractedPdfInvoiceResult(
                invoiceNo = invNo,
                orderNo = matchedRows.firstOrNull()?.orderNo,
                customerName = "",
                sourceLabel = "$displayName (Local PDF Code Match)",
                extractionEngine = "LOCAL_MASTER_FALLBACK",
                items = matchedRows.map { row ->
                    ExtractedPdfInvoiceItem(
                        id = "pdf-${UUID.randomUUID().toString().take(8)}",
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
            )
        }
        return null
    }

    fun buildFromMasterInvoice(
        invoiceNo: String,
        masterItems: List<MasterInvoiceItem>,
        existingReturns: List<ReturnReport> = emptyList(),
        sourceLabel: String = "مزامنة الفاتورة #$invoiceNo",
        extractionEngine: String = "MASTER_SYNC"
    ): ExtractedPdfInvoiceResult {
        val priceHints = mutableMapOf<String, Double>()
        for (rep in existingReturns) {
            for (it in rep.items) {
                if (it.unitPrice > 0.0) {
                    priceHints[PackagingEngine.normalizeCode(it.itemCode)] = it.unitPrice
                }
            }
        }

        val invoiceRows = masterItems.filter { it.invoiceNo.equals(invoiceNo, ignoreCase = true) }
        val items = invoiceRows.map { row ->
            val norm = PackagingEngine.normalizeCode(row.itemCode)
            val price = priceHints[norm] ?: 0.0
            val qty = row.requiredQty.coerceAtLeast(1)
            ExtractedPdfInvoiceItem(
                id = "sync-${UUID.randomUUID().toString().take(8)}",
                itemCode = row.itemCode,
                itemName = row.itemName,
                unit = row.unit,
                invoicedQty = qty,
                unitPrice = price,
                lineTotal = (qty * price * 100.0).roundToInt() / 100.0,
                isSelected = true,
                returnQty = qty,
                condition = ReturnItemCondition.VALID_FOR_RESTOCK
            )
        }
        return ExtractedPdfInvoiceResult(
            invoiceNo = invoiceRows.firstOrNull()?.invoiceNo ?: invoiceNo,
            orderNo = invoiceRows.firstOrNull()?.orderNo,
            customerName = "",
            sourceLabel = sourceLabel,
            extractionEngine = extractionEngine,
            items = items
        )
    }

    private fun renderPdfPagesToBitmaps(context: Context, uri: Uri): List<Bitmap> {
        val bitmaps = mutableListOf<Bitmap>()
        var pfd: android.os.ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
            renderer = PdfRenderer(pfd)
            val count = minOf(renderer.pageCount, MAX_RENDER_PAGES)
            for (i in 0 until count) {
                val page = renderer.openPage(i)
                try {
                    val scale = (TARGET_RENDER_WIDTH_PX.toFloat() / page.width.coerceAtLeast(1))
                        .coerceIn(1f, 3f)
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
            Log.e(TAG, "Failed to render PDF pages", e)
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
            bitmaps.clear()
        } finally {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
        return bitmaps
    }
}
