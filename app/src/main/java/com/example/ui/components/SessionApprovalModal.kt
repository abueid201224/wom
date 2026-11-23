package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.*
import com.example.ui.WmsStrings
import com.example.ui.theme.*

@Composable
fun FullSessionApprovalDialog(
    isRtl: Boolean,
    auditorName: String,
    summary: SessionApprovalSummary,
    onDismiss: () -> Unit,
    onApproveWithSignature: (String) -> Unit,
    onExportPdf: () -> Unit
) {
    val s = WmsStrings(isRtl)
    var showDetails by remember { mutableStateOf(false) }
    var onlyErrorInvoices by remember { mutableStateOf(false) }
    var showSignatureModal by remember { mutableStateOf(false) }
    var expandedInvoiceNo by remember { mutableStateOf<String?>(null) }
    val filteredInvoices = remember(summary.invoiceDetails, onlyErrorInvoices) {
        if (onlyErrorInvoices) summary.invoiceDetails.filter { it.discrepancyCount > 0 }
        else summary.invoiceDetails
    }

    if (showSignatureModal) {
        AuditorSignatureModal(
            isRtl = isRtl,
            auditorName = auditorName,
            onDismiss = { showSignatureModal = false },
            onSignatureSaved = { sig ->
                showSignatureModal = false
                onApproveWithSignature(sig)
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .widthIn(max = 640.dp)
                .fillMaxHeight(0.88f)
                .testTag("full_session_approval_dialog"),
            shape = MaterialTheme.shapes.extraLarge,
            color = Slate950,
            border = BorderStroke(
                1.5.dp,
                if (summary.isSessionApproved) Emerald400 else Indigo400.copy(alpha = 0.6f)
            )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Surface(
                    color = Slate900,
                    border = BorderStroke(1.dp, Slate800)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (summary.isSessionApproved) Icons.Rounded.Verified else Icons.AutoMirrored.Rounded.FactCheck,
                                contentDescription = null,
                                tint = if (summary.isSessionApproved) Emerald400 else Cyan400
                            )
                            Column {
                                Text(
                                    text = s.t("تقرير الاعتماد الشامل للجلسة", "Full Session Approval Report"),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Slate100
                                )
                                Text(
                                    text = s.t(
                                        "المدقق: $auditorName • ${summary.auditedInvoicesCount} من ${summary.totalMasterInvoices} فاتورة",
                                        "Auditor: $auditorName • ${summary.auditedInvoicesCount}/${summary.totalMasterInvoices} invoices"
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Slate400
                                )
                            }
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Slate300)
                        }
                    }
                }

                // Body
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Status Pill & Accuracy Bar
                    item(key = "session_status_overview") {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = Slate900,
                            border = BorderStroke(
                                1.dp,
                                if (summary.totalDiscrepanciesCount == 0 && summary.isAllMasterInvoicesFinished)
                                    Emerald500.copy(alpha = 0.5f)
                                else
                                    Cyan500.copy(alpha = 0.4f)
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
                                    StatusPill(
                                        label = when {
                                            summary.isSessionApproved -> s.t("تم اعتماد كامل التقرير", "Report Approved")
                                            summary.isAllMasterInvoicesFinished -> s.t("جاهز للاعتماد النهائي", "Ready for Approval")
                                            else -> s.t("تقرير مرحلي (${summary.remainingInvoicesCount} متبقي)", "In Progress (${summary.remainingInvoicesCount} left)")
                                        },
                                        color = when {
                                            summary.isSessionApproved -> Emerald400
                                            summary.isAllMasterInvoicesFinished -> Cyan400
                                            else -> Amber400
                                        }
                                    )
                                    Text(
                                        text = "${s.t("دقة المطابقة:", "Accuracy:")} ${summary.matchAccuracyPercent}%",
                                        style = MonoMetricStyle,
                                        color = if (summary.matchAccuracyPercent >= 95) Emerald400 else Amber400,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { (summary.matchAccuracyPercent / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = if (summary.totalDiscrepanciesCount == 0) Emerald400 else Cyan400,
                                    trackColor = Slate800
                                )
                            }
                        }
                    }

                    // Scan & Error Totals Grid
                    item(key = "session_totals_grid") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ApprovalMetricTile(
                                    label = s.t("الفواتير المغلقة", "Audited Invoices"),
                                    value = "${summary.auditedInvoicesCount}/${summary.totalMasterInvoices}",
                                    sub = "${s.t("مطابقة:", "Exact:")} ${summary.exactInvoicesCount} • ${s.t("بفروقات:", "Errors:")} ${summary.discrepancyInvoicesCount}",
                                    accent = Emerald400,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("approval_total_invoices")
                                )
                                ApprovalMetricTile(
                                    label = s.t("إجمالي المسح", "Total Scanned"),
                                    value = "${summary.totalScannedQty}/${summary.totalRequiredQty}",
                                    sub = "${summary.totalItemsCount} ${s.t("صنف", "items")}",
                                    accent = Cyan400,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("approval_total_scanned")
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ApprovalMetricTile(
                                    label = s.t("إجمالي الأخطاء", "Total Errors"),
                                    value = summary.totalDiscrepanciesCount.toString(),
                                    sub = s.t(
                                        "عجز: ${summary.shortageItemsCount} • زيادة: ${summary.surplusItemsCount}",
                                        "Short: ${summary.shortageItemsCount} • Surplus: ${summary.surplusItemsCount}"
                                    ),
                                    accent = if (summary.totalDiscrepanciesCount == 0) Emerald400 else Rose400,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("approval_total_errors")
                                )
                                ApprovalMetricTile(
                                    label = s.t("صافي الانحراف", "Qty Variance"),
                                    value = "-${summary.totalShortageQty} / +${summary.totalSurplusQty}",
                                    sub = s.t("عجز / زيادة بالقطعة", "short / surplus pcs"),
                                    accent = if (summary.totalShortageQty + summary.totalSurplusQty == 0) Emerald400 else Amber400,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("approval_total_variance")
                                )
                            }
                        }
                    }

                    // Toggle Details Button
                    item(key = "session_details_toggle") {
                        OutlinedButton(
                            onClick = { showDetails = !showDetails },
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("toggle_session_details_button"),
                            border = BorderStroke(1.dp, Cyan500.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = if (showDetails) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = Cyan400
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (showDetails)
                                    s.t("إخفاء التفاصيل (${summary.invoiceDetails.size} فاتورة)", "Hide Details (${summary.invoiceDetails.size} invoices)")
                                else
                                    s.t("عرض التفاصيل الكاملة للفواتير والأخطاء (${summary.invoiceDetails.size})", "Show Full Invoice & Error Details (${summary.invoiceDetails.size})"),
                                color = Cyan400
                            )
                        }
                    }

                    // Expandable Invoice & Discrepancy Details
                    if (showDetails) {
                        item(key = "details_invoices_header") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SectionHeaderRow(
                                    title = s.t("تفاصيل الفواتير الممسوحة", "Audited Invoices Breakdown"),
                                    subtitle = s.t("اضغط على أي فاتورة لعرض أصناف الفروقات الخاصة بها", "Tap an invoice to inspect its discrepancy items")
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = !onlyErrorInvoices,
                                        onClick = { onlyErrorInvoices = false },
                                        modifier = Modifier.testTag("approval_filter_all_invoices"),
                                        label = { Text("${s.t("الكل", "All")} (${summary.invoiceDetails.size})") }
                                    )
                                    FilterChip(
                                        selected = onlyErrorInvoices,
                                        onClick = { onlyErrorInvoices = true },
                                        modifier = Modifier.testTag("approval_filter_error_invoices"),
                                        label = { Text("${s.t("بفروقات فقط", "Errors Only")} (${summary.discrepancyInvoicesCount})") }
                                    )
                                }
                                if (filteredInvoices.isEmpty()) {
                                    EmptyStatePanel(
                                        icon = Icons.Rounded.Verified,
                                        title = s.t("لا توجد فواتير بفروقات", "Zero Error Invoices"),
                                        description = s.t("جميع الفواتير المغلقة مطابقة بنسبة 100%.", "All closed invoices matched 100%.")
                                    )
                                }
                            }
                        }

                        items(filteredInvoices, key = { "detail_${it.invoiceNo}" }) { row ->
                            val invDiscs = summary.discrepancyDetails.filter { it.invoiceNo == row.invoiceNo }
                            val isExpanded = expandedInvoiceNo == row.invoiceNo
                            val accent = if (row.discrepancyCount == 0) Emerald400 else Rose400
                            Surface(
                                onClick = {
                                    expandedInvoiceNo = if (isExpanded) null else row.invoiceNo
                                },
                                shape = MaterialTheme.shapes.medium,
                                color = Slate900,
                                border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("approval_detail_invoice_${row.invoiceNo}")
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
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(row.invoiceNo, style = MonoCodeStyle, color = Slate100)
                                            Text(
                                                s.t(
                                                    "مسح: ${row.scannedQty}/${row.requiredQty} • أصناف: ${row.exactItemsCount}/${row.requiredItemsCount} مطابق",
                                                    "Scan: ${row.scannedQty}/${row.requiredQty} • Items: ${row.exactItemsCount}/${row.requiredItemsCount} exact"
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Slate300
                                            )
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            StatusPill(
                                                label = if (row.discrepancyCount == 0)
                                                    s.t("مطابقة", "EXACT")
                                                else
                                                    s.t("${row.discrepancyCount} أخطاء", "${row.discrepancyCount} errors"),
                                                color = accent
                                            )
                                            if (invDiscs.isNotEmpty()) {
                                                Icon(
                                                    imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                                    contentDescription = null,
                                                    tint = Slate400
                                                )
                                            }
                                        }
                                    }

                                    AnimatedVisibility(visible = isExpanded && invDiscs.isNotEmpty()) {
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.padding(top = 4.dp)
                                        ) {
                                            HorizontalDivider(color = Slate800)
                                            invDiscs.forEach { d ->
                                                val diffColor = if (d.difference > 0) Amber400 else Rose400
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text("${d.itemCode} • ${d.itemName}", style = MaterialTheme.typography.labelMedium, color = Slate200)
                                                        Text(
                                                            "${s.t("مطلوب:", "Req:")} ${d.requiredQty} | ${s.t("فعلي:", "Act:")} ${d.actualQty}",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = Slate400
                                                        )
                                                    }
                                                    StatusPill(
                                                        label = "${if (d.difference > 0) "+" else ""}${d.difference}",
                                                        color = diffColor
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (summary.discrepancyDetails.isNotEmpty()) {
                            item(key = "details_all_errors_header") {
                                SectionHeaderRow(
                                    title = s.t("ملخص كافة الفروقات والأخطاء", "All Recorded Discrepancies"),
                                    subtitle = s.t("${summary.discrepancyDetails.size} سجل", "${summary.discrepancyDetails.size} items")
                                )
                            }
                            items(summary.discrepancyDetails, key = { "err_${it.id}_${it.invoiceNo}_${it.itemCode}" }) { d ->
                                val diffColor = if (d.difference > 0) Amber400 else Rose400
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = Slate900,
                                    border = BorderStroke(1.dp, diffColor.copy(alpha = 0.35f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("${d.invoiceNo} • ${d.itemName}", style = MaterialTheme.typography.labelLarge, color = Slate100)
                                            Text(
                                                "${d.itemCode} | ${s.t("مطلوب:", "Req:")} ${d.requiredQty} | ${s.t("ممسوح:", "Scanned:")} ${d.actualQty}",
                                                style = MonoCodeStyle,
                                                color = Slate300
                                            )
                                        }
                                        StatusPill("${if (d.difference > 0) "+" else ""}${d.difference}", diffColor)
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom Action Bar
                Surface(
                    color = Slate900,
                    border = BorderStroke(1.dp, Slate800)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onExportPdf,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("session_approval_export_pdf")
                        ) {
                            Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = Rose400)
                            Spacer(Modifier.width(6.dp))
                            Text("PDF", color = Slate100)
                        }
                        Button(
                            onClick = { showSignatureModal = true },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("approve_full_session_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Emerald500,
                                contentColor = Slate950
                            )
                        ) {
                            Icon(Icons.Rounded.Draw, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (summary.isSessionApproved)
                                    s.t("إعادة توقيع واعتماد التقرير", "Re-sign & Approve Report")
                                else
                                    s.t("اعتماد وتوقيع كامل التقرير", "Sign & Approve Full Report")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalMetricTile(
    label: String,
    value: String,
    sub: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = Slate900,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Slate300, maxLines = 1)
            Text(value, style = MonoMetricStyle, color = accent, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = Slate400, maxLines = 1)
        }
    }
}
