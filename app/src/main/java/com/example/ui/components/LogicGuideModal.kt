package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.ui.WmsStrings
import com.example.ui.theme.*

@Composable
fun LogicGuideModal(
    isRtl: Boolean,
    onDismiss: () -> Unit
) {
    val s = WmsStrings(isRtl)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp),
            shape = MaterialTheme.shapes.large,
            color = Slate900,
            border = BorderStroke(1.dp, Slate700)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null, tint = Indigo400)
                        Text(
                            s.t("دليل قواعد التدقيق ISA 500", "ISA 500 & Packaging Guide"),
                            style = MaterialTheme.typography.titleMedium,
                            color = Slate100
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("logic_guide_close_button")
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Slate300)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GuideSection(
                        title = s.t("1. مطابقة الباركود والتدقيق اللحظي", "1. Real-Time Barcode Audit"),
                        body = s.t(
                            "يدعم النظام قارئ الباركود الخطي (Keyboard Wedge)، أجهزة PDA الصناعية، وكاميرا ML Kit. يتم كشف أخطاء التجميع العابرة للفواتير (Wrong-Pick) فوراً.",
                            "Supports HID Keyboard-Wedge scanners, PDA broadcast intents, and live CameraX ML Kit scanning with cross-invoice Wrong-Pick detection."
                        )
                    )
                    GuideSection(
                        title = s.t("2. محرك تفكيك العبوات (Packaging Matrix)", "2. Packaging Conversion Matrix"),
                        body = s.t(
                            "تحويل تلقائي بين وحدات النظام (كرتون / بوكس / دستة / نصف دستة / حبة) حسب نطاق الباركود (1141–9100) وتصنيف الصعوبة.",
                            "Automatic breakdown across Cartons, Boxes, Dozens (DZ/HDZ), and Pieces mapped across barcode ranges 1141–9100."
                        )
                    )
                    GuideSection(
                        title = s.t("3. تحكيم المرتجعات والمختبر (RMA)", "3. RMA & Lab Arbitration"),
                        body = s.t(
                            "فحص المرتجع إلى صالح لإعادة التخزين (RESTOCK) أو محوّل للمختبر (LAB) مع اعتماد توقيع المدقق الرقمي وإصدار إيصال PDF.",
                            "Routes returned items to RESTOCK or PENDING_LAB arbitration with digital auditor signature & PDF receipt output."
                        )
                    )
                    GuideSection(
                        title = s.t("4. الخصوصية أوفلاين + المزامنة السحابية", "4. Offline-First + Cloud Sync"),
                        body = s.t(
                            "جميع البيانات محفوظة محلياً في Room SQLite وتتوفر مزامنة اختيارية محمية بحساب Google عبر Cloud Firestore.",
                            "100% offline Room persistence with optional per-user encrypted Firebase Firestore backup via Google Sign-In."
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideSection(title: String, body: String) {
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
            Text(title, style = MaterialTheme.typography.titleSmall, color = Emerald400)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Slate300)
        }
    }
}
