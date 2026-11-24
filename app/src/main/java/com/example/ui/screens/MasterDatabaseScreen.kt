package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.*
import com.example.ui.WmsStrings
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.StatusPill
import com.example.ui.theme.*

private enum class MasterInvoiceStatusFilter { ALL, DEFERRED, READY, COMPLETED }

@Composable
fun MasterDatabaseScreen(
    isRtl: Boolean,
    masterItems: List<MasterInvoiceItem>,
    completedInvoices: List<CompletedInvoiceRecord>,
    incompleteInvoices: List<IncompleteInvoiceRecord> = emptyList(),
    packagingRules: List<PackagingGroupRule>,
    onOpenInvoiceInAudit: (String) -> Unit,
    onResetDefaultRules: () -> Unit,
    onOpenExcelSync: () -> Unit
) {
    val s = WmsStrings(isRtl)
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf(MasterInvoiceStatusFilter.ALL) }

    val completedSet by remember(completedInvoices) {
        derivedStateOf { completedInvoices.map { it.invoiceNo }.toSet() }
    }
    val deferredByInvoice by remember(incompleteInvoices) {
        derivedStateOf { incompleteInvoices.associateBy { it.invoiceNo } }
    }

    val groupedInvoices by remember(masterItems, searchQuery, completedSet, deferredByInvoice, statusFilter) {
        derivedStateOf {
            val q = searchQuery.trim().lowercase()
            masterItems
                .groupBy { it.invoiceNo }
                .entries
                .filter { entry ->
                    val matchesSearch = q.isEmpty() ||
                        entry.key.lowercase().contains(q) ||
                        entry.value.any { it.itemCode.lowercase().contains(q) || it.itemName.lowercase().contains(q) }
                    if (!matchesSearch) return@filter false
                    val isDone = entry.key in completedSet
                    val isDeferred = entry.key in deferredByInvoice
                    when (statusFilter) {
                        MasterInvoiceStatusFilter.ALL -> true
                        MasterInvoiceStatusFilter.DEFERRED -> isDeferred && !isDone
                        MasterInvoiceStatusFilter.READY -> !isDeferred && !isDone
                        MasterInvoiceStatusFilter.COMPLETED -> isDone
                    }
                }
                .map { it.key to it.value }
                .sortedBy { (invNo, _) ->
                    when {
                        invNo in deferredByInvoice -> 0
                        invNo !in completedSet -> 1
                        else -> 2
                    }
                }
        }
    }

    val allGrouped = remember(masterItems) { masterItems.groupBy { it.invoiceNo } }
    val deferredCount = remember(allGrouped, deferredByInvoice, completedSet) {
        allGrouped.keys.count { it in deferredByInvoice && it !in completedSet }
    }
    val readyCount = remember(allGrouped, deferredByInvoice, completedSet) {
        allGrouped.keys.count { it !in deferredByInvoice && it !in completedSet }
    }
    val completedCount = remember(allGrouped, completedSet) {
        allGrouped.keys.count { it in completedSet }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "master_db_search_bar") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(s.t("بحث برقم الفاتورة أو باركود الصنف", "Search invoice or item barcode")) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Slate400) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("master_db_search_input")
                )
                FilledIconButton(
                    onClick = onOpenExcelSync,
                    modifier = Modifier
                        .size(52.dp)
                        .testTag("master_db_excel_button"),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Emerald500, contentColor = Slate950)
                ) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = "Import Master Excel")
                }
            }
        }

        item(key = "master_db_tabs") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = { Text("${s.t("الفواتير الرئيسية", "Master Invoices")} (${allGrouped.size})") }
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = { Text("${s.t("قواعد التغليف", "Packaging Rules")} (${packagingRules.size})") }
                )
            }
        }

        if (selectedTab == 0) {
            item(key = "master_db_classification_chips") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(MasterInvoiceStatusFilter.entries, key = { it.name }) { flt ->
                        val label = when (flt) {
                            MasterInvoiceStatusFilter.ALL -> s.t("الكل (${allGrouped.size})", "All (${allGrouped.size})")
                            MasterInvoiceStatusFilter.DEFERRED -> s.t("مرحّلة للاستكمال ($deferredCount)", "Deferred ($deferredCount)")
                            MasterInvoiceStatusFilter.READY -> s.t("جاهزة ($readyCount)", "Ready ($readyCount)")
                            MasterInvoiceStatusFilter.COMPLETED -> s.t("مكتملة ($completedCount)", "Completed ($completedCount)")
                        }
                        FilterChip(
                            selected = statusFilter == flt,
                            onClick = { statusFilter = flt },
                            modifier = Modifier.testTag("master_filter_${flt.name.lowercase()}"),
                            label = { Text(label) }
                        )
                    }
                }
            }

            if (allGrouped.isNotEmpty() && deferredCount == 0 && readyCount == 0 && completedCount == allGrouped.size) {
                item(key = "master_all_completed_banner") {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = Emerald950,
                        border = BorderStroke(1.dp, Emerald400.copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.Verified, contentDescription = null, tint = Emerald400)
                            Text(
                                text = s.t(
                                    "تم استكمال جميع الفواتير — كافة الفواتير الآن مكتملة",
                                    "All deferred & master invoices finished — 100% Completed"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = Emerald400
                            )
                        }
                    }
                }
            }

            if (groupedInvoices.isEmpty()) {
                item(key = "master_db_empty") {
                    EmptyStatePanel(
                        icon = Icons.Rounded.Storage,
                        title = s.t("لا توجد فواتير مطابقة للبحث", "No Matching Invoices"),
                        description = s.t("جرّب مسح مربع البحث أو تغيير التصفية.", "Clear the search query or switch filter.")
                    )
                }
            } else {
                items(groupedInvoices, key = { it.first }) { (invoiceNo, rows) ->
                    val isDone = invoiceNo in completedSet
                    val deferredRec = deferredByInvoice[invoiceNo]
                    val totalQty = remember(rows) { rows.sumOf { it.requiredQty } }
                    val accent = when {
                        isDone -> Emerald400
                        deferredRec != null -> Amber400
                        else -> Cyan400
                    }
                    Surface(
                        onClick = { onOpenInvoiceInAudit(invoiceNo) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("master_invoice_$invoiceNo"),
                        shape = MaterialTheme.shapes.medium,
                        color = if (deferredRec != null && !isDone) Amber950.copy(alpha = 0.45f) else Slate900,
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(invoiceNo, style = MonoCodeStyle, color = accent)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (deferredRec != null && deferredRec.recordShortagesReport && !isDone) {
                                        StatusPill(s.t("نواقص", "Shortages"), Rose400)
                                    }
                                    StatusPill(
                                        label = when {
                                            isDone -> s.t("مكتملة", "Completed")
                                            deferredRec != null -> s.t("مرحّلة للاستكمال (${deferredRec.deferCount}/5)", "Deferred (${deferredRec.deferCount}/5)")
                                            else -> s.t("جاهزة", "Ready")
                                        },
                                        color = accent
                                    )
                                }
                            }
                            Text(
                                "${rows.size} ${s.t("أصناف", "SKUs")} • $totalQty ${s.t("قطعة", "qty")} • ${rows.firstOrNull()?.orderNo ?: "-"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate300
                            )
                        }
                    }
                }
            }
        } else {
            item(key = "master_db_reset_rules") {
                OutlinedButton(
                    onClick = onResetDefaultRules,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("master_db_reset_rules_button")
                ) {
                    Icon(Icons.Rounded.RestartAlt, contentDescription = null, tint = Amber400)
                    Spacer(Modifier.width(8.dp))
                    Text(s.t("استعادة مصفوفة القواعد الافتراضية (14 مجموعة)", "Restore 14 Default Packaging Rules"), color = Slate100)
                }
            }
            items(packagingRules, key = { it.id }) { rule ->
                val diffColor = when (rule.difficultyLevel) {
                    GroupDifficultyLevel.HIGH_EXPERT -> Rose400
                    GroupDifficultyLevel.MEDIUM_INTERMEDIATE -> Amber400
                    GroupDifficultyLevel.LOW_NOVICE -> Emerald400
                }
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = Slate900,
                    border = BorderStroke(1.dp, Slate800)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(rule.name, style = MaterialTheme.typography.titleSmall, color = Slate100, modifier = Modifier.weight(1f))
                            StatusPill(rule.unitName, diffColor)
                        }
                        Text(
                            "${rule.startBarcode} .. ${rule.endBarcode} | ${rule.cartonEquation ?: ""}",
                            style = MonoCodeStyle,
                            color = Cyan400
                        )
                    }
                }
            }
        }
    }
}
