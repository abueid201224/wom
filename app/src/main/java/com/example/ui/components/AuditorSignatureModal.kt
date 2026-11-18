package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.ui.WmsStrings
import com.example.ui.theme.*
import java.io.ByteArrayOutputStream

@Composable
fun AuditorSignatureModal(
    isRtl: Boolean,
    auditorName: String,
    onDismiss: () -> Unit,
    onSignatureSaved: (String) -> Unit
) {
    val s = WmsStrings(isRtl)
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize(600, 260)) }

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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.Draw, contentDescription = null, tint = Emerald400)
                    Column {
                        Text(
                            s.t("التوقيع الإلكتروني المعتمد", "Certified Auditor Signature"),
                            style = MaterialTheme.typography.titleMedium,
                            color = Slate100
                        )
                        Text(
                            auditorName,
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate400
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.5.dp, Slate600, RoundedCornerShape(12.dp))
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    currentStroke = listOf(start)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    currentStroke = currentStroke + change.position
                                },
                                onDragEnd = {
                                    if (currentStroke.isNotEmpty()) {
                                        strokes.add(currentStroke)
                                        currentStroke = emptyList()
                                    }
                                }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val all = strokes + listOf(currentStroke)
                        for (pts in all) {
                            if (pts.size >= 2) {
                                val path = Path().apply {
                                    moveTo(pts.first().x, pts.first().y)
                                    for (i in 1 until pts.size) {
                                        lineTo(pts[i].x, pts[i].y)
                                    }
                                }
                                drawPath(
                                    path = path,
                                    color = Color(0xFF0F172A),
                                    style = Stroke(
                                        width = 5f,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            }
                        }
                    }
                    if (strokes.isEmpty() && currentStroke.isEmpty()) {
                        Text(
                            text = s.t("ارسم توقيعك بالإصبع هنا", "Sign with finger here"),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF64748B),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            strokes.clear()
                            currentStroke = emptyList()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("signature_clear_button")
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = Slate300)
                        Spacer(Modifier.width(6.dp))
                        Text(s.t("مسح", "Clear"), color = Slate200)
                    }
                    Button(
                        onClick = {
                            val b64 = renderSignatureToBase64(strokes, canvasSize)
                            onSignatureSaved(b64)
                        },
                        enabled = strokes.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("signature_save_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Slate950)
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(s.t("اعتماد التوقيع", "Save Signature"))
                    }
                }
            }
        }
    }
}

private val Slate600 = Color(0xFF475569)

private fun renderSignatureToBase64(strokes: List<List<Offset>>, size: IntSize): String {
    val w = size.width.coerceAtLeast(240)
    val h = size.height.coerceAtLeast(120)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)

    val paint = AndroidPaint().apply {
        color = android.graphics.Color.rgb(15, 23, 42)
        strokeWidth = 6f
        style = AndroidPaint.Style.STROKE
        strokeCap = AndroidPaint.Cap.ROUND
        strokeJoin = AndroidPaint.Join.ROUND
        isAntiAlias = true
    }

    for (pts in strokes) {
        if (pts.size < 2) continue
        val path = AndroidPath()
        path.moveTo(pts.first().x, pts.first().y)
        for (i in 1 until pts.size) {
            path.lineTo(pts[i].x, pts[i].y)
        }
        canvas.drawPath(path, paint)
    }

    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}
