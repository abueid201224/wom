package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.model.PendingSupervisorOverride
import com.example.model.SupervisorOverrideActionType
import com.example.ui.WmsStrings
import com.example.ui.theme.*

@Composable
fun SupervisorOverrideDialog(
    isRtl: Boolean,
    pending: PendingSupervisorOverride,
    onAuthorize: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    val s = WmsStrings(isRtl)
    var pinInput by remember(pending.invoiceNo, pending.attemptNumber, pending.actionType) { mutableStateOf("") }
    var pinError by remember(pending.invoiceNo, pending.attemptNumber, pending.actionType) { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .testTag("supervisor_override_dialog"),
            shape = MaterialTheme.shapes.extraLarge,
            color = Slate900,
            border = BorderStroke(1.5.dp, Amber400.copy(alpha = 0.75f))
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.AdminPanelSettings, contentDescription = null, tint = Amber400)
                        Text(
                            text = if (isRtl) pending.titleAr else pending.titleEn,
                            style = MaterialTheme.typography.titleMedium,
                            color = Slate100,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Slate300)
                    }
                }

                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = Amber950,
                    border = BorderStroke(1.dp, Amber500.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${s.t("الفاتورة:", "Invoice:")} ${pending.invoiceNo}",
                                style = MonoCodeStyle,
                                color = Amber400
                            )
                            StatusPill(
                                label = when (pending.actionType) {
                                    SupervisorOverrideActionType.DEFER_BEYOND_LIMIT ->
                                        s.t("محاولة #${pending.attemptNumber}", "Attempt #${pending.attemptNumber}")
                                    SupervisorOverrideActionType.REOPEN_COMPLETED_INVOICE ->
                                        s.t("إعادة فتح", "Reopen")
                                },
                                color = Amber400
                            )
                        }
                        Text(
                            text = if (isRtl) pending.messageAr else pending.messageEn,
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate100
                        )
                    }
                }

                OutlinedTextField(
                    value = pinInput,
                    onValueChange = {
                        pinInput = it
                        pinError = false
                    },
                    label = { Text(s.t("أدخل رمز المشرف للتمرير (Supervisor PIN)", "Enter Supervisor PIN to Authorize")) },
                    singleLine = true,
                    isError = pinError,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (!onAuthorize(pinInput)) {
                                pinError = true
                            }
                        }
                    ),
                    leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null, tint = Amber400) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("supervisor_override_pin_input")
                )

                if (pinError) {
                    Text(
                        text = s.t("رمز المشرف غير صحيح. يرجى المحاولة مرة أخرى.", "Invalid Supervisor PIN. Please try again."),
                        style = MaterialTheme.typography.bodySmall,
                        color = Rose400,
                        modifier = Modifier.testTag("supervisor_override_pin_error")
                    )
                }

                Text(
                    text = s.t(
                        "ملاحظة: يمكنك تعطيل بوابة تمرير المشرف مؤقتاً من الإعدادات بغرض التجربة والاختبار.",
                        "Note: You can temporarily disable Supervisor override enforcement in Settings for trial testing."
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = Slate400
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("supervisor_override_cancel_button")
                    ) {
                        Text(s.t("إلغاء", "Cancel"), color = Slate200)
                    }
                    Button(
                        onClick = {
                            if (!onAuthorize(pinInput)) {
                                pinError = true
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("supervisor_override_confirm_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Slate950)
                    ) {
                        Icon(Icons.Rounded.VerifiedUser, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(s.t("تمرير واعتماد", "Authorize"), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun SupervisorPinSetupDialog(
    isRtl: Boolean,
    isConfigured: Boolean,
    onSavePin: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    val s = WmsStrings(isRtl)
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 440.dp)
                .testTag("supervisor_pin_setup_dialog"),
            shape = MaterialTheme.shapes.extraLarge,
            color = Slate900,
            border = BorderStroke(1.5.dp, Indigo400.copy(alpha = 0.65f))
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
                        Icon(Icons.Rounded.LockReset, contentDescription = null, tint = Indigo400)
                        Text(
                            text = if (isConfigured) s.t("تحديث رمز المشرف", "Update Supervisor PIN")
                            else s.t("إنشاء رمز المشرف", "Create Supervisor PIN"),
                            style = MaterialTheme.typography.titleMedium,
                            color = Slate100
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Slate300)
                    }
                }

                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it; errorMsg = null },
                    label = { Text(s.t("رمز المشرف الجديد (4 أرقام على الأقل)", "New Supervisor PIN (min 4 chars)")) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("supervisor_pin_modal_input")
                )

                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { confirmPin = it; errorMsg = null },
                    label = { Text(s.t("تأكيد رمز المشرف", "Confirm Supervisor PIN")) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("supervisor_pin_modal_confirm_input")
                )

                if (errorMsg != null) {
                    Text(errorMsg!!, style = MaterialTheme.typography.bodySmall, color = Rose400)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(s.t("إلغاء", "Cancel"), color = Slate200)
                    }
                    Button(
                        onClick = {
                            val clean = pin.trim()
                            when {
                                clean.length < 4 -> errorMsg = s.t("يجب أن يكون الرمز 4 خانات على الأقل", "PIN must be at least 4 characters")
                                clean != confirmPin.trim() -> errorMsg = s.t("الرمزان غير متطابقين", "PIN entries do not match")
                                onSavePin(clean) -> onDismiss()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("supervisor_pin_modal_save_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Slate950)
                    ) {
                        Text(s.t("حفظ الرمز", "Save PIN"), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
