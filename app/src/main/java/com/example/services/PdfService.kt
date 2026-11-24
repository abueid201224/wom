package com.example.services

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.Base64
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.model.*
import java.io.File
import java.io.FileOutputStream

object PdfService {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 36f
    private const val CONTENT_WIDTH = (PAGE_WIDTH - MARGIN * 2).toInt()

    fun exportDiscrepancyAuditPdf(
        context: Context,
        auditorName: String,
        auditorId: String,
        signatureBase64: String?,
        discrepancies: List<AuditDiscrepancy>,
        wrongPicks: List<WrongPickingRecord>,
        completedInvoices: List<CompletedInvoiceRecord>,
        sessionSummary: SessionApprovalSummary? = null,
        isRtl: Boolean = true
    ): Result<File> {
        return runCatching {
            val doc = PdfDocument()
            var pageNum = 1
            var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
            var canvas = page.canvas

            val totalScanned = sessionSummary?.totalScannedQty ?: completedInvoices.sumOf { it.totalQty }
            val totalRequired = sessionSummary?.totalRequiredQty ?: totalScanned
            val totalInvoices = sessionSummary?.totalMasterInvoices ?: completedInvoices.size
            val accuracy = sessionSummary?.matchAccuracyPercent ?: 100
            val shortageQty = sessionSummary?.totalShortageQty
                ?: discrepancies.filter { it.difference < 0 }.sumOf { -it.difference }
            val surplusQty = sessionSummary?.totalSurplusQty
                ?: discrepancies.filter { it.difference > 0 }.sumOf { it.difference }

            val title = if (isRtl) {
                "تقرير التدقيق والاعتماد الشامل (ISA 500)"
            } else {
                "ISA 500 Full Session Audit & Approval Report"
            }
            val subtitle1 = if (isRtl) {
                "المدقق: $auditorName ($auditorId) • الفواتير: ${completedInvoices.size}/$totalInvoices • الدقة: $accuracy%"
            } else {
                "Auditor: $auditorName ($auditorId) • Invoices: ${completedInvoices.size}/$totalInvoices • Accuracy: $accuracy%"
            }
            val subtitle2 = if (isRtl) {
                "المسح: $totalScanned/$totalRequired • الأخطاء: ${discrepancies.size} • العجز: -$shortageQty | الزيادة: +$surplusQty"
            } else {
                "Scanned: $totalScanned/$totalRequired • Errors: ${discrepancies.size} • Shortage: -$shortageQty | Surplus: +$surplusQty"
            }

            var y = drawHeader(canvas, title, subtitle1, subtitle2, isRtl)

            val sectionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(15, 23, 42)
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
            }
            val rowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(51, 65, 85)
                textSize = 10f
            }
            val dividerPaint = Paint().apply {
                color = Color.rgb(203, 213, 225)
                strokeWidth = 0.8f
            }

            fun ensureSpace(neededHeight: Float) {
                if (y + neededHeight > PAGE_HEIGHT - 64f) {
                    doc.finishPage(page)
                    pageNum++
                    page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
                    canvas = page.canvas
                    y = MARGIN
                }
            }

            fun drawLineBlock(text: String, paint: TextPaint, rtl: Boolean = isRtl, extraGap: Float = 4f) {
                val layout = buildStaticLayout(text, paint, CONTENT_WIDTH, rtl)
                ensureSpace(layout.height + extraGap)
                canvas.save()
                canvas.translate(MARGIN, y)
                layout.draw(canvas)
                canvas.restore()
                y += layout.height + extraGap
            }

            // Session Invoices Table
            val invoiceRows = sessionSummary?.invoiceDetails.orEmpty()
            if (invoiceRows.isNotEmpty() || completedInvoices.isNotEmpty()) {
                val headerText = if (isRtl) {
                    "ملخص فواتير الجلسة (${invoiceRows.size.takeIf { it > 0 } ?: completedInvoices.size}):"
                } else {
                    "Session Invoices Summary (${invoiceRows.size.takeIf { it > 0 } ?: completedInvoices.size}):"
                }
                drawLineBlock(headerText, sectionPaint, extraGap = 6f)

                if (invoiceRows.isNotEmpty()) {
                    for (inv in invoiceRows) {
                        val line = if (isRtl) {
                            "فاتورة ${inv.invoiceNo} | المسح: ${inv.scannedQty}/${inv.requiredQty} | مطابق: ${inv.exactItemsCount}/${inv.requiredItemsCount} | أخطاء: ${inv.discrepancyCount} (${inv.status})"
                        } else {
                            "Invoice ${inv.invoiceNo} | Scan: ${inv.scannedQty}/${inv.requiredQty} | Exact: ${inv.exactItemsCount}/${inv.requiredItemsCount} | Errors: ${inv.discrepancyCount} (${inv.status})"
                        }
                        drawLineBlock(line, rowPaint, extraGap = 4f)
                    }
                } else {
                    for (inv in completedInvoices) {
                        val invDiscCount = discrepancies.count { it.invoiceNo == inv.invoiceNo }
                        val invStatus = if (invDiscCount == 0) "EXACT" else "DISCREPANCY ($invDiscCount)"
                        val line = "Invoice ${inv.invoiceNo} | Items: ${inv.totalItems} | Scanned Qty: ${inv.totalQty} | Status: $invStatus"
                        drawLineBlock(line, rowPaint, rtl = false, extraGap = 4f)
                    }
                }
                ensureSpace(12f)
                canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, dividerPaint)
                y += 10f
            }

            // Discrepancies Table
            val discHeader = if (isRtl) {
                "تفاصيل الفروقات والأخطاء (${discrepancies.size}):"
            } else {
                "Discrepancy Details (${discrepancies.size}):"
            }
            drawLineBlock(discHeader, sectionPaint, extraGap = 6f)

            if (discrepancies.isEmpty()) {
                drawLineBlock(
                    if (isRtl) "لا توجد أي فروقات — جميع الفواتير مطابقة بنسبة 100%." else "Zero discrepancies — all audited invoices matched 100%.",
                    rowPaint,
                    extraGap = 6f
                )
            } else {
                for (d in discrepancies) {
                    val sign = if (d.difference > 0) "+" else ""
                    val line = if (isRtl) {
                        "${d.invoiceNo} | ${d.itemCode} • ${d.itemName.take(28)} | مطلوب: ${d.requiredQty} | فعلي: ${d.actualQty} | الفرق: $sign${d.difference} (${d.qtyStatus.name})"
                    } else {
                        "${d.invoiceNo} | ${d.itemCode} • ${d.itemName.take(28)} | Req: ${d.requiredQty} | Act: ${d.actualQty} | Diff: $sign${d.difference} (${d.qtyStatus.name})"
                    }
                    drawLineBlock(line, rowPaint, extraGap = 4f)
                }
            }

            if (wrongPicks.isNotEmpty()) {
                y += 6f
                val wpHeader = if (isRtl) {
                    "تنبيهات التجميع الخاطئ (${wrongPicks.size}):"
                } else {
                    "Wrong-Picking Cross-Invoice Alerts (${wrongPicks.size}):"
                }
                drawLineBlock(wpHeader, sectionPaint, extraGap = 6f)
                for (w in wrongPicks) {
                    val line = "${w.activeInvoiceNo} <- ${w.itemCode} (Belongs: ${w.actualBelongingInvoiceNo ?: "UNKNOWN"})"
                    drawLineBlock(line, rowPaint, rtl = false, extraGap = 4f)
                }
            }

            ensureSpace(95f)
            drawSignatureBlock(canvas, y + 14f, auditorName, signatureBase64, isRtl)
            doc.finishPage(page)

            val outFile = savePdfFile(context, "ISA500_Audit_Report_${System.currentTimeMillis()}.pdf", doc)
            doc.close()
            sharePdfFile(context, outFile)
            outFile
        }
    }

    fun exportReturnReportPdf(
        context: Context,
        report: ReturnReport
    ): Result<File> {
        return runCatching {
            val doc = PdfDocument()
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create())
            val canvas = page.canvas
            var y = drawHeader(
                canvas,
                "إيصال وفحص المرتجعات (RMA)",
                "الإيصال: ${report.returnReceiptNo} | الفاتورة: ${report.originalInvoiceNo}",
                "العميل: ${report.customerName} | الحالة: ${report.status.name} | الإجمالي: ${report.totalRefundAmount}",
                isRtl = true
            )

            val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(15, 23, 42)
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
            }
            val rowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(51, 65, 85)
                textSize = 10f
            }

            val headerLayout = buildStaticLayout("الكود | الصنف | كمية المرتجع | الحالة | قرار المختبر | الإجمالي", headerPaint, CONTENT_WIDTH, isRtl = true)
            canvas.save()
            canvas.translate(MARGIN, y)
            headerLayout.draw(canvas)
            canvas.restore()
            y += headerLayout.height + 6f

            for (item in report.items.take(36)) {
                val line = "${item.itemCode} | ${item.itemName.take(24)} | ${item.actualReturnedQty} | ${item.condition.name} | ${item.labDecision.name} | ${item.refundTotal}"
                val rowLayout = buildStaticLayout(line, rowPaint, CONTENT_WIDTH, isRtl = true)
                canvas.save()
                canvas.translate(MARGIN, y)
                rowLayout.draw(canvas)
                canvas.restore()
                y += rowLayout.height + 4f
            }

            drawSignatureBlock(canvas, minOf(y + 20f, PAGE_HEIGHT - 95f), report.auditorName, report.auditorSignature, isRtl = true)
            doc.finishPage(page)

            val outFile = savePdfFile(context, "RMA_${report.returnReceiptNo}_${System.currentTimeMillis()}.pdf", doc)
            doc.close()
            sharePdfFile(context, outFile)
            outFile
        }
    }

    fun exportIncompleteInvoicesPdf(
        context: Context,
        auditorName: String,
        auditorId: String,
        records: List<IncompleteInvoiceRecord>,
        isRtl: Boolean = true
    ): Result<File> {
        return runCatching {
            val doc = PdfDocument()
            var pageNum = 1
            var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
            var canvas = page.canvas

            val totalMissing = records.sumOf { it.missingQty }
            val title = if (isRtl) "تقرير فواتير غير مكتملة (مرحّلة مع النواقص)" else "Incomplete Invoices Report (Deferred with Shortages)"
            val sub1 = if (isRtl) "المُعِد: $auditorName ($auditorId) • عدد الفواتير: ${records.size}" else "Prepared by: $auditorName ($auditorId) • Invoices: ${records.size}"
            val sub2 = if (isRtl) "إجمالي النواقص المسجلة: $totalMissing قطعة" else "Total Recorded Shortages: $totalMissing pcs"

            var y = drawHeader(canvas, title, sub1, sub2, isRtl)

            val sectionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(15, 23, 42)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
            }
            val rowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(51, 65, 85)
                textSize = 10f
            }

            fun drawLine(text: String, paint: TextPaint, gap: Float = 4f) {
                val layout = buildStaticLayout(text, paint, CONTENT_WIDTH, isRtl)
                if (y + layout.height + gap > PAGE_HEIGHT - 64f) {
                    doc.finishPage(page)
                    pageNum++
                    page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
                    canvas = page.canvas
                    y = MARGIN
                }
                canvas.save()
                canvas.translate(MARGIN, y)
                layout.draw(canvas)
                canvas.restore()
                y += layout.height + gap
            }

            if (records.isEmpty()) {
                drawLine(
                    if (isRtl) "لا توجد فواتير غير مكتملة مسجلة حالياً." else "No incomplete invoices currently recorded.",
                    rowPaint,
                    6f
                )
            } else {
                for (rec in records) {
                    val invHeader = if (isRtl) {
                        "فاتورة ${rec.invoiceNo} | ممسوح: ${rec.scannedQty}/${rec.totalRequiredQty} | نواقص: ${rec.missingQty} | محاولات الترحيل: ${rec.deferCount}"
                    } else {
                        "Invoice ${rec.invoiceNo} | Scanned: ${rec.scannedQty}/${rec.totalRequiredQty} | Missing: ${rec.missingQty} | Deferrals: ${rec.deferCount}"
                    }
                    drawLine(invHeader, sectionPaint, 5f)
                    for (item in rec.shortageItemsSnapshot) {
                        val line = if (isRtl) {
                            "  • ${item.itemCode} | ${item.itemName.take(26)} | مطلوب: ${item.requiredQty} | فعلي: ${item.actualQty} | فرق: ${item.difference}"
                        } else {
                            "  • ${item.itemCode} | ${item.itemName.take(26)} | Req: ${item.requiredQty} | Act: ${item.actualQty} | Diff: ${item.difference}"
                        }
                        drawLine(line, rowPaint, 3f)
                    }
                    y += 6f
                }
            }

            doc.finishPage(page)
            val outFile = savePdfFile(context, "Incomplete_Invoices_${System.currentTimeMillis()}.pdf", doc)
            doc.close()
            sharePdfFile(context, outFile)
            outFile
        }
    }

    private fun buildStaticLayout(
        text: String,
        paint: TextPaint,
        widthPx: Int,
        isRtl: Boolean
    ): StaticLayout {
        val dir = if (isRtl) TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(100))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(dir)
            .setLineSpacing(2f, 1.05f)
            .setIncludePad(false)
            .build()
    }

    private fun drawHeader(
        canvas: Canvas,
        title: String,
        subtitle1: String,
        subtitle2: String,
        isRtl: Boolean
    ): Float {
        val bgPaint = Paint().apply { color = Color.rgb(15, 23, 42) }
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 106f, bgPaint)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(52, 211, 153)
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 10f
        }

        val tLayout = buildStaticLayout(title, titlePaint, CONTENT_WIDTH, isRtl)
        canvas.save()
        canvas.translate(MARGIN, 18f)
        tLayout.draw(canvas)
        canvas.restore()

        val s1Layout = buildStaticLayout(subtitle1, subPaint, CONTENT_WIDTH, isRtl)
        canvas.save()
        canvas.translate(MARGIN, 46f)
        s1Layout.draw(canvas)
        canvas.restore()

        val s2Layout = buildStaticLayout(subtitle2, subPaint, CONTENT_WIDTH, isRtl)
        canvas.save()
        canvas.translate(MARGIN, 70f)
        s2Layout.draw(canvas)
        canvas.restore()

        return 124f
    }

    private fun drawSignatureBlock(
        canvas: Canvas,
        startY: Float,
        auditorName: String,
        signatureBase64: String?,
        isRtl: Boolean
    ) {
        val y = startY.coerceAtMost(PAGE_HEIGHT - 95f)
        val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        val label = if (isRtl) "اعتماد وتوقيع المدقق: $auditorName" else "Auditor Signature: $auditorName"
        val layout = buildStaticLayout(label, labelPaint, CONTENT_WIDTH, isRtl)
        canvas.save()
        canvas.translate(MARGIN, y)
        layout.draw(canvas)
        canvas.restore()

        if (!signatureBase64.isNullOrBlank()) {
            runCatching {
                val clean = signatureBase64.substringAfter("base64,", signatureBase64)
                val bytes = Base64.decode(clean, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, 140, 48, true)
                    val x = if (isRtl) PAGE_WIDTH - MARGIN - 140f else MARGIN
                    canvas.drawBitmap(scaled, x, y + layout.height + 6f, null)
                }
            }
        }
    }

    private fun savePdfFile(context: Context, name: String, doc: PdfDocument): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, name)
        FileOutputStream(file).use { doc.writeTo(it) }
        return file
    }

    private fun sharePdfFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
