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
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.*
import com.example.services.PackagingEngine
import com.example.ui.WmsStrings
import com.example.ui.components.CameraQrScannerModal
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.SectionHeaderRow
import com.example.ui.components.StatusPill
import com.example.ui.theme.*
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InventoryCountScreen(
    isRtl: Boolean,
    currentUser: AppUser,
    packagingRules: List<PackagingGroupRule>,
    inventoryReports: List<InventoryCountReport>,
    draftItems: List<InventoryCountItem>,
    isImportBusy: Boolean,
    isCloudBusy: Boolean,
    onImportBookExcel: (Uri, String) -> Unit,
    onSyncBookFromMaster: () -> Unit,
    onSyncReportsFromCloud: () -> Unit,
    onAddOrIncrementItem: (itemCode: String, itemName: String, bookQty: Int, cartons: Int, packs: Int, pieces: Int) -> Unit,
    onScanBarcodeIntoDraft: (String) -> Boolean,
    onAdjustDraftCounts: (itemId: String, dCartons: Int, dPacks: Int, dPieces: Int) -> Unit,
    onRemoveDraftItem: (String) -> Unit,
    onClearDraft: () -> Unit,
    onSaveInventoryReport: (InventoryCountReport) -> Unit,
    onDeleteInventoryReport: (String) -> Unit
) {
    val s = WmsStrings(isRtl)
    val context = LocalContext.current

    var sessionTitle by remember { mutableStateOf("جرد قطاع A - دوري") }
    var aisleCode by remember { mutableStateOf("A-01") }
    var itemCodeInput by remember { mutableStateOf("") }
    var itemNameInput by remember { mutableStateOf("") }
    var bookQtyInput by remember { mutableStateOf("24") }
    var cartonsInput by remember { mutableStateOf("1") }
    var packsInput by remember { mutableStateOf("0") }
    var piecesInput by remember { mutableStateOf("0") }
    var showCamera by remember { mutableStateOf(false) }

    val excelBookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            var displayName = "BookBalance.xlsx"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && cursor.moveToFirst()) {
                    displayName = cursor.getString(col) ?: displayName
                }
            }
            onImportBookExcel(uri, displayName)
        }
    }

    val matchedRule by remember(itemCodeInput, packagingRules) {
        derivedStateOf { PackagingEngine.matchBarcodeToPackagingRule(itemCodeInput, packagingRules) }
    }

    val liveCalculatedPreview by remember(cartonsInput, packsInput, piecesInput, matchedRule) {
        derivedStateOf {
            PackagingEngine.calculateExactQtyFromColumns(
                cartons = cartonsInput.toIntOrNull() ?: 0,
                packs = packsInput.toIntOrNull() ?: 0,
                pieces = piecesInput.toIntOrNull() ?: 0,
                cartonFactor = matchedRule?.cartonFactor ?: 24.0,
                packFactor = matchedRule?.packFactor ?: 6.0
            )
        }
    }

    val totalBook by remember(draftItems) { derivedStateOf { draftItems.sumOf { it.bookQty } } }
    val totalActual by remember(draftItems) { derivedStateOf { draftItems.sumOf { it.calculatedActualQty } } }
    val totalVariance by remember(draftItems) { derivedStateOf { draftItems.sumOf { it.varianceQty } } }

    if (showCamera) {
        CameraQrScannerModal(
            isRtl = isRtl,
            onDismiss = { showCamera = false },
            onBarcodeScanned = { code ->
                showCamera = false
                if (!onScanBarcodeIntoDraft(code)) {
                    itemCodeInput = code
                }
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
        item(key = "inventory_sync_actions_card") {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = Slate900,
                border = BorderStroke(1.dp, Cyan500.copy(alpha = 0.35f))
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
                                s.t("استيراد ومزامنة الرصيد الدفتري", "Book-Balance Import & Sync"),
                                style = MaterialTheme.typography.titleMedium,
                                color = Cyan400
                            )
                            Text(
                                s.t(
                                    "ارفع ملف Excel/CSV بالرصيد الدفتري أو زامن من القاعدة أو السحابة",
                                    "Import Excel/CSV book stock or sync from Master DB & Cloud"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate400
                            )
                        }
                    }

                    if (isImportBusy || isCloudBusy) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Cyan400,
                            trackColor = Slate800
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                excelBookPicker.launch(
                                    arrayOf(
                                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                        "text/csv",
                                        "text/comma-separated-values",
                                        "application/vnd.ms-excel",
                                        "*/*"
                                    )
                                )
                            },
                            enabled = !isImportBusy,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("inventory_import_excel_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Slate950)
                        ) {
                            Icon(Icons.Rounded.TableView, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(s.t("استيراد إكسيل بالرصيد الدفتري", "Import Book Excel"))
                        }

                        OutlinedButton(
                            onClick = onSyncBookFromMaster,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("inventory_sync_master_button"),
                            border = BorderStroke(1.dp, Cyan500.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Rounded.Sync, contentDescription = null, tint = Cyan400)
                            Spacer(Modifier.width(6.dp))
                            Text(s.t("مزامنة من القاعدة", "Sync Master DB"), color = Slate100)
                        }

                        OutlinedButton(
                            onClick = onSyncReportsFromCloud,
                            enabled = !isCloudBusy,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("inventory_sync_cloud_button"),
                            border = BorderStroke(1.dp, Indigo500.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = Indigo400)
                            Spacer(Modifier.width(6.dp))
                            Text(s.t("مزامنة السحابة", "Cloud Sync"), color = Slate100)
                        }
                    }
                }
            }
        }

        item(key = "inventory_form_card") {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = Slate900,
                border = BorderStroke(1.dp, Purple500.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        s.t("الجرد الفعلي وتفكيك العبوات", "Physical Cycle Count & Breakdown"),
                        style = MaterialTheme.typography.titleMedium,
                        color = Purple400
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = sessionTitle,
                            onValueChange = { sessionTitle = it },
                            label = { Text(s.t("عنوان الجلسة", "Count Title")) },
                            singleLine = true,
                            modifier = Modifier.weight(1.4f)
                        )
                        OutlinedTextField(
                            value = aisleCode,
                            onValueChange = { aisleCode = it },
                            label = { Text(s.t("الممر / الرف", "Aisle")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = itemCodeInput,
                            onValueChange = { itemCodeInput = it },
                            label = { Text(s.t("باركود الصنف", "Barcode")) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("inventory_barcode_input")
                        )
                        FilledIconButton(
                            onClick = { showCamera = true },
                            modifier = Modifier
                                .size(50.dp)
                                .testTag("inventory_camera_button"),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Indigo500)
                        ) {
                            Icon(Icons.Rounded.QrCodeScanner, contentDescription = "Scan Item")
                        }
                    }

                    if (matchedRule != null) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = Emerald950,
                            border = BorderStroke(1.dp, Emerald500.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = "${matchedRule!!.name} • ${matchedRule!!.cartonEquation ?: "1CTN=${matchedRule!!.cartonFactor}"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Emerald400,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = itemNameInput,
                            onValueChange = { itemNameInput = it },
                            label = { Text(s.t("البيان (اختياري)", "Item Name")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = bookQtyInput,
                            onValueChange = { bookQtyInput = it.filter(Char::isDigit) },
                            label = { Text(s.t("الرصيد الدفتري", "Book Qty")) },
                            singleLine = true,
                            modifier = Modifier.width(120.dp)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = cartonsInput,
                            onValueChange = { cartonsInput = it.filter(Char::isDigit) },
                            label = { Text(s.t("كرتون", "Cartons")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = packsInput,
                            onValueChange = { packsInput = it.filter(Char::isDigit) },
                            label = { Text(s.t("بوكس", "Boxes")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = piecesInput,
                            onValueChange = { piecesInput = it.filter(Char::isDigit) },
                            label = { Text(s.t("حبة", "Pcs")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = "${s.t("الإجمالي المحسوب:", "Computed Total:")} $liveCalculatedPreview ${matchedRule?.unitName ?: "PCS"}",
                        style = MonoCodeStyle,
                        color = Cyan400
                    )

                    Button(
                        onClick = {
                            if (itemCodeInput.isNotBlank()) {
                                onAddOrIncrementItem(
                                    itemCodeInput.trim(),
                                    itemNameInput.trim(),
                                    bookQtyInput.toIntOrNull() ?: 0,
                                    cartonsInput.toIntOrNull() ?: 0,
                                    packsInput.toIntOrNull() ?: 0,
                                    piecesInput.toIntOrNull() ?: 0
                                )
                                itemCodeInput = ""
                                itemNameInput = ""
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("inventory_add_line_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple500, contentColor = Slate100)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(s.t("احتساب وإضافة لجلسة الجرد", "Calculate & Add Count"))
                    }
                }
            }
        }

        if (draftItems.isNotEmpty()) {
            item(key = "inventory_draft_summary") {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = Slate900,
                    border = BorderStroke(1.dp, Slate700)
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
                            Text(
                                text = s.t("مسودة الجرد النشطة (${draftItems.size} صنف)", "Active Count Draft (${draftItems.size} items)"),
                                style = MaterialTheme.typography.titleSmall,
                                color = Slate100
                            )
                            TextButton(
                                onClick = onClearDraft,
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("inventory_clear_draft_button")
                            ) {
                                Text(s.t("مسح المسودة", "Clear"), color = Rose400)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            InventoryStatChip(s.t("الدفتري", "Book"), totalBook.toString(), Cyan400, Modifier.weight(1f))
                            InventoryStatChip(s.t("الفعلي", "Actual"), totalActual.toString(), Emerald400, Modifier.weight(1f))
                            val varAccent = if (totalVariance == 0) Emerald400 else if (totalVariance > 0) Amber400 else Rose400
                            InventoryStatChip(
                                s.t("الانحراف", "Variance"),
                                "${if (totalVariance > 0) "+" else ""}$totalVariance",
                                varAccent,
                                Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            items(draftItems, key = { it.id }) { row ->
                val accent = if (row.varianceQty == 0) Emerald400 else if (row.varianceQty > 0) Amber400 else Rose400
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = Slate900,
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.38f))
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
                                    text = row.itemName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Slate100,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${row.itemCode} • ${row.unit}" +
                                        (row.groupName?.let { " • $it" } ?: ""),
                                    style = MonoCodeStyle,
                                    color = Cyan400
                                )
                                Text(
                                    text = "${s.t("دفتري", "Book")}: ${row.bookQty} | ${s.t("فعلي", "Actual")}: ${row.calculatedActualQty} (${row.cartonsCount}CTN + ${row.packsCount}BOX + ${row.piecesCount}PCS)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Slate300
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusPill("${if (row.varianceQty > 0) "+" else ""}${row.varianceQty}", accent)
                                IconButton(
                                    onClick = { onRemoveDraftItem(row.id) },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Remove Item", tint = Rose400)
                                }
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            UnitStepChip(
                                label = "${s.t("كرتون", "CTN")} (${row.cartonsCount})",
                                onMinus = { onAdjustDraftCounts(row.id, -1, 0, 0) },
                                onPlus = { onAdjustDraftCounts(row.id, 1, 0, 0) }
                            )
                            UnitStepChip(
                                label = "${s.t("بوكس", "BOX")} (${row.packsCount})",
                                onMinus = { onAdjustDraftCounts(row.id, 0, -1, 0) },
                                onPlus = { onAdjustDraftCounts(row.id, 0, 1, 0) }
                            )
                            UnitStepChip(
                                label = "${s.t("حبة", "PCS")} (${row.piecesCount})",
                                onMinus = { onAdjustDraftCounts(row.id, 0, 0, -1) },
                                onPlus = { onAdjustDraftCounts(row.id, 0, 0, 1) }
                            )
                        }
                    }
                }
            }

            item(key = "inventory_save_action") {
                Button(
                    onClick = {
                        onSaveInventoryReport(
                            InventoryCountReport(
                                id = "cnt-${UUID.randomUUID().toString().take(8)}",
                                title = sessionTitle.ifBlank { "Cycle Count" },
                                sectionOrAisle = aisleCode,
                                createdAt = Instant.now().toString(),
                                auditorName = currentUser.name,
                                auditorId = currentUser.jobId,
                                items = draftItems.toList(),
                                totalBookQty = draftItems.sumOf { it.bookQty },
                                totalActualQty = draftItems.sumOf { it.calculatedActualQty },
                                totalVarianceQty = draftItems.sumOf { it.varianceQty }
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 50.dp)
                        .testTag("inventory_save_report_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Slate950)
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(s.t("حفظ واعتماد تقرير الجرد", "Save Inventory Count Report"))
                }
            }
        }

        item(key = "inventory_history_header") {
            SectionHeaderRow(
                title = s.t("سجل جلسات الجرد", "Saved Count Sessions"),
                subtitle = s.t("${inventoryReports.size} جلسة", "${inventoryReports.size} sessions")
            )
        }

        if (inventoryReports.isEmpty()) {
            item(key = "inventory_empty") {
                EmptyStatePanel(
                    icon = Icons.Rounded.Inventory2,
                    title = s.t("لا توجد جلسات جرد محفوظة", "No Saved Count Reports"),
                    description = s.t(
                        "استورد ملف الرصيد الدفتري أو امسح الأصناف وحدد الكراتين والبوكسات والحبات أعلاه.",
                        "Import a book-balance Excel file or count cartons, boxes, and pieces above."
                    )
                )
            }
        } else {
            items(inventoryReports, key = { it.id }) { rep ->
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
                            Text("${rep.title} (${rep.sectionOrAisle.orEmpty()})", style = MaterialTheme.typography.titleSmall, color = Slate100)
                            Text(
                                "${s.t("فعلي:", "Act:")} ${rep.totalActualQty} / ${s.t("دفتري:", "Book:")} ${rep.totalBookQty} | ${s.t("انحراف:", "Var:")} ${rep.totalVarianceQty}",
                                style = MonoCodeStyle,
                                color = Slate300
                            )
                        }
                        IconButton(
                            onClick = { onDeleteInventoryReport(rep.id) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete Count Report", tint = Rose400)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InventoryStatChip(
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = Slate950,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Slate400)
            Text(value, style = MonoCodeStyle, color = accent)
        }
    }
}

@Composable
private fun UnitStepChip(
    label: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = Slate950,
        border = BorderStroke(1.dp, Slate700)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(
                onClick = onMinus,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Rounded.Remove, contentDescription = "Decrease $label", tint = Rose400)
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = Slate200)
            IconButton(
                onClick = onPlus,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Increase $label", tint = Emerald400)
            }
        }
    }
}
