package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.model.*
import com.example.ui.WmsStrings
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PendingLongBarcodePrompt
import com.example.ui.viewmodel.ScanFeedbackBanner

@Composable
fun ActiveAuditScreen(
    isRtl: Boolean,
    settings: AppSettings,
    activeSession: ActiveInvoiceSession?,
    masterItems: List<MasterInvoiceItem>,
    completedInvoices: List<CompletedInvoiceRecord>,
    scanBanner: ScanFeedbackBanner?,
    pendingLongBarcode: PendingLongBarcodePrompt?,
    onDismissBanner: () -> Unit,
    onResolveLongBarcode: (Boolean) -> Unit,
    onOpenInvoice: (String) -> Unit,
    onProcessScan: (String) -> Unit,
    onAdjustQty: (String, Int) -> Unit,
    onSuspendSession: () -> Unit,
    onFinalizeSession: (String?) -> Unit,
    onReopenCompleted: (String) -> Unit,
    onChangeSortMode: (ItemSortMode) -> Unit
) {
    val s = WmsStrings(isRtl)
    var scanInput by remember { mutableStateOf("") }
    var showCameraModal by remember { mutableStateOf(false) }
    var showSignatureModal by remember { mutableStateOf(false) }

    val availableInvoices by remember(masterItems, completedInvoices) {
        derivedStateOf {
            val completedSet = completedInvoices.map { it.invoiceNo }.toSet()
            masterItems.map { it.invoiceNo }.distinct().filter { it !in completedSet }
        }
    }

    val totalReq by remember(activeSession) {
        derivedStateOf { activeSession?.items?.values?.sumOf { it.requiredQty } ?: 0 }
    }
    val totalScan by remember(activeSession) {
        derivedStateOf { activeSession?.items?.values?.sumOf { it.actualQty } ?: 0 }
    }
    val progress by remember {
        derivedStateOf {
            if (totalReq > 0) (totalScan.toFloat() / totalReq.toFloat()).coerceIn(0f, 1f) else 0f
        }
    }
    val sortedEntries by remember(activeSession, settings.itemSortMode) {
        derivedStateOf {
            val sessionItems = activeSession?.items?.entries?.toList().orEmpty()
            when (settings.itemSortMode) {
                ItemSortMode.LAST_SCANNED -> sessionItems.sortedByDescending { it.value.lastScannedAt }
                ItemSortMode.ORIGINAL_ORDER -> sessionItems.sortedBy { it.value.originalIndex }
                ItemSortMode.PENDING_FIRST -> sessionItems.sortedBy { it.value.qtyStatus == QtyStatus.EXACT }
                ItemSortMode.ERRORS_FIRST -> sessionItems.sortedBy {
                    it.value.codeStatus == CodeStatus.MATCH && it.value.qtyStatus == QtyStatus.EXACT
                }
            }
        }
    }

    if (showCameraModal) {
        CameraQrScannerModal(
            isRtl = isRtl,
            onDismiss = { showCameraModal = false },
            onBarcodeScanned = { code ->
                showCameraModal = false
                onProcessScan(code)
            }
        )
    }

    if (showSignatureModal && activeSession != null) {
        AuditorSignatureModal(
            isRtl = isRtl,
            auditorName = settings.auditorName,
            onDismiss = { showSignatureModal = false },
            onSignatureSaved = { b64 ->
                showSignatureModal = false
                onFinalizeSession(b64)
            }
        )
    }

    if (pendingLongBarcode != null) {
        LongBarcodePolicyDialog(
            prompt = pendingLongBarcode,
            isRtl = isRtl,
            onDecision = onResolveLongBarcode
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "audit_scanner_header") {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = Slate900,
                border = BorderStroke(1.dp, Emerald500.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = scanInput,
                            onValueChange = { scanInput = it },
                            label = {
                                Text(
                                    if (activeSession == null)
                                        s.t("امسح رقم الفاتورة أو أدخله", "Scan or type Invoice No.")
                                    else
                                        s.t("امسح باركود الصنف", "Scan item barcode")
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (scanInput.isNotBlank()) {
                                        onProcessScan(scanInput)
                                        scanInput = ""
                                    }
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("audit_barcode_input")
                        )
                        FilledIconButton(
                            onClick = {
                                if (scanInput.isNotBlank()) {
                                    onProcessScan(scanInput)
                                    scanInput = ""
                                }
                            },
                            modifier = Modifier
                                .size(52.dp)
                                .testTag("audit_submit_scan_button"),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Emerald500, contentColor = Slate950)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Submit Scan")
                        }
                        if (settings.enableCameraQr) {
                            FilledIconButton(
                                onClick = { showCameraModal = true },
                                modifier = Modifier
                                    .size(52.dp)
                                    .testTag("audit_camera_scan_button"),
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Indigo500, contentColor = Slate100)
                            ) {
                                Icon(Icons.Rounded.PhotoCamera, contentDescription = "Open Camera Scanner")
                            }
                        }
                    }

                    if (scanBanner != null) {
                        ScanBannerCard(
                            banner = scanBanner,
                            isRtl = isRtl,
                            onDismiss = onDismissBanner
                        )
                    }
                }
            }
        }

        if (activeSession == null) {
            item(key = "audit_ready_header") {
                SectionHeaderRow(
                    title = s.t("فواتير جاهزة للتدقيق", "Invoices Ready for Audit"),
                    subtitle = s.t("${availableInvoices.size} فاتورة مفتوحة", "${availableInvoices.size} pending")
                )
            }
            if (availableInvoices.isEmpty()) {
                item(key = "audit_empty_state") {
                    EmptyStatePanel(
                        icon = Icons.Rounded.TaskAlt,
                        title = s.t("تم إكمال جميع الفواتير", "All Invoices Audited"),
                        description = s.t("استورد ملف إكسيل جديد أو أعد فتح فاتورة من الأسفل.", "Import a new spreadsheet or reopen a completed invoice below.")
                    )
                }
            } else {
                items(availableInvoices, key = { "avail_$it" }) { inv ->
                    val rows = remember(masterItems, inv) { masterItems.filter { it.invoiceNo == inv } }
                    val reqSum = remember(rows) { rows.sumOf { it.requiredQty } }
                    Surface(
                        onClick = { onOpenInvoice(inv) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("open_invoice_$inv"),
                        shape = MaterialTheme.shapes.medium,
                        color = Slate900,
                        border = BorderStroke(1.dp, Slate800)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(inv, style = MonoCodeStyle, color = Emerald400)
                                Text(
                                    "${rows.size} ${s.t("أصناف", "items")} • $reqSum ${s.t("قطعة", "qty")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Slate300
                                )
                            }
                            StatusPill(s.t("فتح للتدقيق", "Start"), Emerald400)
                        }
                    }
                }
            }

            if (completedInvoices.isNotEmpty()) {
                item(key = "audit_completed_header") {
                    SectionHeaderRow(
                        title = s.t("الفواتير المكتملة المقفلة", "Completed & Locked Invoices"),
                        subtitle = s.t("${completedInvoices.size} فاتورة", "${completedInvoices.size} completed")
                    )
                }
                items(completedInvoices.take(10), key = { "comp_${it.invoiceNo}" }) { comp ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = Slate900,
                        border = BorderStroke(1.dp, Slate800)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(comp.invoiceNo, style = MonoCodeStyle, color = Slate200)
                                Text("${comp.totalItems} ${s.t("صنف", "items")} • ${comp.totalQty} ${s.t("كمية", "qty")}", style = MaterialTheme.typography.bodySmall, color = Slate400)
                            }
                            OutlinedButton(
                                onClick = { onReopenCompleted(comp.invoiceNo) },
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("reopen_invoice_${comp.invoiceNo}")
                            ) {
                                Icon(Icons.Rounded.LockOpen, contentDescription = null, tint = Amber400)
                                Spacer(Modifier.width(4.dp))
                                Text(s.t("إعادة فتح", "Reopen"), color = Amber400)
                            }
                        }
                    }
                }
            }
        } else {
            item(key = "active_session_summary") {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = Slate900,
                    border = BorderStroke(1.dp, Slate700)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${s.t("فاتورة نشطة:", "Active Invoice:")} ${activeSession.invoiceNo}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Emerald400
                                )
                                Text(
                                    "${s.t("الطلب:", "Order:")} ${activeSession.orderNo ?: "-"} • ${sortedEntries.size} ${s.t("صنف", "SKUs")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Slate400
                                )
                            }
                            Text("$totalScan / $totalReq", style = MonoMetricStyle, color = Slate100)
                        }

                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            color = Emerald500,
                            trackColor = Slate800
                        )

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(ItemSortMode.entries, key = { it.name }) { mode ->
                                val label = when (mode) {
                                    ItemSortMode.LAST_SCANNED -> s.t("الأحدث مسحاً", "Recent")
                                    ItemSortMode.ORIGINAL_ORDER -> s.t("الترتيب الأصلي", "Original")
                                    ItemSortMode.PENDING_FIRST -> s.t("المتبقي أولاً", "Pending")
                                    ItemSortMode.ERRORS_FIRST -> s.t("الفروقات أولاً", "Errors")
                                }
                                FilterChip(
                                    selected = settings.itemSortMode == mode,
                                    onClick = { onChangeSortMode(mode) },
                                    label = { Text(label) }
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onSuspendSession,
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("audit_suspend_button")
                            ) {
                                Icon(Icons.Rounded.PauseCircle, contentDescription = null, tint = Amber400)
                                Spacer(Modifier.width(6.dp))
                                Text(s.t("تعليق الفاتورة", "Hold"), color = Amber400)
                            }
                            Button(
                                onClick = { showSignatureModal = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("audit_complete_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Slate950)
                            ) {
                                Icon(Icons.Rounded.Verified, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(s.t("اعتماد وإقفال", "Sign & Close"))
                            }
                        }
                    }
                }
            }

            items(sortedEntries, key = { it.key }) { (key, item) ->
                val accent = when {
                    item.codeStatus == CodeStatus.MISMATCH -> Rose400
                    item.qtyStatus == QtyStatus.EXACT -> Emerald400
                    item.qtyStatus == QtyStatus.SURPLUS -> Amber400
                    else -> Cyan400
                }
                val isLast = activeSession.lastScannedItemCode == key
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = if (isLast) accent.copy(alpha = 0.11f) else Slate900,
                    border = BorderStroke(if (isLast) 1.5.dp else 1.dp, accent.copy(alpha = 0.55f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(item.itemName, style = MaterialTheme.typography.titleSmall, color = Slate100)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(item.itemCode, style = MonoCodeStyle, color = accent)
                                StatusPill(item.unit, Slate300)
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            IconButton(
                                onClick = { onAdjustQty(key, -1) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("audit_dec_${item.itemCode}")
                            ) {
                                Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = "Decrease", tint = Slate300)
                            }
                            Text(
                                text = "${item.actualQty}/${item.requiredQty}",
                                style = MonoMetricStyle,
                                color = accent
                            )
                            IconButton(
                                onClick = { onAdjustQty(key, +1) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("audit_inc_${item.itemCode}")
                            ) {
                                Icon(Icons.Rounded.AddCircleOutline, contentDescription = "Increase", tint = Emerald400)
                            }
                        }
                    }
                }
            }
        }
    }
}
