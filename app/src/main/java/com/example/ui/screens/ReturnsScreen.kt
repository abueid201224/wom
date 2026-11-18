package com.example.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AssignmentReturn
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.model.*
import com.example.ui.WmsStrings
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PdfExtractionUiState
import java.time.Instant
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReturnsScreen(
    isRtl: Boolean,
    currentUser: AppUser,
    canApproveLab: Boolean,
    returnReports: List<ReturnReport>,
    masterItems: List<MasterInvoiceItem>,
    pdfExtractionState: PdfExtractionUiState,
    isCloudBusy: Boolean,
    onImportOriginalPdf: (Uri, String) -> Unit,
    onSyncMasterInvoice: (String) -> Unit,
    onRestoreReturnsFromCloud: () -> Unit,
    onTogglePdfItem: (String) -> Unit,
    onSelectAllPdfItems: (Boolean) -> Unit,
    onUpdatePdfItemReturnQty: (String, Int) -> Unit,
    onUpdatePdfItemPrice: (String, Double) -> Unit,
    onUpdatePdfItemCondition: (String, ReturnItemCondition) -> Unit,
    onClearExtractedPdf: () -> Unit,
    onSaveReturnReport: (ReturnReport) -> Unit,
    onLabDecision: (reportId: String, decision: LabDecision, notes: String) -> Unit,
    onExportPdf: (ReturnReport) -> Unit,
    onDeleteReturnReport: (String) -> Unit
) {
    val s = WmsStrings(isRtl)
    val context = LocalContext.current

    var invoiceNo by remember { mutableStateOf("") }
    var customerName by remember { mutableStateOf("") }
    var itemCode by remember { mutableStateOf("") }
    var itemName by remember { mutableStateOf("") }
    var itemQty by remember { mutableStateOf("1") }
    var itemPrice by remember { mutableStateOf("50.0") }
    var selectedCondition by remember { mutableStateOf(ReturnItemCondition.VALID_FOR_RESTOCK) }
    var showSignature by remember { mutableStateOf(false) }
    var showMasterInvoiceDialog by remember { mutableStateOf(false) }
    var masterInvoiceQuery by remember { mutableStateOf("") }

    val draftItems = remember { mutableStateListOf<ReturnSessionItem>() }
    val draftReturnedTotal by remember { derivedStateOf { draftItems.sumOf { it.actualReturnedQty } } }
    val draftRefundTotal by remember { derivedStateOf { draftItems.sumOf { it.refundTotal } } }

    val distinctMasterInvoices by remember(masterItems, masterInvoiceQuery) {
        derivedStateOf {
            masterItems
                .groupBy { it.invoiceNo }
                .map { (inv, rows) -> inv to rows.size }
                .filter { (inv, _) ->
                    masterInvoiceQuery.isBlank() || inv.contains(masterInvoiceQuery.trim(), ignoreCase = true)
                }
        }
    }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            var displayName = "Invoice_Original.pdf"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && cursor.moveToFirst()) {
                    displayName = cursor.getString(col) ?: displayName
                }
            }
            onImportOriginalPdf(uri, displayName)
        }
    }

    if (showMasterInvoiceDialog) {
        AlertDialog(
            onDismissRequest = { showMasterInvoiceDialog = false },
            containerColor = Slate900,
            titleContentColor = Amber400,
            textContentColor = Slate200,
            title = {
                Text(s.t("مزامنة فاتورة أصلية من القاعدة", "Sync Original Invoice from Master DB"))
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = masterInvoiceQuery,
                        onValueChange = { masterInvoiceQuery = it },
                        label = { Text(s.t("بحث برقم الفاتورة", "Search Invoice No.")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (distinctMasterInvoices.isEmpty()) {
                        Text(
                            s.t(
                                "لا توجد فواتير متاحة في القاعدة الرئيسية. استورد ملف الإكسيل أولاً.",
                                "No invoices available in Master DB. Import master spreadsheet first."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate400
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            distinctMasterInvoices.take(8).forEach { (inv, count) ->
                                Surface(
                                    onClick = {
                                        showMasterInvoiceDialog = false
                                        onSyncMasterInvoice(inv)
                                    },
                                    shape = MaterialTheme.shapes.small,
                                    color = Slate950,
                                    border = BorderStroke(1.dp, Slate700),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("sync_master_inv_$inv")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(inv, style = MonoCodeStyle, color = Amber400)
                                        Text(
                                            s.t("$count صنف", "$count items"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Slate300
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showMasterInvoiceDialog = false },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(s.t("إغلاق", "Close"), color = Slate300)
                }
            }
        )
    }

    if (showSignature) {
        AuditorSignatureModal(
            isRtl = isRtl,
            auditorName = currentUser.name,
            onDismiss = { showSignature = false },
            onSignatureSaved = { b64 ->
                showSignature = false
                val hasLab = draftItems.any { it.condition == ReturnItemCondition.TRANSFERRED_TO_LAB }
                val report = ReturnReport(
                    id = "ret-${UUID.randomUUID().toString().take(8)}",
                    returnReceiptNo = "RMA-${(1000..9999).random()}",
                    originalInvoiceNo = invoiceNo.ifBlank { "20450101" },
                    customerName = customerName.ifBlank { s.t("عميل نقدي", "Cash Customer") },
                    createdAt = Instant.now().toString(),
                    auditorName = currentUser.name,
                    auditorId = currentUser.jobId,
                    auditorSignature = b64,
                    status = if (hasLab) ReturnReportStatus.PENDING_LAB else ReturnReportStatus.COMPLETED,
                    items = draftItems.toList(),
                    totalInvoicedQty = draftItems.sumOf { it.invoicedQty.coerceAtLeast(it.actualReturnedQty) },
                    totalReturnedQty = draftItems.sumOf { it.actualReturnedQty },
                    totalValidForRestockQty = draftItems.filter { it.condition == ReturnItemCondition.VALID_FOR_RESTOCK }.sumOf { it.actualReturnedQty },
                    totalTransferredToLabQty = draftItems.filter { it.condition == ReturnItemCondition.TRANSFERRED_TO_LAB }.sumOf { it.actualReturnedQty },
                    totalRefundAmount = draftItems.sumOf { it.refundTotal }
                )
                onSaveReturnReport(report)
                draftItems.clear()
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "returns_pdf_ocr_import_card") {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = Slate900,
                border = BorderStroke(1.dp, Cyan500.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        s.t("استيراد ومزامنة الفاتورة الأصلية (PDF / RTL OCR)", "Original Invoice PDF Import & Sync"),
                        style = MaterialTheme.typography.titleMedium,
                        color = Cyan400
                    )
                    Text(
                        s.t(
                            "استخلص بنود الفاتورة وكمياتها وأسعارها تلقائياً مع دعم العربية RTL وافتحها لاختيار المرتجع",
                            "Extract invoice lines, quantities & prices with Arabic RTL OCR and pick items to return"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { pdfPicker.launch(arrayOf("application/pdf")) },
                            enabled = pdfExtractionState !is PdfExtractionUiState.Loading,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("returns_import_pdf_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan500, contentColor = Slate950)
                        ) {
                            Icon(Icons.Rounded.DocumentScanner, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(s.t("استيراد فاتورة PDF (OCR عربي)", "Import Invoice PDF (Arabic OCR)"))
                        }

                        OutlinedButton(
                            onClick = { showMasterInvoiceDialog = true },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("returns_sync_master_invoice_button"),
                            border = BorderStroke(1.dp, Amber500.copy(alpha = 0.55f))
                        ) {
                            Icon(Icons.Rounded.Sync, contentDescription = null, tint = Amber400)
                            Spacer(Modifier.width(6.dp))
                            Text(s.t("مزامنة فاتورة من القاعدة", "Sync Master Invoice"), color = Slate100)
                        }

                        OutlinedButton(
                            onClick = onRestoreReturnsFromCloud,
                            enabled = !isCloudBusy,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("returns_sync_cloud_button"),
                            border = BorderStroke(1.dp, Indigo500.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = Indigo400)
                            Spacer(Modifier.width(6.dp))
                            Text(s.t("مزامنة السحابة", "Cloud Sync"), color = Slate100)
                        }
                    }

                    when (pdfExtractionState) {
                        is PdfExtractionUiState.Loading -> {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Cyan400,
                                    trackColor = Slate800
                                )
                                Text(
                                    s.t(
                                        "جاري استخلاص عناصر الفاتورة والكميات والأسعار مع مراعاة RTL و OCR العربي...",
                                        "Extracting invoice items, quantities & prices via Arabic RTL OCR..."
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Cyan400
                                )
                            }
                        }

                        is PdfExtractionUiState.Error -> {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = Rose950,
                                border = BorderStroke(1.dp, Rose500.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = pdfExtractionState.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Rose400,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = onClearExtractedPdf,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Dismiss Error", tint = Rose400)
                                    }
                                }
                            }
                        }

                        else -> Unit
                    }
                }
            }
        }

        if (pdfExtractionState is PdfExtractionUiState.Success) {
            val extracted = pdfExtractionState.result
            val selectedItems = extracted.items.filter { it.isSelected }
            val allSelected = extracted.items.isNotEmpty() && selectedItems.size == extracted.items.size
            val selectedTotalRefund = selectedItems.sumOf { it.returnQty * it.unitPrice }

            item(key = "returns_extracted_header") {
                CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = Slate900,
                        border = BorderStroke(1.5.dp, Emerald500.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = s.t(
                                            "عناصر الفاتورة المستخلصة (${extracted.items.size})",
                                            "Extracted Invoice Items (${extracted.items.size})"
                                        ),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Emerald400
                                    )
                                    Text(
                                        text = "${s.t("الفاتورة:", "Inv:")} ${extracted.invoiceNo.ifBlank { "-" }}" +
                                            if (extracted.customerName.isNotBlank()) " • ${extracted.customerName}" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Slate200
                                    )
                                    Text(
                                        text = extracted.sourceLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Slate400
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusPill(
                                        label = when (extracted.extractionEngine) {
                                            "GEMINI_RTL_OCR" -> s.t("OCR عربي RTL", "Arabic RTL OCR")
                                            "MASTER_SYNC" -> s.t("مزامنة القاعدة", "Master Sync")
                                            else -> s.t("استخلاص محلي", "Local Match")
                                        },
                                        color = Cyan400
                                    )
                                    IconButton(
                                        onClick = onClearExtractedPdf,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Close Extracted Invoice", tint = Slate400)
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = allSelected,
                                        onCheckedChange = { onSelectAllPdfItems(!allSelected) },
                                        modifier = Modifier.testTag("returns_select_all_pdf_items")
                                    )
                                    Text(
                                        s.t("تحديد الكل", "Select All"),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Slate100
                                    )
                                }
                                Text(
                                    text = "${s.t("المحدد:", "Selected:")} ${selectedItems.size} • ${String.format(Locale.US, "%.2f", selectedTotalRefund)} ${extracted.currency}",
                                    style = MonoCodeStyle,
                                    color = Emerald400
                                )
                            }
                        }
                    }
                }
            }

            items(extracted.items, key = { "ext_${it.id}" }) { extItem ->
                CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    var priceText by remember(extItem.id, extItem.unitPrice) {
                        mutableStateOf(
                            if (extItem.unitPrice == 0.0) "0"
                            else if (extItem.unitPrice % 1.0 == 0.0) extItem.unitPrice.toInt().toString()
                            else String.format(Locale.US, "%.2f", extItem.unitPrice)
                        )
                    }
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (extItem.isSelected) Slate900 else Slate950,
                        border = BorderStroke(
                            1.dp,
                            if (extItem.isSelected) Emerald500.copy(alpha = 0.45f) else Slate800
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Checkbox(
                                    checked = extItem.isSelected,
                                    onCheckedChange = { onTogglePdfItem(extItem.id) },
                                    modifier = Modifier.testTag("returns_pdf_item_check_${extItem.id}")
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = extItem.itemName,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            textDirection = TextDirection.Content
                                        ),
                                        color = Slate100,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${extItem.itemCode} • ${extItem.unit} • ${s.t("الكمية الأصلية:", "Invoiced:")} ${extItem.invoicedQty}",
                                        style = MonoCodeStyle,
                                        color = Cyan400
                                    )
                                }
                                StatusPill(
                                    label = "${String.format(Locale.US, "%.2f", extItem.returnQty * extItem.unitPrice)} ${extracted.currency}",
                                    color = if (extItem.isSelected) Emerald400 else Slate400
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = Slate950,
                                    border = BorderStroke(1.dp, Slate700)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { onUpdatePdfItemReturnQty(extItem.id, extItem.returnQty - 1) },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(Icons.Rounded.Remove, contentDescription = "Decrease Return Qty", tint = Rose400)
                                        }
                                        Text(
                                            text = "${s.t("مرتجع:", "Ret:")} ${extItem.returnQty}",
                                            style = MonoCodeStyle,
                                            color = Slate100
                                        )
                                        IconButton(
                                            onClick = { onUpdatePdfItemReturnQty(extItem.id, extItem.returnQty + 1) },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(Icons.Rounded.Add, contentDescription = "Increase Return Qty", tint = Emerald400)
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = priceText,
                                    onValueChange = { raw ->
                                        val cleaned = raw.filter { it.isDigit() || it == '.' }
                                        priceText = cleaned
                                        cleaned.toDoubleOrNull()?.let { parsed ->
                                            onUpdatePdfItemPrice(extItem.id, parsed)
                                        }
                                    },
                                    label = { Text(s.t("السعر", "Price")) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = extItem.condition == ReturnItemCondition.VALID_FOR_RESTOCK,
                                    onClick = { onUpdatePdfItemCondition(extItem.id, ReturnItemCondition.VALID_FOR_RESTOCK) },
                                    label = { Text(s.t("صالح للمخزون", "Restock")) }
                                )
                                FilterChip(
                                    selected = extItem.condition == ReturnItemCondition.TRANSFERRED_TO_LAB,
                                    onClick = { onUpdatePdfItemCondition(extItem.id, ReturnItemCondition.TRANSFERRED_TO_LAB) },
                                    label = { Text(s.t("تحويل للمختبر", "Send to Lab")) }
                                )
                            }
                        }
                    }
                }
            }

            item(key = "returns_extracted_transfer_cta") {
                Button(
                    onClick = {
                        if (selectedItems.isNotEmpty()) {
                            if (invoiceNo.isBlank() && extracted.invoiceNo.isNotBlank()) {
                                invoiceNo = extracted.invoiceNo
                            }
                            if (customerName.isBlank() && extracted.customerName.isNotBlank()) {
                                customerName = extracted.customerName
                            }
                            selectedItems.forEach { sel ->
                                draftItems.add(
                                    ReturnSessionItem(
                                        id = "retitem-${UUID.randomUUID().toString().take(8)}",
                                        itemCode = sel.itemCode,
                                        itemName = sel.itemName,
                                        unit = sel.unit,
                                        invoiceNo = extracted.invoiceNo.ifBlank { null },
                                        sourceFile = extracted.sourceLabel,
                                        invoicedQty = sel.invoicedQty,
                                        subtotal = sel.lineTotal,
                                        actualReturnedQty = sel.returnQty,
                                        unitPrice = sel.unitPrice,
                                        refundTotal = sel.returnQty * sel.unitPrice,
                                        condition = sel.condition
                                    )
                                )
                            }
                            onClearExtractedPdf()
                        }
                    },
                    enabled = selectedItems.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 50.dp)
                        .testTag("returns_transfer_selected_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Slate950)
                ) {
                    Icon(Icons.Rounded.Checklist, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        s.t(
                            "إضافة العناصر المختارة لتذكرة المرتجع (${selectedItems.size})",
                            "Add Selected Items to RMA (${selectedItems.size})"
                        )
                    )
                }
            }
        }

        item(key = "returns_entry_form") {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = Slate900,
                border = BorderStroke(1.dp, Amber500.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        s.t("إنشاء تذكرة مرتجع وفحص (RMA)", "New RMA Return Inspection"),
                        style = MaterialTheme.typography.titleMedium,
                        color = Amber400
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = invoiceNo,
                            onValueChange = { invoiceNo = it },
                            label = { Text(s.t("رقم الفاتورة", "Invoice No.")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = customerName,
                            onValueChange = { customerName = it },
                            label = { Text(s.t("اسم العميل", "Customer")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = itemCode,
                            onValueChange = { itemCode = it },
                            label = { Text(s.t("باركود الصنف", "Item Code")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = itemQty,
                            onValueChange = { itemQty = it.filter(Char::isDigit) },
                            label = { Text(s.t("الكمية", "Qty")) },
                            singleLine = true,
                            modifier = Modifier.width(80.dp)
                        )
                        OutlinedTextField(
                            value = itemPrice,
                            onValueChange = { itemPrice = it },
                            label = { Text(s.t("السعر", "Price")) },
                            singleLine = true,
                            modifier = Modifier.width(90.dp)
                        )
                    }
                    OutlinedTextField(
                        value = itemName,
                        onValueChange = { itemName = it },
                        label = { Text(s.t("اسم الصنف (اختياري)", "Item Name")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedCondition == ReturnItemCondition.VALID_FOR_RESTOCK,
                            onClick = { selectedCondition = ReturnItemCondition.VALID_FOR_RESTOCK },
                            label = { Text(s.t("صالح للمخزون", "Restock")) }
                        )
                        FilterChip(
                            selected = selectedCondition == ReturnItemCondition.TRANSFERRED_TO_LAB,
                            onClick = { selectedCondition = ReturnItemCondition.TRANSFERRED_TO_LAB },
                            label = { Text(s.t("تحويل للمختبر", "Send to Lab")) }
                        )
                    }

                    Button(
                        onClick = {
                            if (itemCode.isNotBlank()) {
                                val qty = itemQty.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                val price = itemPrice.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                                draftItems.add(
                                    ReturnSessionItem(
                                        id = "retitem-${UUID.randomUUID().toString().take(8)}",
                                        itemCode = itemCode.trim(),
                                        itemName = itemName.ifBlank { itemCode.trim() },
                                        actualReturnedQty = qty,
                                        unitPrice = price,
                                        refundTotal = qty * price,
                                        condition = selectedCondition
                                    )
                                )
                                itemCode = ""
                                itemName = ""
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("returns_add_item_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Slate950)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(s.t("إضافة الصنف لتذكرة المرتجع", "Add Item to RMA"))
                    }
                }
            }
        }

        if (draftItems.isNotEmpty()) {
            items(draftItems, key = { it.id }) { item ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = Slate900,
                    border = BorderStroke(1.dp, Slate800)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.itemName, style = MaterialTheme.typography.titleSmall, color = Slate100)
                            Text(
                                "${item.itemCode} • ${item.actualReturnedQty} ${item.unit} • ${String.format(Locale.US, "%.2f", item.refundTotal)}",
                                style = MonoCodeStyle,
                                color = Slate300
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(
                                label = if (item.condition == ReturnItemCondition.TRANSFERRED_TO_LAB) "LAB" else "RESTOCK",
                                color = if (item.condition == ReturnItemCondition.TRANSFERRED_TO_LAB) Amber400 else Emerald400
                            )
                            IconButton(
                                onClick = { draftItems.removeAll { it.id == item.id } },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "Remove Return Item", tint = Rose400)
                            }
                        }
                    }
                }
            }
            item(key = "returns_save_footer") {
                Text(
                    text = "${s.t("إجمالي المرتجع:", "Total Qty:")} $draftReturnedTotal • ${s.t("الاسترداد:", "Refund:")} ${String.format(Locale.US, "%.2f", draftRefundTotal)}",
                    style = MonoCodeStyle,
                    color = Slate300
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { showSignature = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 50.dp)
                        .testTag("returns_sign_save_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Slate950)
                ) {
                    Icon(Icons.Rounded.Draw, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(s.t("توقيع وحفظ إيصال المرتجع", "Sign & Save RMA Receipt"))
                }
            }
        }

        item(key = "returns_reports_header") {
            SectionHeaderRow(
                title = s.t("تقارير المرتجعات وتحكيم المختبر", "RMA Reports & Lab Arbitration"),
                subtitle = s.t("${returnReports.size} تقرير", "${returnReports.size} reports")
            )
        }

        if (returnReports.isEmpty()) {
            item(key = "returns_empty_state") {
                EmptyStatePanel(
                    icon = Icons.AutoMirrored.Rounded.AssignmentReturn,
                    title = s.t("لا توجد تقارير مرتجعات بعد", "No Return Reports Yet"),
                    description = s.t(
                        "استورد فاتورة PDF أو أضف أصناف المرتجع أعلاه ثم احفظ الإيصال.",
                        "Import an invoice PDF or add return items above and save a signed RMA ticket."
                    )
                )
            }
        } else {
            items(returnReports, key = { it.id }) { report ->
                val badgeColor = if (report.status == ReturnReportStatus.PENDING_LAB) Amber400 else Emerald400
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = Slate900,
                    border = BorderStroke(1.dp, Slate800)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(report.returnReceiptNo, style = MonoCodeStyle, color = Amber400)
                                Text("${report.customerName} • ${report.originalInvoiceNo}", style = MaterialTheme.typography.bodySmall, color = Slate300)
                            }
                            StatusPill(report.status.name, badgeColor)
                        }
                        Text(
                            "${s.t("الكمية المرتجعة:", "Returned:")} ${report.totalReturnedQty} | ${s.t("الاسترداد:", "Refund:")} ${String.format(Locale.US, "%.2f", report.totalRefundAmount)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate400
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onExportPdf(report) },
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("return_pdf_${report.id}")
                            ) {
                                Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = Rose400)
                                Spacer(Modifier.width(4.dp))
                                Text("PDF", color = Slate100)
                            }
                            if (report.status == ReturnReportStatus.PENDING_LAB && canApproveLab) {
                                Button(
                                    onClick = { onLabDecision(report.id, LabDecision.APPROVED_FOR_RESTOCK, "Approved by lab") },
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Slate950)
                                ) {
                                    Text(s.t("إعادة للمخزون", "Approve Restock"))
                                }
                            }
                            IconButton(
                                onClick = { onDeleteReturnReport(report.id) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete RMA", tint = Rose400)
                            }
                        }
                    }
                }
            }
        }
    }
}
