package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.AppSettings
import com.example.model.AppUser
import com.example.model.UserRole
import com.example.ui.WmsStrings
import com.example.ui.components.AuditorSignatureModal
import com.example.ui.components.StatusPill
import com.example.ui.components.SupervisorPinSetupDialog
import com.example.ui.theme.*
import com.google.firebase.auth.FirebaseUser

@Composable
fun SettingsScreen(
    isRtl: Boolean,
    settings: AppSettings,
    currentUser: AppUser,
    firebaseUser: FirebaseUser?,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onConfigureSupervisorPin: (String) -> Boolean = { false },
    onSwitchToTrialAuditor: () -> Unit = {},
    onOpenOperators: () -> Unit,
    onOpenCloudSync: () -> Unit,
    onOpenGuide: () -> Unit
) {
    val s = WmsStrings(isRtl)
    var showSignatureModal by remember { mutableStateOf(false) }
    var showSupervisorPinModal by remember { mutableStateOf(false) }

    if (showSignatureModal) {
        AuditorSignatureModal(
            isRtl = isRtl,
            auditorName = settings.auditorName,
            onDismiss = { showSignatureModal = false },
            onSignatureSaved = { b64 ->
                showSignatureModal = false
                onUpdateSettings { it.copy(auditorSignatureBase64 = b64) }
            }
        )
    }

    if (showSupervisorPinModal) {
        SupervisorPinSetupDialog(
            isRtl = isRtl,
            isConfigured = settings.supervisorPinConfigured,
            onSavePin = onConfigureSupervisorPin,
            onDismiss = { showSupervisorPinModal = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Language & Profile Card
        Surface(
            shape = MaterialTheme.shapes.large,
            color = Slate900,
            border = BorderStroke(1.dp, Slate800)
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
                        Text(currentUser.name, style = MaterialTheme.typography.titleMedium, color = Slate100)
                        Text("${currentUser.jobId} • ${settings.auditorTitle}", style = MaterialTheme.typography.bodySmall, color = Emerald400)
                    }
                    OutlinedButton(
                        onClick = onOpenOperators,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("settings_switch_operator_button")
                    ) {
                        Text(s.t("تبديل الحساب", "Switch Role"), color = Cyan400)
                    }
                }

                if (currentUser.role != UserRole.AUDITOR) {
                    OutlinedButton(
                        onClick = onSwitchToTrialAuditor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("settings_switch_trial_auditor_button")
                    ) {
                        Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = Indigo400)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            s.t("التبديل إلى حساب المدقق التجريبي الافتراضي", "Switch to Trial Auditor Account"),
                            color = Indigo400
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.t("لغة الواجهة (العربية / English)", "Interface Language"), color = Slate200)
                    SegmentedButtonRowCompact(
                        isArabic = settings.language == "ar",
                        onSelectLanguage = { lang -> onUpdateSettings { it.copy(language = lang) } }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(s.t("التوقيع الإلكتروني للمدقق", "Saved Digital Signature"), color = Slate200)
                        Text(
                            if (settings.auditorSignatureBase64.isNullOrBlank()) s.t("غير مسجل", "Not recorded") else s.t("محفوظ ومعتمد", "Saved & Active"),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (settings.auditorSignatureBase64.isNullOrBlank()) Amber400 else Emerald400
                        )
                    }
                    OutlinedButton(
                        onClick = { showSignatureModal = true },
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("settings_signature_button")
                    ) {
                        Icon(Icons.Rounded.Draw, contentDescription = null, tint = Emerald400)
                        Spacer(Modifier.width(6.dp))
                        Text(s.t("توقيع", "Sign"), color = Slate100)
                    }
                }
            }
        }

        // Supervisor Governance & Trial Mode Toggle Card
        Surface(
            shape = MaterialTheme.shapes.large,
            color = Slate900,
            border = BorderStroke(1.dp, Amber500.copy(alpha = 0.4f))
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.AdminPanelSettings, contentDescription = null, tint = Amber400)
                        Text(
                            s.t("صلاحيات المشرف وفترة التجربة", "Supervisor Override & Trial Mode"),
                            style = MaterialTheme.typography.titleSmall,
                            color = Amber400
                        )
                    }
                    StatusPill(
                        label = if (settings.requireSupervisorOverride) s.t("مفعّل", "Enforced") else s.t("وضع التجربة", "Trial Bypass"),
                        color = if (settings.requireSupervisorOverride) Amber400 else Cyan400
                    )
                }

                SettingSwitchRow(
                    title = s.t(
                        "طلب تمرير المشرف برمزه (بعد المحاولة 5 وإعادة الفتح — عطّله للتجربة)",
                        "Require Supervisor PIN Override (after 5th deferral & reopen)"
                    ),
                    checked = settings.requireSupervisorOverride,
                    tag = "settings_supervisor_gate_switch"
                ) { checked -> onUpdateSettings { it.copy(requireSupervisorOverride = checked) } }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(s.t("رمز تمرير المشرف (PIN)", "Supervisor Override PIN"), color = Slate200)
                        Text(
                            if (settings.supervisorPinConfigured)
                                s.t("معيّن ومحفوظ بنجاح", "Configured & Active")
                            else
                                s.t("غير معيّن بعد (الافتراضي 1234)", "Not yet configured (default 1234)"),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (settings.supervisorPinConfigured) Emerald400 else Amber400
                        )
                    }
                    OutlinedButton(
                        onClick = { showSupervisorPinModal = true },
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("settings_configure_supervisor_pin_button")
                    ) {
                        Icon(Icons.Rounded.Key, contentDescription = null, tint = Amber400)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (settings.supervisorPinConfigured) s.t("تعديل الرمز", "Change PIN")
                            else s.t("تعيين الرمز", "Set PIN"),
                            color = Slate100
                        )
                    }
                }
            }
        }

        // Audio, Haptics & Scanner Controls
        Surface(
            shape = MaterialTheme.shapes.large,
            color = Slate900,
            border = BorderStroke(1.dp, Slate800)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(s.t("الصوت والماسح والكاميرا", "Audio, Haptics & Scanner"), style = MaterialTheme.typography.titleSmall, color = Emerald400)

                SettingSwitchRow(
                    title = s.t("التنبيهات الصوتية عند المسح", "Audio Scan Feedback"),
                    checked = settings.soundEnabled,
                    tag = "settings_sound_switch"
                ) { checked -> onUpdateSettings { it.copy(soundEnabled = checked) } }

                if (settings.soundEnabled) {
                    Slider(
                        value = settings.soundVolume,
                        onValueChange = { vol -> onUpdateSettings { it.copy(soundVolume = vol) } },
                        valueRange = 0.1f..1f
                    )
                }

                SettingSwitchRow(
                    title = s.t("الاهتزاز التفاعلي", "Vibration Feedback"),
                    checked = settings.vibrationEnabled,
                    tag = "settings_vibration_switch"
                ) { checked -> onUpdateSettings { it.copy(vibrationEnabled = checked) } }

                SettingSwitchRow(
                    title = s.t("تفعيل ماسح الكاميرا ML Kit", "Enable Camera QR Scanner"),
                    checked = settings.enableCameraQr,
                    tag = "settings_camera_switch"
                ) { checked -> onUpdateSettings { it.copy(enableCameraQr = checked) } }

                SettingSwitchRow(
                    title = s.t("تبديل الفاتورة تلقائياً عند مسح رقمها", "Auto-Switch on Invoice Scan"),
                    checked = settings.autoSwitchOnNewInvoice,
                    tag = "settings_autoswitch_switch"
                ) { checked -> onUpdateSettings { it.copy(autoSwitchOnNewInvoice = checked) } }

                SettingSwitchRow(
                    title = s.t("وضع أمان التبديل (يتطلب مسح الفاتورة مرتين خلال ثانيتين)", "Switch Guard (Require 2x scan within 2s)"),
                    checked = settings.requireDoubleScanToSwitchInvoice,
                    tag = "settings_double_scan_switch"
                ) { checked -> onUpdateSettings { it.copy(requireDoubleScanToSwitchInvoice = checked) } }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${s.t("حد تنبيه الباركود الطويل:", "Long Barcode Threshold:")} ${settings.longBarcodeThreshold}",
                        color = Slate200
                    )
                    Row {
                        IconButton(
                            onClick = { onUpdateSettings { it.copy(longBarcodeThreshold = (it.longBarcodeThreshold - 1).coerceAtLeast(6)) } },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Rounded.Remove, contentDescription = "Decrease Threshold", tint = Slate300)
                        }
                        IconButton(
                            onClick = { onUpdateSettings { it.copy(longBarcodeThreshold = (it.longBarcodeThreshold + 1).coerceAtMost(24)) } },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "Increase Threshold", tint = Emerald400)
                        }
                    }
                }
            }
        }

        // Cloud & Guide Actions
        Surface(
            shape = MaterialTheme.shapes.large,
            color = Slate900,
            border = BorderStroke(1.dp, Slate800)
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
                    Column {
                        Text(s.t("حالة المزامنة السحابية Firebase", "Firebase Cloud Status"), style = MaterialTheme.typography.titleSmall, color = Slate100)
                        Text(firebaseUser?.email ?: s.t("وضع الأوفلاين المحلي", "Local Offline Mode"), style = MaterialTheme.typography.bodySmall, color = Slate400)
                    }
                    StatusPill(if (firebaseUser != null) "CONNECTED" else "OFFLINE", if (firebaseUser != null) Emerald400 else Amber400)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onOpenCloudSync,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("settings_cloud_sync_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan500, contentColor = Slate950)
                    ) {
                        Icon(Icons.Rounded.CloudSync, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(s.t("إدارة السحابة", "Cloud Sync"))
                    }
                    OutlinedButton(
                        onClick = onOpenGuide,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("settings_guide_button")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null, tint = Indigo400)
                        Spacer(Modifier.width(6.dp))
                        Text(s.t("الدليل", "Guide"), color = Slate100)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = Slate200, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
private fun SegmentedButtonRowCompact(
    isArabic: Boolean,
    onSelectLanguage: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = isArabic,
            onClick = { onSelectLanguage("ar") },
            label = { Text("العربية") }
        )
        FilterChip(
            selected = !isArabic,
            onClick = { onSelectLanguage("en") },
            label = { Text("EN") }
        )
    }
}
