package com.example.services

import android.content.Context
import android.content.Intent
import android.util.Xml
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.model.InventoryCountItem
import com.example.model.MasterInvoiceItem
import com.example.model.PackagingGroupRule
import com.example.model.ReceivingSessionItem
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

object SpreadsheetService {

    fun parseMasterFile(inputStream: InputStream, fileName: String): List<MasterInvoiceItem> {
        val rows = readTabularRows(inputStream, fileName)
        if (rows.isEmpty()) return emptyList()

        val headerIndex = detectHeaderRowIndex(rows)
        val headers = rows.getOrNull(headerIndex)?.map { it.trim().lowercase() } ?: emptyList()

        val idxInvoice = findCol(headers, listOf("invoice", "فاتورة", "الفاتورة", "inv", "bill"))
        val idxOrder = findCol(headers, listOf("order", "طلب", "الطلب", "ord"))
        val idxCode = findCol(headers, listOf("code", "barcode", "sku", "كود", "الباركود", "الصنف"))
        val idxName = findCol(headers, listOf("name", "desc", "itemname", "اسم", "البيان", "وصف"))
        val idxUnit = findCol(headers, listOf("unit", "uom", "وحدة", "الوحدة"))
        val idxQty = findCol(headers, listOf("qty", "quantity", "count", "كمية", "الكمية"))

        val nowIso = Instant.now().toString()
        val result = mutableListOf<MasterInvoiceItem>()

        for (rIdx in (headerIndex + 1) until rows.size) {
            val row = rows[rIdx]
            if (row.all { it.isBlank() }) continue

            val invoiceNo = cellAt(row, if (idxInvoice >= 0) idxInvoice else 0)
            val itemCode = cellAt(row, if (idxCode >= 0) idxCode else 1)
            val itemName = cellAt(row, if (idxName >= 0) idxName else 2)
            val qtyStr = cellAt(row, if (idxQty >= 0) idxQty else if (row.size > 4) 4 else 3)
            val qty = qtyStr.toDoubleOrNull()?.roundToInt()?.coerceAtLeast(1) ?: 1

            if (invoiceNo.isBlank() || itemCode.isBlank()) continue

            val orderNo = if (idxOrder >= 0) cellAt(row, idxOrder).ifBlank { null } else null
            val unit = if (idxUnit >= 0) cellAt(row, idxUnit).ifBlank { "PCS" } else "PCS"

            result.add(
                MasterInvoiceItem(
                    orderNo = orderNo,
                    invoiceNo = invoiceNo,
                    itemCode = itemCode,
                    itemName = itemName.ifBlank { itemCode },
                    unit = unit.uppercase(),
                    requiredQty = qty,
                    importedAt = nowIso,
                    originalIndex = result.size
                )
            )
        }
        return result
    }

    fun parseReceivingPoFile(inputStream: InputStream, fileName: String): List<ReceivingSessionItem> {
        val rows = readTabularRows(inputStream, fileName)
        if (rows.isEmpty()) return emptyList()

        val headerIndex = detectHeaderRowIndex(rows)
        val headers = rows.getOrNull(headerIndex)?.map { it.trim().lowercase() } ?: emptyList()

        val idxCode = findCol(headers, listOf("code", "barcode", "sku", "كود", "الباركود", "الصنف"))
        val idxName = findCol(headers, listOf("name", "desc", "اسم", "البيان"))
        val idxUnit = findCol(headers, listOf("unit", "uom", "وحدة", "الوحدة"))
        val idxQty = findCol(headers, listOf("qty", "quantity", "expected", "كمية", "الكمية"))

        val result = mutableListOf<ReceivingSessionItem>()
        for (rIdx in (headerIndex + 1) until rows.size) {
            val row = rows[rIdx]
            if (row.all { it.isBlank() }) continue
            val itemCode = cellAt(row, if (idxCode >= 0) idxCode else 0)
            if (itemCode.isBlank()) continue
            val itemName = cellAt(row, if (idxName >= 0) idxName else 1)
            val unit = if (idxUnit >= 0) cellAt(row, idxUnit).ifBlank { "PCS" } else "PCS"
            val qtyStr = cellAt(row, if (idxQty >= 0) idxQty else if (row.size > 3) 3 else 2)
            val expected = qtyStr.toDoubleOrNull()?.roundToInt()?.coerceAtLeast(1) ?: 1

            result.add(
                ReceivingSessionItem(
                    id = "rec-${UUID.randomUUID().toString().take(8)}",
                    itemCode = itemCode,
                    itemName = itemName.ifBlank { itemCode },
                    unit = unit.uppercase(),
                    expectedQty = expected,
                    receivedQty = 0,
                    status = "SHORTAGE"
                )
            )
        }
        return result
    }

    fun parseInventoryBookFile(
        inputStream: InputStream,
        fileName: String,
        packagingRules: List<PackagingGroupRule> = emptyList()
    ): List<InventoryCountItem> {
        val rows = readTabularRows(inputStream, fileName)
        if (rows.isEmpty()) return emptyList()

        val headerIndex = detectHeaderRowIndex(rows)
        val headers = rows.getOrNull(headerIndex)?.map { it.trim().lowercase() } ?: emptyList()

        val idxCode = findCol(headers, listOf("code", "barcode", "sku", "كود", "الباركود", "الصنف", "رقم الصنف"))
        val idxName = findCol(headers, listOf("name", "desc", "itemname", "اسم", "البيان", "الوصف", "اسم الصنف"))
        val idxUnit = findCol(headers, listOf("unit", "uom", "وحدة", "الوحدة"))
        val idxBookQty = findCol(
            headers,
            listOf("book", "bookqty", "balance", "stock", "رصيد", "الرصيد", "دفتري", "دفترى", "الدفتري", "الدفترى", "qty", "quantity", "كمية", "الكمية")
        )
        val idxCartons = findCol(headers, listOf("carton", "كرتون"))
        val idxPacks = findCol(headers, listOf("box", "pack", "بوكس", "علبة"))
        val idxPieces = findCol(headers, listOf("piece", "pcs", "حبة", "قطعة"))

        val nowIso = Instant.now().toString()
        val result = mutableListOf<InventoryCountItem>()

        for (rIdx in (headerIndex + 1) until rows.size) {
            val row = rows[rIdx]
            if (row.all { it.isBlank() }) continue

            val itemCode = normalizeDigits(cellAt(row, if (idxCode >= 0) idxCode else 0)).trim()
            if (itemCode.isBlank()) continue

            val matchedRule = PackagingEngine.matchBarcodeToPackagingRule(itemCode, packagingRules)
            val rawName = cellAt(row, if (idxName >= 0) idxName else 1)
            val unit = if (idxUnit >= 0) cellAt(row, idxUnit).ifBlank { matchedRule?.unitName ?: "PCS" } else (matchedRule?.unitName ?: "PCS")

            val fallbackQtyCol = if (row.size > 3) 3 else 2
            val bookQty = parseFlexibleInt(cellAt(row, if (idxBookQty >= 0) idxBookQty else fallbackQtyCol)).coerceAtLeast(0)
            val cartons = if (idxCartons >= 0) parseFlexibleInt(cellAt(row, idxCartons)).coerceAtLeast(0) else 0
            val packs = if (idxPacks >= 0) parseFlexibleInt(cellAt(row, idxPacks)).coerceAtLeast(0) else 0
            val pieces = if (idxPieces >= 0) parseFlexibleInt(cellAt(row, idxPieces)).coerceAtLeast(0) else 0

            val cFactor = matchedRule?.cartonFactor ?: 24.0
            val pFactor = matchedRule?.packFactor ?: 6.0
            val actualQty = PackagingEngine.calculateQtyFromColumns(cartons, packs, pieces, cFactor, pFactor)
            val variance = actualQty - bookQty

            result.add(
                InventoryCountItem(
                    id = "inv-${UUID.randomUUID().toString().take(8)}",
                    itemCode = itemCode,
                    itemName = rawName.ifBlank { matchedRule?.name ?: itemCode },
                    unit = unit.uppercase(),
                    groupId = matchedRule?.id,
                    groupName = matchedRule?.name,
                    bookQty = bookQty,
                    cartonsCount = cartons,
                    cartonFactor = cFactor.roundToInt().coerceAtLeast(1),
                    packsCount = packs,
                    packFactor = pFactor.roundToInt().coerceAtLeast(1),
                    piecesCount = pieces,
                    calculatedActualQty = actualQty,
                    varianceQty = variance,
                    status = when {
                        variance == 0 -> "EXACT"
                        variance > 0 -> "SURPLUS"
                        else -> "SHORTAGE"
                    },
                    lastScannedAt = nowIso
                )
            )
        }
        return result
    }

    internal fun normalizeDigits(input: String): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in '٠'..'٩' -> sb.append(('0'.code + (ch.code - '٠'.code)).toChar())
                in '۰'..'۹' -> sb.append(('0'.code + (ch.code - '۰'.code)).toChar())
                '٫' -> sb.append('.')
                '٬' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun parseFlexibleInt(raw: String): Int {
        val cleaned = normalizeDigits(raw).replace(",", "").trim()
        return cleaned.toDoubleOrNull()?.roundToInt() ?: 0
    }

    fun exportCsvAndShare(
        context: Context,
        fileName: String,
        headers: List<String>,
        rows: List<List<String>>
    ): Result<File> {
        return runCatching {
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val safeName = if (fileName.endsWith(".csv", true)) fileName else "$fileName.csv"
            val outFile = File(exportDir, safeName)
            val sb = StringBuilder()
            sb.append('\uFEFF') // UTF-8 BOM for Arabic Excel compatibility
            sb.append(headers.joinToString(",") { escapeCsv(it) }).append("\r\n")
            for (row in rows) {
                sb.append(row.joinToString(",") { escapeCsv(it) }).append("\r\n")
            }
            outFile.writeText(sb.toString(), Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                outFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, safeName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            outFile
        }
    }

    private fun escapeCsv(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n')
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }

    private fun readTabularRows(inputStream: InputStream, fileName: String): List<List<String>> {
        val bytes = inputStream.readBytes()
        if (bytes.isEmpty()) return emptyList()
        val isZipXlsx = fileName.endsWith(".xlsx", ignoreCase = true) ||
            (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte())
        return if (isZipXlsx) parseXlsxBytes(bytes) else parseCsvText(bytes.toString(Charsets.UTF_8))
    }

    private fun parseCsvText(text: String): List<List<String>> {
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").split('\n')
        val rows = mutableListOf<List<String>>()
        for (line in lines) {
            if (line.isBlank()) continue
            val delimiter = if (line.count { it == ';' } > line.count { it == ',' }) ';' else ','
            val cells = mutableListOf<String>()
            val current = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '"') {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                } else if (c == delimiter && !inQuotes) {
                    cells.add(current.toString().trim())
                    current.clear()
                } else {
                    current.append(c)
                }
                i++
            }
            cells.add(current.toString().trim())
            rows.add(cells)
        }
        return rows
    }

    private fun parseXlsxBytes(bytes: ByteArray): List<List<String>> {
        var sharedStringsXml: ByteArray? = null
        var firstSheetXml: ByteArray? = null

        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.lowercase()
                when {
                    name == "xl/sharedstrings.xml" -> sharedStringsXml = zis.readBytes()
                    name == "xl/worksheets/sheet1.xml" -> firstSheetXml = zis.readBytes()
                    firstSheetXml == null && name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml") -> {
                        firstSheetXml = zis.readBytes()
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val sheetBytes = firstSheetXml ?: return emptyList()
        val sharedStrings = sharedStringsXml?.let { parseSharedStrings(it) } ?: emptyList()
        return parseWorksheetXml(sheetBytes, sharedStrings)
    }

    private fun parseSharedStrings(xmlBytes: ByteArray): List<String> {
        val list = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")
        var insideSi = false
        var insideT = false
        val current = StringBuilder()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "si" -> {
                            insideSi = true
                            current.clear()
                        }
                        "t" -> if (insideSi) insideT = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (insideSi && insideT) current.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "t" -> insideT = false
                        "si" -> {
                            insideSi = false
                            list.add(current.toString())
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return list
    }

    private fun parseWorksheetXml(xmlBytes: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")

        var currentRow: MutableList<Pair<Int, String>>? = null
        var currentColIdx = 0
        var currentCellType = ""
        var inValueTag = false
        var inInlineTag = false
        val textBuf = StringBuilder()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "row" -> currentRow = mutableListOf()
                        "c" -> {
                            val ref = parser.getAttributeValue(null, "r") ?: ""
                            currentColIdx = colIndexFromRef(ref, currentRow?.size ?: 0)
                            currentCellType = parser.getAttributeValue(null, "t") ?: ""
                            textBuf.clear()
                        }
                        "v" -> {
                            inValueTag = true
                            textBuf.clear()
                        }
                        "t" -> {
                            inInlineTag = true
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inValueTag || inInlineTag) textBuf.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "v" -> inValueTag = false
                        "t" -> inInlineTag = false
                        "c" -> {
                            val rawVal = textBuf.toString().trim()
                            val decoded = if (currentCellType == "s") {
                                rawVal.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: rawVal
                            } else {
                                rawVal
                            }
                            currentRow?.add(currentColIdx to decoded)
                        }
                        "row" -> {
                            currentRow?.let { entries ->
                                if (entries.isNotEmpty()) {
                                    val maxCol = entries.maxOf { it.first }
                                    val dense = MutableList(maxCol + 1) { "" }
                                    for ((col, value) in entries) {
                                        if (col in dense.indices) dense[col] = value
                                    }
                                    rows.add(dense)
                                }
                            }
                            currentRow = null
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return rows
    }

    private fun colIndexFromRef(ref: String, fallback: Int): Int {
        val letters = ref.takeWhile { it.isLetter() }.uppercase()
        if (letters.isEmpty()) return fallback
        var col = 0
        for (ch in letters) {
            col = col * 26 + (ch - 'A' + 1)
        }
        return (col - 1).coerceAtLeast(0)
    }

    private fun detectHeaderRowIndex(rows: List<List<String>>): Int {
        val keywords = listOf(
            "invoice", "فاتورة", "item", "code", "كود", "باركود",
            "qty", "كمية", "اسم", "po", "book", "رصيد", "دفتري", "دفترى", "balance"
        )
        for (i in 0 until minOf(5, rows.size)) {
            val joined = rows[i].joinToString(" ").lowercase()
            if (keywords.any { joined.contains(it) }) return i
        }
        return 0
    }

    private fun findCol(headers: List<String>, candidates: List<String>): Int {
        return headers.indexOfFirst { h -> candidates.any { h.contains(it) } }
    }

    private fun cellAt(row: List<String>, idx: Int): String {
        return if (idx in row.indices) row[idx].trim() else ""
    }
}
