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
import androidx.compose.ui.unit.dp
import com.example.model.AppUser
import com.example.model.ReceivingReport
import com.example.model.ReceivingSessionItem
import com.example.ui.WmsStrings
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

@Composable
fun ReceivingScreen(
    isRtl: Boolean,
    currentUser: AppUser,
    receivingReports: List<ReceivingReport>,
    onParsePoSpreadsheet: suspend (Uri, String) -> List<ReceivingSessionItem>,
    onSaveReceivingReport: (ReceivingReport) -> Unit,
    onDeleteReceivingReport: (String) -> Unit
) {
    val s = WmsStrings(isRtl)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var poNumber by remember { mutableStateOf("PO-${(1000..9999).random()}") }
    var supplierName by remember { mutableStateOf("") }
    var newItemCode by remember { mutableStateOf("") }
    var newItemName by remember { mutableStateOf("") }
    var newExpectedQty by remember { mutableStateOf("24") }
    var showCamera by remember { mutableStateOf(false) }

    val draftItems = remember {
        mutableStateListOf(
            ReceivingSessionItem(
                id = "rec-init-1",
                itemCode = "114110055",
                itemName = "طقم سكاكين ستيل فاخر 6 قطع",
                unit = "HDZ",
                expectedQty = 20,
                receivedQty = 0,
                status = "SHORTAGE"
            )
        )
    }
    val totalExpected by remember { derivedStateOf { draftItems.sumOf { it.expectedQty } } }
    val totalReceived by remember { derivedStateOf { draftItems.sumOf { it.receivedQty } } }
    val totalDamaged by remember { derivedStateOf { draftItems.sumOf { it.damagedQty } } }

    val poPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            var displayName = "PO_Inbound.xlsx"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && cursor.moveToFirst()) {
                    displayName = cursor.getString(col) ?: displayName
                }
            }
            scope.launch {
                val parsed = onParsePoSpreadsheet(uri, displayName)
                if (parsed.isNotEmpty()) {
                    draftItems.clear()
                    draftItems.addAll(parsed)
                }
            }
        }
    }

    if (showCamera) {
        CameraQrScannerModal(
            isRtl = isRtl,
            onDismiss = { showCamera = false },
            onBarcodeScanned = { code ->
                showCamera = false
                val idx = draftItems.indexOfFirst { it.itemCode == code }
                if (idx >= 0) {
                    val item = draftItems[idx]
                    val nextRec = item.receivedQty + 1
                    draftItems[idx] = item.copy(
                        receivedQty = nextRec,
                        status = when {
                            nextRec == item.expectedQty -> "EXACT"
                            nextRec > item.expectedQty -> "SURPLUS"
                            else -> "SHORTAGE"
                        }
                    )
                } else {
                    newItemCode = code
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
        item(key = "receiving_po_header") {
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
                        Text(
                            s.t("استلام التوريدات الواردة (PO)", "Inbound PO Receiving"),
                            style = MaterialTheme.typography.titleMedium,
                            color = Cyan400
                        )
                        OutlinedButton(
                            onClick = { poPicker.launch(arrayOf("*/*")) },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("receiving_import_po_button")
                        ) {
                            Icon(Icons.Rounded.UploadFile, contentDescription = null, tint = Cyan400)
                            Spacer(Modifier.width(6.dp))
                            Text(s.t("استيراد PO", "Import PO"), color = Slate100)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = poNumber,
                            onValueChange = { poNumber = it },
                            label = { Text(s.t("رقم أمر الشراء", "PO No.")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = supplierName,
                            onValueChange = { supplierName = it },
                            label = { Text(s.t("اسم المورد", "Supplier")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newItemCode,
                            onValueChange = { newItemCode = it },
                            label = { Text(s.t("كود الصنف", "Item Code")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = newExpectedQty,
                            onValueChange = { newExpectedQty = it.filter(Char::isDigit) },
                            label = { Text(s.t("المتوقع", "Expected")) },
                            singleLine = true,
                            modifier = Modifier.width(96.dp)
                        )
                        FilledIconButton(
                            onClick = { showCamera = true },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("receiving_camera_button"),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Indigo500)
                        ) {
                            Icon(Icons.Rounded.QrCodeScanner, contentDescription = "Scan Receiving Item")
                        }
                        FilledIconButton(
                            onClick = {
                                if (newItemCode.isNotBlank()) {
                                    val exp = newExpectedQty.toIntOrNull() ?: 1
                                    draftItems.add(
                                        ReceivingSessionItem(
                                            id = "rec-${UUID.randomUUID().toString().take(8)}",
                                            itemCode = newItemCode.trim(),
                                            itemName = newItemName.ifBlank { newItemCode.trim() },
                                            expectedQty = exp,
                                            receivedQty = exp,
                                            status = "EXACT"
                                        )
                                    )
                                    newItemCode = ""
                                    newItemName = ""
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("receiving_add_item_button"),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Emerald500, contentColor = Slate950)
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "Add Item")
                        }
                    }
                }
            }
        }

        items(draftItems, key = { it.id }) { item ->
            val idx = draftItems.indexOfFirst { it.id == item.id }
            val accent = when {
                item.damagedQty > 0 -> Rose400
                item.receivedQty == item.expectedQty -> Emerald400
                item.receivedQty > item.expectedQty -> Amber400
                else -> Cyan400
            }
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = Slate900,
                border = BorderStroke(1.dp, Slate800)
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
                            Text(item.itemName, style = MaterialTheme.typography.titleSmall, color = Slate100)
                            Text("${item.itemCode} • ${item.unit}", style = MonoCodeStyle, color = Cyan400)
                        }
                        StatusPill(item.status, accent)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${s.t("المستلم:", "Received:")} ${item.receivedQty}/${item.expectedQty} • ${s.t("التالف:", "Damaged:")} ${item.damagedQty}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate300
                        )
                        Row {
                            IconButton(
                                onClick = {
                                    if (idx >= 0) {
                                        val nextRec = item.receivedQty + 1
                                        draftItems[idx] = item.copy(
                                            receivedQty = nextRec,
                                            status = if (nextRec == item.expectedQty) "EXACT" else if (nextRec > item.expectedQty) "SURPLUS" else "SHORTAGE"
                                        )
                                    }
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Rounded.AddCircleOutline, contentDescription = "Add Received", tint = Emerald400)
                            }
                            IconButton(
                                onClick = {
                                    if (idx >= 0) {
                                        draftItems[idx] = item.copy(damagedQty = item.damagedQty + 1)
                                    }
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Rounded.ReportProblem, contentDescription = "Add Damaged", tint = Rose400)
                            }
                        }
                    }
                }
            }
        }

        item(key = "receiving_save_footer") {
            Text(
                text = "${s.t("ملخص الاستلام:", "Summary:")} $totalReceived / $totalExpected • ${s.t("تالف:", "Dmg:")} $totalDamaged",
                style = MonoCodeStyle,
                color = Slate300
            )
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    if (draftItems.isNotEmpty()) {
                        onSaveReceivingReport(
                            ReceivingReport(
                                id = "recv-${UUID.randomUUID().toString().take(8)}",
                                poNumber = poNumber.ifBlank { "PO-NEW" },
                                supplierName = supplierName.ifBlank { s.t("مورد عام", "General Supplier") },
                                createdAt = Instant.now().toString(),
                                auditorName = currentUser.name,
                                auditorId = currentUser.jobId,
                                status = if (draftItems.any { it.damagedQty > 0 || it.receivedQty != it.expectedQty }) "PARTIAL" else "ACCEPTED_FULL",
                                items = draftItems.toList(),
                                totalExpectedQty = draftItems.sumOf { it.expectedQty },
                                totalReceivedQty = draftItems.sumOf { it.receivedQty },
                                totalDamagedQty = draftItems.sumOf { it.damagedQty }
                            )
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 50.dp)
                    .testTag("receiving_save_report_button"),
                colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Slate950)
            ) {
                Icon(Icons.Rounded.Verified, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(s.t("حفظ واعتماد محضر الاستلام", "Save Receiving Report"))
            }
        }

        if (receivingReports.isNotEmpty()) {
            item(key = "receiving_reports_header") {
                SectionHeaderRow(title = s.t("سجل محاضر الاستلام", "Saved Receiving Reports"))
            }
            items(receivingReports, key = { it.id }) { rec ->
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
                            Text("${rec.poNumber} • ${rec.supplierName}", style = MaterialTheme.typography.titleSmall, color = Slate100)
                            Text(
                                "${s.t("المستلم:", "Received:")} ${rec.totalReceivedQty}/${rec.totalExpectedQty} | ${s.t("تالف:", "Dmg:")} ${rec.totalDamagedQty}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate400
                            )
                        }
                        IconButton(
                            onClick = { onDeleteReceivingReport(rec.id) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = Rose400)
                        }
                    }
                }
            }
        }
    }
}
