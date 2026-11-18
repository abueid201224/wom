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
import com.example.model.*
import com.example.ui.WmsStrings
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.SectionHeaderRow
import com.example.ui.components.StatusPill
import com.example.ui.theme.*
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PickingWaveScreen(
    isRtl: Boolean,
    masterItems: List<MasterInvoiceItem>,
    pickingWaves: List<BatchPickingWave>,
    workers: List<WarehouseWorker>,
    onCreateWave: (title: String, invoices: Set<String>) -> BatchPickingWave?,
    onSaveWave: (BatchPickingWave) -> Unit,
    onDeleteWave: (String) -> Unit,
    onUpsertWorker: (WarehouseWorker) -> Unit,
    onDeleteWorker: (String) -> Unit
) {
    val s = WmsStrings(isRtl)
    val availableInvoices by remember(masterItems) {
        derivedStateOf { masterItems.map { it.invoiceNo }.distinct() }
    }
    val selectedInvoices = remember { mutableStateMapOf<String, Boolean>() }
    val hasSelectedInvoices by remember {
        derivedStateOf { selectedInvoices.any { it.value } }
    }
    var waveTitle by remember { mutableStateOf("") }
    var showWorkersPanel by remember { mutableStateOf(false) }
    var newWorkerName by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "waves_generator_card") {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = Slate900,
                border = BorderStroke(1.dp, Indigo500.copy(alpha = 0.4f))
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
                            s.t("مولّد موجات الانتقاء وتجميع الفواتير", "Batch Picking Wave Generator"),
                            style = MaterialTheme.typography.titleMedium,
                            color = Indigo400,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = { showWorkersPanel = !showWorkersPanel },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("waves_toggle_workers_button")
                        ) {
                            Icon(Icons.Rounded.Groups, contentDescription = null, tint = Cyan400)
                            Spacer(Modifier.width(6.dp))
                            Text("${workers.size}", color = Slate100)
                        }
                    }

                    OutlinedTextField(
                        value = waveTitle,
                        onValueChange = { waveTitle = it },
                        label = { Text(s.t("اسم موجة الانتقاء", "Wave Title")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        s.t("اختر الفواتير لدمجها وتوزيعها حسب خبرة العمال:", "Select invoices to aggregate & assign:"),
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate300
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        availableInvoices.take(8).forEach { inv ->
                            val isSelected = selectedInvoices[inv] == true
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedInvoices[inv] = !isSelected },
                                label = { Text(inv, style = MonoCodeStyle) }
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val chosen = selectedInvoices.filterValues { it }.keys
                            if (chosen.isNotEmpty()) {
                                onCreateWave(waveTitle, chosen)
                                selectedInvoices.clear()
                                waveTitle = ""
                            }
                        },
                        enabled = hasSelectedInvoices,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("waves_create_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo500, contentColor = Slate100)
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(s.t("توليد الموجة وتفكيك العبوات", "Generate Picking Wave"))
                    }
                }
            }
        }

        if (showWorkersPanel) {
            item(key = "waves_workers_panel") {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = Slate900,
                    border = BorderStroke(1.dp, Cyan500.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(s.t("فريق محضّري المستودع", "Warehouse Pickers Team"), style = MaterialTheme.typography.titleSmall, color = Cyan400)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = newWorkerName,
                                onValueChange = { newWorkerName = it },
                                label = { Text(s.t("اسم العامل الجديد", "New Picker Name")) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (newWorkerName.isNotBlank()) {
                                        onUpsertWorker(
                                            WarehouseWorker(
                                                id = "w-${UUID.randomUUID().toString().take(6)}",
                                                name = newWorkerName.trim(),
                                                code = "W-${(10..99).random()}",
                                                experienceLevel = WorkerExperienceLevel.INTERMEDIATE,
                                                createdAt = Instant.now().toString()
                                            )
                                        )
                                        newWorkerName = ""
                                    }
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 52.dp)
                            ) {
                                Text(s.t("إضافة", "Add"))
                            }
                        }
                        workers.forEach { w ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${w.name} (${w.code}) • ${w.experienceLevel.name}", style = MaterialTheme.typography.bodySmall, color = Slate200)
                                IconButton(onClick = { onDeleteWorker(w.id) }, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove Worker", tint = Rose400)
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "waves_list_header") {
            SectionHeaderRow(
                title = s.t("الموجات النشطة والمحفوظة", "Active & Archived Waves"),
                subtitle = s.t("${pickingWaves.size} موجة", "${pickingWaves.size} waves")
            )
        }

        if (pickingWaves.isEmpty()) {
            item(key = "waves_empty") {
                EmptyStatePanel(
                    icon = Icons.Rounded.Layers,
                    title = s.t("لا توجد موجات انتقاء بعد", "No Picking Waves Yet"),
                    description = s.t("حدّد فاتورة أو أكثر أعلاه لتجميع الأصناف وتوزيع المجموعات تلقائياً.", "Select one or more invoices above to generate an aggregated picking wave.")
                )
            }
        } else {
            items(pickingWaves, key = { it.id }) { wave ->
                val isCompleted = wave.status == "COMPLETED"
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = Slate900,
                    border = BorderStroke(1.dp, if (isCompleted) Emerald500.copy(alpha = 0.4f) else Indigo500.copy(alpha = 0.35f))
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
                                Text("${wave.waveNo} • ${wave.title}", style = MaterialTheme.typography.titleSmall, color = Slate100)
                                Text(
                                    "${wave.totalInvoicesCount} ${s.t("فواتير", "inv")} | ${wave.totalCartons}CTN • ${wave.totalPacks}BOX • ${wave.totalPieces}PCS",
                                    style = MonoCodeStyle,
                                    color = Indigo400
                                )
                            }
                            StatusPill(wave.status, if (isCompleted) Emerald400 else Indigo400)
                        }

                        wave.groups.forEach { grp ->
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = Slate950,
                                border = BorderStroke(1.dp, Slate800)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(grp.groupName, style = MaterialTheme.typography.labelLarge, color = Slate100)
                                        Text(
                                            "${s.t("المكلّف:", "Assigned:")} ${grp.assignedWorkerName ?: s.t("غير معيّن", "Unassigned")} (${grp.difficulty.name})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Emerald400
                                        )
                                    }
                                    Text("${grp.totalCartons}C/${grp.totalPacks}B/${grp.totalPieces}P", style = MonoCodeStyle, color = Slate300)
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!isCompleted) {
                                Button(
                                    onClick = { onSaveWave(wave.copy(status = "COMPLETED", phase1Completed = true)) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Slate950)
                                ) {
                                    Text(s.t("اعتماد اكتمال الموجة", "Complete Wave"))
                                }
                            }
                            OutlinedButton(
                                onClick = { onDeleteWave(wave.id) },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete Wave", tint = Rose400)
                            }
                        }
                    }
                }
            }
        }
    }
}
