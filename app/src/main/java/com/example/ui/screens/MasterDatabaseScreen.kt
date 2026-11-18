package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.CompletedInvoiceRecord
import com.example.model.GroupDifficultyLevel
import com.example.model.MasterInvoiceItem
import com.example.model.PackagingGroupRule
import com.example.ui.WmsStrings
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.StatusPill
import com.example.ui.theme.*

@Composable
fun MasterDatabaseScreen(
    isRtl: Boolean,
    masterItems: List<MasterInvoiceItem>,
    completedInvoices: List<CompletedInvoiceRecord>,
    packagingRules: List<PackagingGroupRule>,
    onOpenInvoiceInAudit: (String) -> Unit,
    onResetDefaultRules: () -> Unit,
    onOpenExcelSync: () -> Unit
) {
    val s = WmsStrings(isRtl)
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    val completedSet by remember(completedInvoices) {
        derivedStateOf { completedInvoices.map { it.invoiceNo }.toSet() }
    }

    val groupedInvoices by remember(masterItems, searchQuery) {
        derivedStateOf {
            val q = searchQuery.trim().lowercase()
            masterItems
                .groupBy { it.invoiceNo }
                .entries
                .filter { entry ->
                    q.isEmpty() ||
                        entry.key.lowercase().contains(q) ||
                        entry.value.any { it.itemCode.lowercase().contains(q) || it.itemName.lowercase().contains(q) }
                }
                .map { it.key to it.value }
        }
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
                    label = { Text("${s.t("الفواتير الرئيسية", "Master Invoices")} (${groupedInvoices.size})") }
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = { Text("${s.t("قواعد التغليف", "Packaging Rules")} (${packagingRules.size})") }
                )
            }
        }

        if (selectedTab == 0) {
            if (groupedInvoices.isEmpty()) {
                item(key = "master_db_empty") {
                    EmptyStatePanel(
                        icon = Icons.Rounded.Storage,
                        title = s.t("لا توجد فواتير مطابقة للبحث", "No Matching Invoices"),
                        description = s.t("جرّب مسح مربع البحث أو استيراد ملف إكسيل جديد.", "Clear the search query or import a new Excel spreadsheet.")
                    )
                }
            } else {
                items(groupedInvoices, key = { it.first }) { (invoiceNo, rows) ->
                    val isDone = invoiceNo in completedSet
                    val totalQty = remember(rows) { rows.sumOf { it.requiredQty } }
                    Surface(
                        onClick = { onOpenInvoiceInAudit(invoiceNo) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("master_invoice_$invoiceNo"),
                        shape = MaterialTheme.shapes.medium,
                        color = Slate900,
                        border = BorderStroke(1.dp, if (isDone) Emerald500.copy(alpha = 0.4f) else Slate800)
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
                                Text(invoiceNo, style = MonoCodeStyle, color = Emerald400)
                                StatusPill(
                                    label = if (isDone) s.t("مكتمل", "Completed") else s.t("مفتوح", "Pending"),
                                    color = if (isDone) Emerald400 else Cyan400
                                )
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
