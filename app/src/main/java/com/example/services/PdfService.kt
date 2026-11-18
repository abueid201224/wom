package com.example.services

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
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

    fun exportDiscrepancyAuditPdf(
        context: Context,
        auditorName: String,
        auditorId: String,
        signatureBase64: String?,
        discrepancies: List<AuditDiscrepancy>,
        wrongPicks: List<WrongPickingRecord>,
        completedInvoices: List<CompletedInvoiceRecord>
    ): Result<File> {
        return runCatching {
            val doc = PdfDocument()
            var pageNum = 1
            var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
            var canvas = page.canvas
            var y = drawHeader(
                canvas,
                "ISA 500 Audit & Discrepancy Report",
                "Auditor: $auditorName ($auditorId)",
                "Completed: ${completedInvoices.size} | Discrepancies: ${discrepancies.size} | Wrong Picks: ${wrongPicks.size}"
            )

            val rowPaint = Paint().apply { color = Color.DKGRAY; textSize = 10f }
            val headerPaint = Paint().apply {
                color = Color.rgb(15, 23, 42)
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
            }

            canvas.drawText("Invoice | Code | Item | Req | Act | Diff | Status", MARGIN, y, headerPaint)
            y += 16f

            for (d in discrepancies) {
                if (y > PAGE_HEIGHT - 90f) {
                    doc.finishPage(page)
                    pageNum++
                    page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
                    canvas = page.canvas
                    y = MARGIN + 20f
                }
                val line = "${d.invoiceNo} | ${d.itemCode} | ${d.itemName.take(24)} | ${d.requiredQty} | ${d.actualQty} | ${d.difference} | ${d.qtyStatus.name}"
                canvas.drawText(line, MARGIN, y, rowPaint)
                y += 14f
            }

            if (wrongPicks.isNotEmpty()) {
                y += 14f
                canvas.drawText("Wrong-Picking Cross-Invoice Alerts (${wrongPicks.size}):", MARGIN, y, headerPaint)
                y += 16f
                for (w in wrongPicks) {
                    if (y > PAGE_HEIGHT - 90f) {
                        doc.finishPage(page)
                        pageNum++
                        page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
                        canvas = page.canvas
                        y = MARGIN + 20f
                    }
                    val line = "${w.activeInvoiceNo} <- ${w.itemCode} (Belongs: ${w.actualBelongingInvoiceNo ?: "UNKNOWN"})"
                    canvas.drawText(line, MARGIN, y, rowPaint)
                    y += 14f
                }
            }

            drawSignatureBlock(canvas, y + 20f, auditorName, signatureBase64)
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
                "RMA Return & Inspection Receipt",
                "Receipt: ${report.returnReceiptNo} | Invoice: ${report.originalInvoiceNo}",
                "Customer: ${report.customerName} | Status: ${report.status.name} | Refund: ${report.totalRefundAmount}"
            )

            val headerPaint = Paint().apply {
                color = Color.rgb(15, 23, 42)
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
            }
            val rowPaint = Paint().apply { color = Color.DKGRAY; textSize = 10f }

            canvas.drawText("Code | Item | RetQty | Condition | Lab Decision | Refund", MARGIN, y, headerPaint)
            y += 16f

            for (item in report.items.take(36)) {
                val line = "${item.itemCode} | ${item.itemName.take(22)} | ${item.actualReturnedQty} | ${item.condition.name} | ${item.labDecision.name} | ${item.refundTotal}"
                canvas.drawText(line, MARGIN, y, rowPaint)
                y += 14f
            }

            drawSignatureBlock(canvas, minOf(y + 24f, PAGE_HEIGHT - 110f), report.auditorName, report.auditorSignature)
            doc.finishPage(page)

            val outFile = savePdfFile(context, "RMA_${report.returnReceiptNo}_${System.currentTimeMillis()}.pdf", doc)
            doc.close()
            sharePdfFile(context, outFile)
            outFile
        }
    }

    private fun drawHeader(
        canvas: Canvas,
        title: String,
        subtitle1: String,
        subtitle2: String
    ): Float {
        val bgPaint = Paint().apply { color = Color.rgb(15, 23, 42) }
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 96f, bgPaint)

        val titlePaint = Paint().apply {
            color = Color.rgb(52, 211, 153)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        }
        val subPaint = Paint().apply {
            color = Color.WHITE
            textSize = 10.5f
        }
        canvas.drawText(title, MARGIN, 34f, titlePaint)
        canvas.drawText(subtitle1, MARGIN, 56f, subPaint)
        canvas.drawText(subtitle2, MARGIN, 76f, subPaint)
        return 122f
    }

    private fun drawSignatureBlock(
        canvas: Canvas,
        startY: Float,
        auditorName: String,
        signatureBase64: String?
    ) {
        val y = startY.coerceAtMost(PAGE_HEIGHT - 95f)
        val labelPaint = Paint().apply { color = Color.DKGRAY; textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("Auditor Signature: $auditorName", MARGIN, y, labelPaint)
        if (!signatureBase64.isNullOrBlank()) {
            runCatching {
                val clean = signatureBase64.substringAfter("base64,", signatureBase64)
                val bytes = Base64.decode(clean, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, 140, 48, true)
                    canvas.drawBitmap(scaled, MARGIN, y + 8f, null)
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
