package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.*
import com.example.ui.WmsStrings
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.StatusPill
import com.example.ui.components.WmsKpiCard
import com.example.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ErrorReportScreen(
    isRtl: Boolean,
    discrepancies: List<AuditDiscrepancy>,
    wrongPicks: List<WrongPickingRecord>,
    auditHistory: List<InvoiceAuditHistory>,
    incompleteInvoices: List<IncompleteInvoiceRecord> = emptyList(),
    sessionSummary: SessionApprovalSummary = SessionApprovalSummary(),
    onOpenSessionApproval: () -> Unit = {},
    onOpenDeferredInvoice: (String) -> Unit = {},
    onExportIncompletePdf: () -> Unit = {},
    onExportCsv: () -> Unit,
    onExportPdf: () -> Unit,
    onDeleteDiscrepancy: (Long) -> Unit,
    onClearAllReports: () -> Unit
) {
    val s = WmsStrings(isRtl)
    var selectedTab by remember { mutableIntStateOf(0) }
    val shortageFlaggedIncomplete = remember(incompleteInvoices) {
        incompleteInvoices.filter { it.recordShortagesReport }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "reports_session_approval_card") {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = if (sessionSummary.isSessionApproved) Emerald950 else Slate900,
                border = BorderStroke(
                    1.dp,
                    if (sessionSummary.isSessionApproved) Emerald400 else Indigo400.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = s.t("تقرير اعتماد الجلسة الشامل", "Full Session Approval Report"),
                                style = MaterialTheme.typography.titleSmall,
                                color = Slate100
                            )
                            Text(
                                text = s.t(
                                    "الفواتير: ${sessionSummary.auditedInvoicesCount}/${sessionSummary.totalMasterInvoices} • المسح: ${sessionSummary.totalScannedQty}/${sessionSummary.totalRequiredQty} • الدقة: ${sessionSummary.matchAccuracyPercent}%",
                                    "Invoices: ${sessionSummary.auditedInvoicesCount}/${sessionSummary.totalMasterInvoices} • Scanned: ${sessionSummary.totalScannedQty}/${sessionSummary.totalRequiredQty} • Accuracy: ${sessionSummary.matchAccuracyPercent}%"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate300
                            )
                        }
                        StatusPill(
                            label = if (sessionSummary.isSessionApproved)
                                s.t("معتمد", "Approved")
                            else if (sessionSummary.isAllMasterInvoicesFinished)
                                s.t("جاهز للاعتماد", "Ready")
                            else
                                s.t("قيد التنفيذ", "In Progress"),
                            color = if (sessionSummary.isSessionApproved) Emerald400 else Cyan400
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StatusPill(
                            label = s.t("مطابقة: ${sessionSummary.exactInvoicesCount}", "Exact: ${sessionSummary.exactInvoicesCount}"),
                            color = Emerald400
                        )
                        StatusPill(
                            label = s.t("مرحّلة: ${sessionSummary.deferredInvoicesCount}", "Deferred: ${sessionSummary.deferredInvoicesCount}"),
                            color = if (sessionSummary.deferredInvoicesCount == 0) Emerald400 else Amber400
                        )
                        StatusPill(
                            label = s.t("عجز: -${sessionSummary.totalShortageQty}", "Short: -${sessionSummary.totalShortageQty}"),
                            color = if (sessionSummary.totalShortageQty == 0) Emerald400 else Rose400
                        )
                        StatusPill(
                            label = s.t("زيادة: +${sessionSummary.totalSurplusQty}", "Surplus: +${sessionSummary.totalSurplusQty}"),
                            color = if (sessionSummary.totalSurplusQty == 0) Emerald400 else Amber400
                        )
                    }
                    Button(
                        onClick = onOpenSessionApproval,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("error_report_open_approval_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo500, contentColor = Slate100)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.FactCheck, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (sessionSummary.isSessionApproved)
                                s.t("عرض التقرير المعتمد والتفاصيل", "View Approved Report & Details")
                            else
                                s.t("عرض التفاصيل واعتماد الجلسة (للمدقق)", "View Details & Approve (Auditor)")
                        )
                    }
                }
            }
        }

        item(key = "reports_kpi_row") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                WmsKpiCard(
                    title = s.t("فروقات الكمية", "Discrepancies"),
                    value = discrepancies.size.toString(),
                    icon = Icons.Rounded.WarningAmber,
                    accentColor = Rose400,
                    modifier = Modifier.weight(1f)
                )
                WmsKpiCard(
                    title = s.t("فواتير غير مكتملة", "Incomplete"),
                    value = shortageFlaggedIncomplete.size.toString(),
                    subtitle = s.t("مرحّلة مع النواقص", "deferred w/ shortages"),
                    icon = Icons.Rounded.PendingActions,
                    accentColor = Amber400,
                    modifier = Modifier.weight(1f),
                    onClick = { selectedTab = 3 }
                )
            }
        }

        item(key = "reports_export_actions") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onExportPdf,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("reports_export_pdf_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Rose500, contentColor = Slate100)
                ) {
                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(s.t("تصدير PDF ISA 500", "Export PDF"))
                }
                OutlinedButton(
                    onClick = onExportCsv,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("reports_export_csv_button")
                ) {
                    Icon(Icons.Rounded.TableChart, contentDescription = null, tint = Cyan400)
                    Spacer(Modifier.width(6.dp))
                    Text(s.t("تصدير CSV", "Export CSV"), color = Slate100)
                }
                if (discrepancies.isNotEmpty() || wrongPicks.isNotEmpty()) {
                    IconButton(
                        onClick = onClearAllReports,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("reports_clear_all_button")
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear All Reports", tint = Rose400)
                    }
                }
            }
        }

        item(key = "reports_filter_tabs") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.testTag("reports_tab_discrepancies"),
                    label = { Text("${s.t("الفروقات", "Discrepancies")} (${discrepancies.size})") }
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.testTag("reports_tab_wrong_picks"),
                    label = { Text("${s.t("التجميع الخاطئ", "Wrong Picks")} (${wrongPicks.size})") }
                )
                FilterChip(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    modifier = Modifier.testTag("reports_tab_history"),
                    label = { Text("${s.t("السجل", "History")} (${auditHistory.size})") }
                )
                FilterChip(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    modifier = Modifier.testTag("reports_tab_incomplete_invoices"),
                    label = { Text("${s.t("فواتير غير مكتملة", "Incomplete Invoices")} (${shortageFlaggedIncomplete.size})") }
                )
            }
        }

        when (selectedTab) {
            0 -> {
                if (discrepancies.isEmpty()) {
                    item(key = "reports_empty_discrepancies") {
                        EmptyStatePanel(
                            icon = Icons.Rounded.Verified,
                            title = s.t("لا توجد فروقات مسجلة", "Zero Discrepancies"),
                            description = s.t("جميع الفواتير المدققة مطابقة 100% لمعيار ISA 500.", "All audited invoices passed ISA 500 verification cleanly.")
                        )
                    }
                } else {
                    items(discrepancies, key = { "${it.id}_${it.invoiceNo}_${it.itemCode}" }) { d ->
                        val pillColor = if (d.qtyStatus == QtyStatus.SURPLUS) Amber400 else Rose400
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = Slate900,
                            border = BorderStroke(1.dp, pillColor.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text("${d.invoiceNo} • ${d.itemName}", style = MaterialTheme.typography.titleSmall, color = Slate100)
                                    Text(
                                        "${d.itemCode} | ${s.t("مطلوب:", "Req:")} ${d.requiredQty} | ${s.t("فعلي:", "Act:")} ${d.actualQty} (${d.difference})",
                                        style = MonoCodeStyle,
                                        color = Slate300
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusPill(d.qtyStatus.name, pillColor)
                                    IconButton(
                                        onClick = { onDeleteDiscrepancy(d.id) },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete Row", tint = Slate400)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                if (wrongPicks.isEmpty()) {
                    item(key = "reports_empty_wrong_picks") {
                        EmptyStatePanel(
                            icon = Icons.Rounded.CheckCircleOutline,
                            title = s.t("لا توجد أخطاء تجميع عابرة للفواتير", "No Cross-Invoice Wrong Picks"),
                            description = s.t("لم يتم رصد أي أصناف تخص فواتير أخرى أثناء التدقيق.", "No items belonging to other invoices were scanned.")
                        )
                    }
                } else {
                    items(wrongPicks, key = { "${it.id}_${it.scannedAt}" }) { w ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = Slate900,
                            border = BorderStroke(1.dp, Amber500.copy(alpha = 0.35f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("${w.itemCode} • ${w.itemName ?: ""}", style = MaterialTheme.typography.titleSmall, color = Amber400)
                                Text(
                                    "${s.t("مُسح في فاتورة:", "Scanned in:")} ${w.activeInvoiceNo} → ${s.t("يتبع فاتورة:", "Belongs to:")} ${w.actualBelongingInvoiceNo ?: "UNKNOWN"}",
                                    style = MonoCodeStyle,
                                    color = Slate200
                                )
                            }
                        }
                    }
                }
            }
            2 -> {
                if (auditHistory.isEmpty()) {
                    item(key = "reports_empty_history") {
                        EmptyStatePanel(
                            icon = Icons.Rounded.History,
                            title = s.t("لا يوجد سجل تدقيق مغلق بعد", "No Closed Audit History"),
                            description = s.t("سيظهر سجل الفواتير المغلقة هنا.", "Closed audit sessions appear here.")
                        )
                    }
                } else {
                    items(auditHistory, key = { "${it.id}_${it.invoiceNo}_${it.completedAt}" }) { h ->
                        val color = if (h.discrepancyCount == 0) Emerald400 else Amber400
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
                                Column {
                                    Text("${h.invoiceNo} • ${h.auditorName.orEmpty()}", style = MaterialTheme.typography.titleSmall, color = Slate100)
                                    Text("${h.totalScannedQty}/${h.totalRequiredQty} • ${s.t("فروقات:", "Disc:")} ${h.discrepancyCount}", style = MonoCodeStyle, color = Slate300)
                                }
                                StatusPill(h.status, color)
                            }
                        }
                    }
                }
            }
            else -> {
                item(key = "reports_incomplete_header") {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = Amber950,
                        border = BorderStroke(1.dp, Amber500.copy(alpha = 0.45f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        s.t("تقرير فواتير غير مكتملة", "Incomplete Invoices Report"),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Amber400,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        s.t(
                                            "الفواتير المرحّلة مع تسجيل النواقص لإعادة المسح واستكمالها",
                                            "Invoices deferred with recorded shortages awaiting re-scan"
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Slate200
                                    )
                                }
                                StatusPill("${shortageFlaggedIncomplete.size}", Amber400)
                            }
                            if (shortageFlaggedIncomplete.isNotEmpty()) {
                                Button(
                                    onClick = onExportIncompletePdf,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("reports_export_incomplete_pdf_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Slate950)
                                ) {
                                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        s.t("تصدير تقرير فواتير غير مكتملة PDF", "Export Incomplete Invoices PDF"),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                if (shortageFlaggedIncomplete.isEmpty()) {
                    item(key = "reports_empty_incomplete") {
                        EmptyStatePanel(
                            icon = Icons.Rounded.TaskAlt,
                            title = s.t("لا توجد فواتير غير مكتملة مسجلة", "No Incomplete Invoices Recorded"),
                            description = s.t(
                                "عند اختيار «ترحيل مع تسجيل النواقص» من شاشة التدقيق ستظهر الفاتورة ونواقصها هنا وتُزال تلقائياً فور استكمالها.",
                                "When you select 'Defer w/ Shortages' during audit, the invoice and its missing items appear here until completed."
                            )
                        )
                    }
                } else {
                    items(shortageFlaggedIncomplete, key = { "inc_${it.invoiceNo}" }) { rec ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("incomplete_report_card_${rec.invoiceNo}"),
                            shape = MaterialTheme.shapes.medium,
                            color = Slate900,
                            border = BorderStroke(1.dp, Rose500.copy(alpha = 0.45f))
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
                                        Text("${rec.invoiceNo} • ${rec.auditorName ?: "-"}", style = MonoCodeStyle, color = Amber400)
                                        Text(
                                            s.t(
                                                "ممسوح: ${rec.scannedQty}/${rec.totalRequiredQty} • نواقص: ${rec.missingQty} قطعة • محاولة ${rec.deferCount}/5",
                                                "Scanned: ${rec.scannedQty}/${rec.totalRequiredQty} • Missing: ${rec.missingQty} pcs • Attempt ${rec.deferCount}/5"
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Slate300
                                        )
                                    }
                                    StatusPill(s.t("غير مكتملة", "Incomplete"), Rose400)
                                }
                                if (rec.shortageItemsSnapshot.isNotEmpty()) {
                                    HorizontalDivider(color = Slate800)
                                    rec.shortageItemsSnapshot.forEach { sh ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "${sh.itemCode} • ${sh.itemName}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = Slate200,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                "${sh.actualQty}/${sh.requiredQty} (${sh.difference})",
                                                style = MonoCodeStyle,
                                                color = Rose400
                                            )
                                        }
                                    }
                                }
                                OutlinedButton(
                                    onClick = { onOpenDeferredInvoice(rec.invoiceNo) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("incomplete_reopen_for_completion_${rec.invoiceNo}")
                                ) {
                                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = Emerald400)
                                    Spacer(Modifier.width(6.dp))
                                    Text(s.t("فتح لإعادة المسح والاستكمال", "Open to Re-Scan & Complete"), color = Emerald400)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
