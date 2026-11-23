package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AssignmentReturn
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.*
import com.example.ui.WmsStrings
import com.example.ui.components.SectionHeaderRow
import com.example.ui.components.StatusPill
import com.example.ui.components.WmsKpiCard
import com.example.ui.navigation.WmsRoute
import com.example.ui.theme.*
import com.google.firebase.auth.FirebaseUser

private data class QuickModuleTile(
    val route: WmsRoute,
    val tag: String,
    val titleAr: String,
    val titleEn: String,
    val subtitleAr: String,
    val subtitleEn: String,
    val icon: ImageVector,
    val accent: Color
)

@Composable
fun WelcomeDashboardScreen(
    isRtl: Boolean,
    currentUser: AppUser,
    firebaseUser: FirebaseUser? = null,
    cloudReturnsCount: Int = 0,
    syncMetadata: SyncMetadata,
    masterItems: List<MasterInvoiceItem>,
    completedInvoices: List<CompletedInvoiceRecord>,
    incompleteInvoices: List<IncompleteInvoiceRecord>,
    discrepancies: List<AuditDiscrepancy>,
    returnReports: List<ReturnReport>,
    pickingWaves: List<BatchPickingWave>,
    dailySnapshots: List<DailyAuditSnapshot>,
    sessionSummary: SessionApprovalSummary = SessionApprovalSummary(),
    onNavigate: (WmsRoute) -> Unit,
    onResumeIncomplete: (String) -> Unit,
    onCloseShift: () -> Unit,
    onOpenExcel: () -> Unit,
    onOpenCloud: () -> Unit
) {
    val s = WmsStrings(isRtl)
    val totalInvoices by remember(masterItems) {
        derivedStateOf { masterItems.asSequence().map { it.invoiceNo }.distinct().count() }
    }
    val remainingToAuditCount by remember(totalInvoices, completedInvoices) {
        derivedStateOf { (totalInvoices - completedInvoices.size).coerceAtLeast(0) }
    }
    val pendingLabCount by remember(returnReports) {
        derivedStateOf { returnReports.count { it.status == ReturnReportStatus.PENDING_LAB } }
    }
    val activeWavesCount by remember(pickingWaves) {
        derivedStateOf { pickingWaves.count { it.status != "COMPLETED" } }
    }

    val modules = remember {
        listOf(
            QuickModuleTile(WmsRoute.ActiveAudit, "dash_tile_audit", "تدقيق الفواتير", "Dispatch Audit", "فحص ومسح باركود الصادر", "Barcode verification", Icons.Rounded.QrCodeScanner, Emerald400),
            QuickModuleTile(WmsRoute.PickingWave, "dash_tile_waves", "موجات الانتقاء", "Picking Waves", "تجميع وتوزيع الفرق", "Batch wave picking", Icons.Rounded.Layers, Indigo400),
            QuickModuleTile(WmsRoute.Receiving, "dash_tile_receiving", "استلام التوريدات", "PO Receiving", "فحص أوامر الشراء الواردة", "Inbound PO verification", Icons.Rounded.LocalShipping, Cyan400),
            QuickModuleTile(WmsRoute.Returns, "dash_tile_returns", "المرتجعات والمختبر", "RMA Returns", "فحص الإرجاع والتحكيم", "Returns & lab review", Icons.AutoMirrored.Rounded.AssignmentReturn, Amber400),
            QuickModuleTile(WmsRoute.InventoryCount, "dash_tile_inventory", "الجرد التفكيكي", "Cycle Count", "عد الكراتين والبوكس والحبة", "Carton/box/pcs count", Icons.Rounded.Inventory2, Purple400),
            QuickModuleTile(WmsRoute.ErrorReport, "dash_tile_reports", "تقارير ISA 500", "Discrepancies", "الفروقات وتصدير PDF", "Reports & PDF export", Icons.Rounded.Assessment, Rose400)
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Banner
        item(key = "dash_hero_banner") {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = Slate900,
                border = BorderStroke(1.dp, Emerald500.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = s.t("مرحباً، ${currentUser.name}", "Welcome, ${currentUser.name}"),
                                style = MaterialTheme.typography.titleLarge,
                                color = Slate100
                            )
                            Text(
                                text = "${syncMetadata.fileName ?: "Demo_Master_Invoices.xlsx"} • $totalInvoices ${s.t("فاتورة", "invoices")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate400
                            )
                        }
                        StatusPill(currentUser.jobId, Emerald400)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusPill(
                            label = if (firebaseUser != null)
                                s.t("سحابي: ${firebaseUser.email?.substringBefore('@') ?: "متصل"} ($cloudReturnsCount)", "Cloud: ${firebaseUser.email?.substringBefore('@') ?: "Connected"} ($cloudReturnsCount)")
                            else
                                s.t("الوضع المحلي أوفلاين", "Local Offline Mode"),
                            color = if (firebaseUser != null) Cyan400 else Amber400
                        )
                        StatusPill(
                            label = when {
                                sessionSummary.isSessionApproved -> s.t("الجلسة معتمدة", "Session Approved")
                                sessionSummary.isAllMasterInvoicesFinished -> s.t("جاهز للاعتماد الشامل", "Ready for Approval")
                                else -> s.t("جاهز للتدقيق: $remainingToAuditCount", "Ready: $remainingToAuditCount")
                            },
                            color = when {
                                sessionSummary.isSessionApproved -> Emerald400
                                sessionSummary.isAllMasterInvoicesFinished -> Indigo400
                                else -> Cyan400
                            }
                        )
                    }
                    if (totalInvoices > 0) {
                        val sessionFraction = (completedInvoices.size.toFloat() / totalInvoices.coerceAtLeast(1)).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { sessionFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = if (remainingToAuditCount == 0) Emerald400 else Cyan400,
                            trackColor = Slate800
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (sessionSummary.isAllMasterInvoicesFinished && !sessionSummary.isSessionApproved) {
                                    onCloseShift()
                                } else {
                                    onNavigate(WmsRoute.ActiveAudit)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("dash_start_audit_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (sessionSummary.isAllMasterInvoicesFinished && !sessionSummary.isSessionApproved) Indigo500 else Emerald500,
                                contentColor = if (sessionSummary.isAllMasterInvoicesFinished && !sessionSummary.isSessionApproved) Color.White else Slate950
                            )
                        ) {
                            Icon(
                                if (sessionSummary.isAllMasterInvoicesFinished && !sessionSummary.isSessionApproved)
                                    Icons.AutoMirrored.Rounded.FactCheck
                                else
                                    Icons.Rounded.QrCodeScanner,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (sessionSummary.isAllMasterInvoicesFinished && !sessionSummary.isSessionApproved)
                                    s.t("اعتماد تقرير الجلسة", "Approve Full Report")
                                else
                                    s.t("بدء التدقيق", "Start Audit")
                            )
                        }
                        OutlinedButton(
                            onClick = onOpenExcel,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("dash_excel_button")
                        ) {
                            Icon(Icons.Rounded.UploadFile, contentDescription = "Import Excel", tint = Cyan400)
                        }
                        OutlinedButton(
                            onClick = onOpenCloud,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("dash_cloud_button")
                        ) {
                            Icon(Icons.Rounded.CloudSync, contentDescription = "Cloud Sync", tint = if (firebaseUser != null) Cyan400 else Indigo400)
                        }
                    }
                }
            }
        }

        // KPI Grid
        item(key = "dash_kpi_grid") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    WmsKpiCard(
                        title = s.t("الفواتير المكتملة", "Completed"),
                        value = "${completedInvoices.size}/$totalInvoices",
                        subtitle = s.t("متبقي $remainingToAuditCount للتدقيق", "$remainingToAuditCount ready to audit"),
                        icon = Icons.Rounded.Verified,
                        accentColor = Emerald400,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(WmsRoute.MasterDatabase) }
                    )
                    WmsKpiCard(
                        title = s.t("إجمالي الفروقات", "Discrepancies"),
                        value = discrepancies.size.toString(),
                        subtitle = s.t("عجز / زيادة / خطأ", "short/surplus/wrong"),
                        icon = Icons.Rounded.WarningAmber,
                        accentColor = if (discrepancies.isEmpty()) Emerald400 else Rose400,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(WmsRoute.ErrorReport) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    WmsKpiCard(
                        title = s.t("قيد المختبر RMA", "Pending Lab"),
                        value = pendingLabCount.toString(),
                        subtitle = s.t("تقارير مرتجعات", "return tickets"),
                        icon = Icons.Rounded.Science,
                        accentColor = Amber400,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(WmsRoute.Returns) }
                    )
                    WmsKpiCard(
                        title = s.t("موجات الانتقاء", "Active Waves"),
                        value = activeWavesCount.toString(),
                        subtitle = s.t("مهام التجهيز", "picking waves"),
                        icon = Icons.Rounded.Layers,
                        accentColor = Indigo400,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(WmsRoute.PickingWave) }
                    )
                }
            }
        }

        // Incomplete Suspended Sessions
        if (incompleteInvoices.isNotEmpty()) {
            item(key = "dash_suspended_sessions") {
                SectionHeaderRow(
                    title = s.t("فواتير معلّقة للاستئناف", "Suspended Invoices"),
                    subtitle = s.t("${incompleteInvoices.size} فاتورة قيد التدقيق", "${incompleteInvoices.size} pending resume")
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(incompleteInvoices, key = { it.invoiceNo }) { rec ->
                        Surface(
                            onClick = { onResumeIncomplete(rec.invoiceNo) },
                            modifier = Modifier
                                .width(220.dp)
                                .testTag("resume_incomplete_${rec.invoiceNo}"),
                            shape = MaterialTheme.shapes.medium,
                            color = Amber950,
                            border = BorderStroke(1.dp, Amber500.copy(alpha = 0.4f))
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(rec.invoiceNo, style = MonoCodeStyle, color = Amber400)
                                    Text("${rec.scannedQty}/${rec.totalRequiredQty}", style = MonoCodeStyle, color = Slate200)
                                }
                                Text(
                                    s.t("اضغط لاستئناف التدقيق", "Tap to resume audit"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Slate300
                                )
                            }
                        }
                    }
                }
            }
        }

        // Module Shortcuts
        item(key = "dash_module_shortcuts") {
            SectionHeaderRow(
                title = s.t("أقسام العمليات التشغيلية", "Operational Modules"),
                actionLabel = s.t("قاعدة البيانات", "Master DB"),
                onActionClick = { onNavigate(WmsRoute.MasterDatabase) }
            )
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                modules.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        pair.forEach { mod ->
                            Surface(
                                onClick = { onNavigate(mod.route) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag(mod.tag),
                                shape = MaterialTheme.shapes.medium,
                                color = Slate900,
                                border = BorderStroke(1.dp, Slate800)
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(mod.icon, contentDescription = null, tint = mod.accent, modifier = Modifier.size(26.dp))
                                    Text(
                                        text = if (isRtl) mod.titleAr else mod.titleEn,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Slate100
                                    )
                                    Text(
                                        text = if (isRtl) mod.subtitleAr else mod.subtitleEn,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Slate400,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Shift Closure & Daily Snapshots
        item(key = "dash_shift_closure") {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = Slate900,
                border = BorderStroke(1.dp, Slate800)
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
                                s.t("الإغلاق اليومي للوردية", "Daily Shift Closure"),
                                style = MaterialTheme.typography.titleSmall,
                                color = Slate100
                            )
                            Text(
                                s.t("تم ترحيل ${dailySnapshots.size} إغلاق يومي محفوظ", "${dailySnapshots.size} archived daily snapshots"),
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate400
                            )
                        }
                        Button(
                            onClick = onCloseShift,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("dash_close_shift_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Indigo500, contentColor = Color.White)
                        ) {
                            Text(s.t("إغلاق الوردية", "Close Shift"))
                        }
                    }
                }
            }
        }
    }
}
