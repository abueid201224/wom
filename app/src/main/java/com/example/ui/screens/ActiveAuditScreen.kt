package com.example.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.model.*
import com.example.ui.WmsStrings
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PendingLongBarcodePrompt
import com.example.ui.viewmodel.ScanFeedbackBanner

private enum class AuditItemFilter { ALL, REMAINING, COMPLETED, ERRORS }

@Composable
fun ActiveAuditScreen(
    isRtl: Boolean,
    settings: AppSettings,
    activeSession: ActiveInvoiceSession?,
    masterItems: List<MasterInvoiceItem>,
    completedInvoices: List<CompletedInvoiceRecord>,
    auditKpis: AuditScanningKpis,
    sessionSummary: SessionApprovalSummary,
    scanBanner: ScanFeedbackBanner?,
    pendingLongBarcode: PendingLongBarcodePrompt?,
    pendingInvoiceSwitch: PendingInvoiceSwitchConfirmation? = null,
    onDismissBanner: () -> Unit,
    onResolveLongBarcode: (Boolean) -> Unit,
    onConfirmPendingSwitch: () -> Unit = {},
    onDismissPendingSwitch: () -> Unit = {},
    onToggleSound: () -> Unit = {},
    onToggleVibration: () -> Unit = {},
    onOpenInvoice: (String) -> Unit,
    onProcessScan: (String) -> Unit,
    onAdjustQty: (String, Int) -> Unit,
    onSuspendSession: () -> Unit,
    onCloseInvoice: () -> Unit,
    onOpenSessionApproval: () -> Unit,
    onExportSessionPdf: () -> Unit,
    onReopenCompleted: (String) -> Unit,
    onChangeSortMode: (ItemSortMode) -> Unit
) {
    val s = WmsStrings(isRtl)
    var scanInput by remember { mutableStateOf("") }
    var showCameraModal by remember { mutableStateOf(false) }
    var activeFilter by remember { mutableStateOf(AuditItemFilter.ALL) }
    val barcodeFocusRequester = remember { FocusRequester() }

    LaunchedEffect(activeSession?.invoiceNo, showCameraModal, pendingLongBarcode) {
        if (!showCameraModal && pendingLongBarcode == null) {
            runCatching { barcodeFocusRequester.requestFocus() }
        }
    }

    val availableInvoices by remember(masterItems, completedInvoices) {
        derivedStateOf {
            val completedSet = completedInvoices.map { it.invoiceNo }.toSet()
            masterItems.map { it.invoiceNo }.distinct().filter { it !in completedSet }
        }
    }

    val totalReq by remember(activeSession) {
        derivedStateOf { activeSession?.totalRequiredQty ?: 0 }
    }
    val totalScan by remember(activeSession) {
        derivedStateOf { activeSession?.totalScannedQty ?: 0 }
    }
    val progress by remember(activeSession) {
        derivedStateOf {
            (activeSession?.progressPercent ?: 0) / 100f
        }
    }
    val animatedActiveProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 280),
        label = "active_invoice_progress"
    )
    val animatedOverallProgress by animateFloatAsState(
        targetValue = (auditKpis.overallProgressPercent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 320),
        label = "overall_audit_progress"
    )
    val sortedEntries by remember(activeSession, settings.itemSortMode, activeFilter) {
        derivedStateOf {
            val sessionItems = activeSession?.items?.entries?.toList().orEmpty()
            val filtered = when (activeFilter) {
                AuditItemFilter.ALL -> sessionItems
                AuditItemFilter.REMAINING -> sessionItems.filter { it.value.qtyStatus == QtyStatus.SHORTAGE }
                AuditItemFilter.COMPLETED -> sessionItems.filter {
                    it.value.qtyStatus == QtyStatus.EXACT && it.value.codeStatus == CodeStatus.MATCH
                }
                AuditItemFilter.ERRORS -> sessionItems.filter {
                    it.value.qtyStatus == QtyStatus.SURPLUS || it.value.codeStatus == CodeStatus.MISMATCH
                }
            }
            when (settings.itemSortMode) {
                ItemSortMode.LAST_SCANNED -> filtered.sortedByDescending { it.value.lastScannedAt }
                ItemSortMode.ORIGINAL_ORDER -> filtered.sortedBy { it.value.originalIndex }
                ItemSortMode.PENDING_FIRST -> filtered.sortedBy { it.value.qtyStatus == QtyStatus.EXACT }
                ItemSortMode.ERRORS_FIRST -> filtered.sortedBy {
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
        item(key = "top_scanning_kpi_counter") {
            TopScanningProgressCounterCard(
                isRtl = isRtl,
                auditKpis = auditKpis,
                animatedOverallProgress = animatedOverallProgress
            )
        }

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
                                        runCatching { barcodeFocusRequester.requestFocus() }
                                    }
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(barcodeFocusRequester)
                                .testTag("audit_barcode_input")
                        )
                        FilledIconButton(
                            onClick = {
                                if (scanInput.isNotBlank()) {
                                    onProcessScan(scanInput)
                                    scanInput = ""
                                    runCatching { barcodeFocusRequester.requestFocus() }
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

                    // Quick Audio/Vibration & Switch Guard Status Strip
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = settings.soundEnabled,
                            onClick = onToggleSound,
                            modifier = Modifier.testTag("audit_quick_sound_toggle"),
                            leadingIcon = {
                                Icon(
                                    imageVector = if (settings.soundEnabled) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                                    contentDescription = s.t("تبديل الصوت", "Toggle Sound"),
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = {
                                Text(
                                    if (settings.soundEnabled) s.t("الصوت نشط", "Sound On") else s.t("صامت", "Muted"),
                                    maxLines = 1
                                )
                            }
                        )
                        FilterChip(
                            selected = settings.vibrationEnabled,
                            onClick = onToggleVibration,
                            modifier = Modifier.testTag("audit_quick_vibration_toggle"),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Vibration,
                                    contentDescription = s.t("تبديل الاهتزاز", "Toggle Haptics"),
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = {
                                Text(
                                    if (settings.vibrationEnabled) s.t("الاهتزاز", "Haptics") else s.t("بدون اهتزاز", "No Haptics"),
                                    maxLines = 1
                                )
                            }
                        )
                        if (settings.requireDoubleScanToSwitchInvoice) {
                            StatusPill(s.t("حماية 2x", "2x Guard"), Amber400)
                        }
                    }

                    if (pendingInvoiceSwitch != null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pending_invoice_switch_banner"),
                            shape = MaterialTheme.shapes.medium,
                            color = Amber950,
                            border = BorderStroke(1.5.dp, Amber400)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = s.t(
                                        "الفاتورة ${pendingInvoiceSwitch.currentInvoiceNo} مفتوحة — امسح ${pendingInvoiceSwitch.targetInvoiceNo} مجدداً خلال ثانيتين أو اضغط تأكيد للإغلاق والتبديل.",
                                        "Invoice ${pendingInvoiceSwitch.currentInvoiceNo} is open — scan ${pendingInvoiceSwitch.targetInvoiceNo} again within 2s or confirm switch."
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Amber400
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = onConfirmPendingSwitch,
                                        modifier = Modifier
                                            .weight(1f)
                                            .defaultMinSize(minHeight = 48.dp)
                                            .testTag("confirm_invoice_switch_button"),
                                        colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Slate950)
                                    ) {
                                        Text(s.t("إغلاق وتبديل الآن", "Close & Switch"))
                                    }
                                    OutlinedButton(
                                        onClick = onDismissPendingSwitch,
                                        modifier = Modifier
                                            .defaultMinSize(minHeight = 48.dp)
                                            .testTag("dismiss_invoice_switch_button")
                                    ) {
                                        Text(s.t("إلغاء", "Cancel"), color = Slate200)
                                    }
                                }
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
            if (sessionSummary.isAllMasterInvoicesFinished) {
                item(key = "full_session_approval_ready_card") {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("full_session_approval_ready_card"),
                        shape = MaterialTheme.shapes.large,
                        color = if (sessionSummary.isSessionApproved) Emerald950 else Slate900,
                        border = BorderStroke(1.5.dp, if (sessionSummary.isSessionApproved) Emerald400 else Indigo400)
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
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = if (sessionSummary.isSessionApproved) Icons.Rounded.Verified else Icons.AutoMirrored.Rounded.FactCheck,
                                        contentDescription = null,
                                        tint = if (sessionSummary.isSessionApproved) Emerald400 else Cyan400
                                    )
                                    Column {
                                        Text(
                                            text = if (sessionSummary.isSessionApproved)
                                                s.t("تم اعتماد تقرير الجلسة بالكامل", "Full Session Report Approved")
                                            else
                                                s.t("انتهت جميع فواتير الجلسة — التقرير جاهز للاعتماد", "All Invoices Finished — Report Ready for Approval"),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = Slate100
                                        )
                                        Text(
                                            text = s.t(
                                                "الممسوح: ${sessionSummary.totalScannedQty}/${sessionSummary.totalRequiredQty} • الأخطاء: ${sessionSummary.totalDiscrepanciesCount} • الدقة: ${sessionSummary.matchAccuracyPercent}%",
                                                "Scanned: ${sessionSummary.totalScannedQty}/${sessionSummary.totalRequiredQty} • Errors: ${sessionSummary.totalDiscrepanciesCount} • Accuracy: ${sessionSummary.matchAccuracyPercent}%"
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Slate300
                                        )
                                    }
                                }
                                StatusPill(
                                    label = "${sessionSummary.auditedInvoicesCount}/${sessionSummary.totalMasterInvoices}",
                                    color = Emerald400
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onOpenSessionApproval,
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("open_full_approval_report_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Slate950)
                                ) {
                                    Icon(Icons.Rounded.Verified, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (sessionSummary.isSessionApproved)
                                            s.t("عرض التقرير المعتمد", "View Approved Report")
                                        else
                                            s.t("اعتماد وعرض التفاصيل", "Review & Approve Report")
                                    )
                                }
                                OutlinedButton(
                                    onClick = onExportSessionPdf,
                                    modifier = Modifier
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("audit_export_session_pdf_button")
                                ) {
                                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = Rose400)
                                    Spacer(Modifier.width(4.dp))
                                    Text("PDF", color = Slate100)
                                }
                            }
                        }
                    }
                }
            } else if (completedInvoices.isNotEmpty()) {
                item(key = "session_progress_preview_button") {
                    OutlinedButton(
                        onClick = onOpenSessionApproval,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("preview_session_report_button"),
                        border = BorderStroke(1.dp, Indigo400.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Rounded.Assessment, contentDescription = null, tint = Indigo400)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            s.t(
                                "معاينة تقرير الجلسة الحالي (${completedInvoices.size} مغلقة • ${sessionSummary.totalDiscrepanciesCount} أخطاء)",
                                "Preview Current Session Summary (${completedInvoices.size} closed • ${sessionSummary.totalDiscrepanciesCount} errors)"
                            ),
                            color = Indigo400
                        )
                    }
                }
            }

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
                        title = s.t("تم إغلاق جميع الفواتير", "All Invoices Closed"),
                        description = s.t("يمكنك اعتماد تقرير الجلسة الكامل من الأعلى أو استيراد ملف جديد.", "Approve the full session report above or import a new spreadsheet.")
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
                val isCompleted100 = activeSession.isFullyCompleted
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = Slate900,
                    border = BorderStroke(
                        if (isCompleted100) 2.dp else 1.dp,
                        if (isCompleted100) Emerald400 else Cyan500.copy(alpha = 0.45f)
                    )
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
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isCompleted100) Icons.Rounded.Verified else Icons.Rounded.Lock,
                                        contentDescription = null,
                                        tint = if (isCompleted100) Emerald400 else Amber400,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        "${s.t("فاتورة نشطة:", "Active Invoice:")} ${activeSession.invoiceNo}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Emerald400
                                    )
                                }
                                Text(
                                    "${s.t("الطلب:", "Order:")} ${activeSession.orderNo ?: "-"} • ${activeSession.exactItemsCount}/${sortedEntries.size} ${s.t("صنف مكتمل", "items matched")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Slate300
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "${activeSession.progressPercent}%",
                                    style = MonoMetricStyle,
                                    color = if (isCompleted100) Emerald400 else Cyan400,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "$totalScan / $totalReq ${s.t("قطعة", "pcs")}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Slate300
                                )
                            }
                        }

                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (isCompleted100) Emerald950 else Slate950,
                            border = BorderStroke(
                                1.dp,
                                if (isCompleted100) Emerald500.copy(alpha = 0.6f) else Amber500.copy(alpha = 0.35f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (isCompleted100) Icons.Rounded.CheckCircle else Icons.Rounded.Shield,
                                    contentDescription = null,
                                    tint = if (isCompleted100) Emerald400 else Amber400,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (isCompleted100) {
                                        s.t(
                                            "تم مسح 100% من الفاتورة — جميع العناصر مطابقة وجاهزة للإغلاق (الاعتماد لكامل التقرير)",
                                            "100% Invoice Scan Complete — Ready to close (full report approved at session end)"
                                        )
                                    } else {
                                        s.t(
                                            "الفاتورة مقفلة على عناصرها فقط • المتبقي: ${activeSession.remainingQty} قطعة (يُرفض أي باركود خارجي)",
                                            "Locked to invoice items only • Remaining: ${activeSession.remainingQty} pcs (outside barcodes rejected)"
                                        )
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isCompleted100) Emerald400 else Amber400
                                )
                            }
                        }

                        LinearProgressIndicator(
                            progress = { animatedActiveProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            color = if (isCompleted100) Emerald400 else Cyan400,
                            trackColor = Slate800
                        )

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(AuditItemFilter.entries, key = { "flt_${it.name}" }) { filter ->
                                val label = when (filter) {
                                    AuditItemFilter.ALL -> s.t("الكل (${activeSession.items.size})", "All (${activeSession.items.size})")
                                    AuditItemFilter.REMAINING -> s.t("المتبقي", "Remaining")
                                    AuditItemFilter.COMPLETED -> s.t("المكتمل", "Completed")
                                    AuditItemFilter.ERRORS -> s.t("الفروقات", "Errors")
                                }
                                FilterChip(
                                    selected = activeFilter == filter,
                                    onClick = { activeFilter = filter },
                                    modifier = Modifier.testTag("audit_filter_${filter.name.lowercase()}"),
                                    label = { Text(label) }
                                )
                            }
                        }

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
                                onClick = onCloseInvoice,
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("audit_complete_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Slate950)
                            ) {
                                Icon(Icons.Rounded.TaskAlt, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(s.t("إغلاق الفاتورة", "Close Invoice"))
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
                val itemFraction = if (item.requiredQty > 0) {
                    (item.actualQty.toFloat() / item.requiredQty).coerceIn(0f, 1f)
                } else 1f
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("audit_item_card_${item.itemCode}"),
                    shape = MaterialTheme.shapes.medium,
                    color = if (isLast) accent.copy(alpha = 0.14f) else Slate900,
                    border = BorderStroke(if (isLast) 2.dp else 1.dp, accent.copy(alpha = if (isLast) 0.9f else 0.45f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        item.itemName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Slate100,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (isLast) {
                                        StatusPill(s.t("آخر مسح", "Last"), accent)
                                    }
                                }
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
                        LinearProgressIndicator(
                            progress = { itemFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = accent,
                            trackColor = Slate800
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopScanningProgressCounterCard(
    isRtl: Boolean,
    auditKpis: AuditScanningKpis,
    animatedOverallProgress: Float
) {
    val s = WmsStrings(isRtl)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("top_scanning_counter_card"),
        shape = MaterialTheme.shapes.large,
        color = Slate900,
        border = BorderStroke(1.dp, Indigo500.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = s.t("عداد المسح والتقدم الشامل", "Global Scanning Progress Counter"),
                        style = MaterialTheme.typography.titleSmall,
                        color = Slate100
                    )
                    Text(
                        text = s.t(
                            "مكتمل: ${auditKpis.completedInvoices} من ${auditKpis.totalInvoices} فاتورة • المتبقي: ${auditKpis.remainingInvoices}",
                            "Completed: ${auditKpis.completedInvoices}/${auditKpis.totalInvoices} invoices • Remaining: ${auditKpis.remainingInvoices}"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400
                    )
                }

                // Circular graphical progress gauge + exact %
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .testTag("top_scanning_progress_gauge"),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokePx = 6.dp.toPx()
                        val inset = strokePx / 2f
                        val arcSize = Size(size.width - strokePx, size.height - strokePx)
                        drawArc(
                            color = Slate800,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round)
                        )
                        drawArc(
                            brush = Brush.sweepGradient(listOf(Cyan400, Emerald400, Indigo400)),
                            startAngle = -90f,
                            sweepAngle = 360f * animatedOverallProgress,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round)
                        )
                    }
                    Text(
                        text = "${auditKpis.overallProgressPercent}%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (auditKpis.overallProgressPercent >= 100) Emerald400 else Cyan400
                    )
                }
            }

            // 3 KPI counters: Total Invoices | Total Scanned | Remaining
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CounterMiniTile(
                    label = s.t("إجمالي الفواتير", "Total Invoices"),
                    value = auditKpis.totalInvoices.toString(),
                    accent = Indigo400,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("counter_total_invoices")
                )
                CounterMiniTile(
                    label = s.t("إجمالي ما تم مسحه", "Total Scanned"),
                    value = auditKpis.totalScannedQty.toString(),
                    accent = Emerald400,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("counter_total_scanned")
                )
                CounterMiniTile(
                    label = s.t("الباقي", "Remaining"),
                    value = auditKpis.remainingQty.toString(),
                    accent = if (auditKpis.remainingQty == 0 && auditKpis.totalRequiredQty > 0) Emerald400 else Amber400,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("counter_remaining")
                )
            }

            // Graphical horizontal bar + percentage legend
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = s.t(
                            "المؤشر البياني (${auditKpis.totalEffectiveScannedQty} / ${auditKpis.totalRequiredQty} قطعة)",
                            "Progress Bar (${auditKpis.totalEffectiveScannedQty} / ${auditKpis.totalRequiredQty} pcs)"
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate300
                    )
                    Text(
                        text = "${auditKpis.overallProgressPercent}%",
                        style = MonoCodeStyle,
                        color = if (auditKpis.overallProgressPercent >= 100) Emerald400 else Cyan400
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Slate800)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(animatedOverallProgress)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Cyan500, Emerald400)
                                )
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun CounterMiniTile(
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = Slate950,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                style = MonoMetricStyle,
                color = accent,
                maxLines = 1
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Slate300,
                maxLines = 1
            )
        }
    }
}
