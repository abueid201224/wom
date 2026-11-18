package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.WmsStrings
import com.example.ui.theme.*
import com.example.ui.viewmodel.PendingLongBarcodePrompt
import com.example.ui.viewmodel.ScanFeedbackBanner

@Composable
fun WmsKpiCard(
    title: String,
    value: String,
    subtitle: String? = null,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.let {
            if (onClick != null) it.testTag("kpi_${title.lowercase().replace(" ", "_")}") else it
        },
        shape = MaterialTheme.shapes.medium,
        color = Slate900,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.28f)),
        onClick = onClick ?: {},
        enabled = onClick != null
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
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = value,
                    style = MonoMetricStyle,
                    color = accentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = Slate100,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun StatusPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SectionHeaderRow(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Slate100
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400
                )
            }
        }
        if (actionLabel != null && onActionClick != null) {
            TextButton(
                onClick = onActionClick,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text(actionLabel, color = Emerald400)
            }
        }
    }
}

@Composable
fun EmptyStatePanel(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = Slate900,
        border = BorderStroke(1.dp, Slate800)
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Slate800),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = Slate400,
                    modifier = Modifier.size(28.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Slate200
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Slate400
            )
        }
    }
}

@Composable
fun ScanBannerCard(
    banner: ScanFeedbackBanner,
    isRtl: Boolean,
    onDismiss: () -> Unit
) {
    val (bg, border, icon, text) = when (banner) {
        is ScanFeedbackBanner.Success -> Quad(
            Emerald950, Emerald400, Icons.Rounded.CheckCircle,
            if (isRtl) banner.messageAr else banner.messageEn
        )
        is ScanFeedbackBanner.Warning -> Quad(
            Amber950, Amber400, Icons.Rounded.WarningAmber,
            if (isRtl) banner.messageAr else banner.messageEn
        )
        is ScanFeedbackBanner.Error -> Quad(
            Rose950, Rose400, Icons.Rounded.ErrorOutline,
            if (isRtl) banner.messageAr else banner.messageEn
        )
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("scan_feedback_banner"),
        shape = MaterialTheme.shapes.medium,
        color = bg,
        border = BorderStroke(1.5.dp, border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = null, tint = border)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = Slate100,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = Slate300)
            }
        }
    }
}

@Composable
fun LongBarcodePolicyDialog(
    prompt: PendingLongBarcodePrompt,
    isRtl: Boolean,
    onDecision: (Boolean) -> Unit
) {
    val s = WmsStrings(isRtl)
    AlertDialog(
        onDismissRequest = { onDecision(false) },
        containerColor = Slate900,
        titleContentColor = Amber400,
        textContentColor = Slate200,
        icon = {
            Icon(Icons.Rounded.QrCode2, contentDescription = null, tint = Amber400)
        },
        title = {
            Text(s.t("تنبيه طول الباركود", "Long Barcode Detected"))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    s.t(
                        "تم مسح رمز باركود بطول (${prompt.length}) خانة يتجاوز الحد المسموح. قد يكون رقم فاتورة أو تسلسل دمج:",
                        "Scanned barcode length (${prompt.length}) exceeds threshold. Choose session policy:"
                    )
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = Slate950,
                    border = BorderStroke(1.dp, Slate700)
                ) {
                    Text(
                        text = prompt.code,
                        style = MonoCodeStyle,
                        color = Amber400,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onDecision(true) },
                modifier = Modifier.testTag("long_barcode_allow_button"),
                colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Slate950)
            ) {
                Text(s.t("السماح لهذه الفاتورة", "Allow for Session"))
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { onDecision(false) },
                modifier = Modifier.testTag("long_barcode_block_button")
            ) {
                Text(s.t("رفض وحظر", "Block"), color = Rose400)
            }
        }
    )
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
