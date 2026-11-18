package com.example.ui.components

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.model.SyncMetadata
import com.example.ui.WmsStrings
import com.example.ui.theme.*

@Composable
fun ExcelSyncModal(
    isRtl: Boolean,
    syncMetadata: SyncMetadata,
    onDismiss: () -> Unit,
    onImportSpreadsheet: (Uri, String) -> Unit,
    onExportCsv: () -> Unit,
    onExportPdf: () -> Unit
) {
    val s = WmsStrings(isRtl)
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            var displayName = "imported_master.xlsx"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameCol >= 0 && cursor.moveToFirst()) {
                    displayName = cursor.getString(nameCol) ?: displayName
                }
            }
            onImportSpreadsheet(uri, displayName)
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = Slate900,
            border = BorderStroke(1.dp, Slate700)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.TableChart, contentDescription = null, tint = Emerald400)
                        Text(
                            s.t("مزامنة Excel و CSV", "Excel & CSV Sync"),
                            style = MaterialTheme.typography.titleMedium,
                            color = Slate100
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Slate300)
                    }
                }

                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = Slate950,
                    border = BorderStroke(1.dp, Slate800)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            s.t("القاعدة النشطة حالياً", "Current Active Dataset"),
                            style = MaterialTheme.typography.labelLarge,
                            color = Emerald400
                        )
                        Text(
                            text = "${s.t("الملف:", "File:")} ${syncMetadata.fileName ?: "Demo_Master_Invoices.xlsx"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate200
                        )
                        Text(
                            text = "${s.t("الفواتير:", "Invoices:")} ${syncMetadata.totalInvoices} | ${s.t("الأصناف:", "Items:")} ${syncMetadata.totalItems}",
                            style = MonoCodeStyle,
                            color = Slate300
                        )
                    }
                }

                Button(
                    onClick = {
                        filePicker.launch(
                            arrayOf(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "text/csv",
                                "text/comma-separated-values",
                                "*/*"
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("excel_pick_file_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Slate950)
                ) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(s.t("استيراد ملف XLSX أو CSV", "Import XLSX / CSV File"))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onExportCsv,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("excel_export_csv_button")
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, tint = Cyan400)
                        Spacer(Modifier.width(6.dp))
                        Text(s.t("تصدير CSV", "Export CSV"), color = Slate100)
                    }
                    OutlinedButton(
                        onClick = onExportPdf,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("excel_export_pdf_button")
                    ) {
                        Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = Rose400)
                        Spacer(Modifier.width(6.dp))
                        Text(s.t("تقرير PDF", "Audit PDF"), color = Slate100)
                    }
                }
            }
        }
    }
}
