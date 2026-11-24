package com.example.ui.screens

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AppSettings
import com.example.model.AppUser
import com.example.model.ROLE_DEFINITIONS
import com.example.model.UserRole
import com.example.ui.WmsStrings
import com.example.ui.components.StatusPill
import com.example.ui.theme.*
import com.google.firebase.auth.FirebaseUser

@Composable
fun AuthScreen(
    isRtl: Boolean,
    firebaseUser: FirebaseUser?,
    currentUser: AppUser,
    settings: AppSettings,
    isAuthBusy: Boolean,
    onSignInGoogle: (Activity) -> Unit,
    onContinueOffline: () -> Unit,
    onConfigureSupervisorPin: (String) -> Boolean,
    onSwitchToTrialAuditor: () -> Unit,
    onToggleSupervisorGate: (Boolean) -> Unit,
    onOpenOperators: () -> Unit,
    onToggleLanguage: () -> Unit
) {
    val s = WmsStrings(isRtl)
    val activity = LocalContext.current as? Activity
    val roleDef = ROLE_DEFINITIONS[currentUser.role]
    val needsSupervisorPinSetup = currentUser.role == UserRole.SUPERVISOR && !settings.supervisorPinConfigured

    var pinSetupInput by remember { mutableStateOf("") }
    var pinSetupConfirm by remember { mutableStateOf("") }
    var pinSetupError by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Slate950
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onToggleLanguage,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("auth_language_toggle")
                ) {
                    Icon(Icons.Rounded.Translate, contentDescription = null, tint = Cyan400)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isRtl) "English" else "العربية", color = Slate100)
                }
            }

            Image(
                painter = painterResource(id = R.drawable.ic_wms_logo),
                contentDescription = "WMS Auditor Logo",
                modifier = Modifier
                    .size(86.dp)
                    .clip(RoundedCornerShape(22.dp))
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = s.t("مدير عمليات المستودع الذكي", "Smart WMS Auditor"),
                    style = MaterialTheme.typography.titleLarge,
                    color = Slate100
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = s.t(
                        "تدقيق الفواتير • الترحيل للاستكمال • الجرد • المرتجعات",
                        "Dispatch Audit • Deferral Workflow • Cycle Count • RMA"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400
                )
            }

            // Supervisor First-Time PIN Creation Card
            if (needsSupervisorPinSetup) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 520.dp)
                        .testTag("supervisor_pin_setup_card"),
                    shape = MaterialTheme.shapes.large,
                    color = Amber950,
                    border = BorderStroke(1.5.dp, Amber400)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.AdminPanelSettings, contentDescription = null, tint = Amber400)
                            Text(
                                text = s.t(
                                    "إعداد رمز المشرف عند الفتح (مطلوب للتمرير)",
                                    "Create Supervisor PIN on Launch"
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = Amber400,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = s.t(
                                "تم تحويل الحساب الحالي إلى حساب مشرف (${currentUser.name}). أنشئ رمز المشرف الخاص بك لاستخدامه عند طلب التمرير بعد المحاولة الخامسة أو إعادة فتح الفواتير.",
                                "Current account is configured as Supervisor (${currentUser.name}). Create your Supervisor PIN for overrides after the 5th deferral attempt."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate100
                        )
                        OutlinedTextField(
                            value = pinSetupInput,
                            onValueChange = { pinSetupInput = it; pinSetupError = null },
                            label = { Text(s.t("رمز المشرف الجديد (4+ أرقام)", "New Supervisor PIN (4+ chars)")) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("supervisor_pin_setup_input")
                        )
                        OutlinedTextField(
                            value = pinSetupConfirm,
                            onValueChange = { pinSetupConfirm = it; pinSetupError = null },
                            label = { Text(s.t("تأكيد رمز المشرف", "Confirm Supervisor PIN")) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("supervisor_pin_confirm_input")
                        )
                        if (pinSetupError != null) {
                            Text(pinSetupError!!, style = MaterialTheme.typography.bodySmall, color = Rose400)
                        }
                        Button(
                            onClick = {
                                val clean = pinSetupInput.trim()
                                when {
                                    clean.length < 4 -> pinSetupError = s.t("يجب إدخال 4 خانات على الأقل", "Minimum 4 characters required")
                                    clean != pinSetupConfirm.trim() -> pinSetupError = s.t("تأكيد الرمز غير متطابق", "PIN confirmation does not match")
                                    onConfigureSupervisorPin(clean) -> {
                                        pinSetupInput = ""
                                        pinSetupConfirm = ""
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("supervisor_pin_save_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Slate950)
                        ) {
                            Icon(Icons.Rounded.Key, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(s.t("حفظ وتفعيل رمز المشرف", "Save & Activate Supervisor PIN"), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Google Cloud Authentication Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp),
                shape = MaterialTheme.shapes.large,
                color = Slate900,
                border = BorderStroke(1.dp, Cyan500.copy(alpha = 0.35f))
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
                            Icon(Icons.Rounded.CloudSync, contentDescription = null, tint = Cyan400)
                            Text(
                                s.t("المصادقة السحابية الآمنة", "Cloud Identity & Sync"),
                                style = MaterialTheme.typography.titleMedium,
                                color = Slate100
                            )
                        }
                        if (firebaseUser != null) {
                            StatusPill(s.t("متصل", "Signed In"), Emerald400)
                        }
                    }

                    Text(
                        text = if (firebaseUser != null) {
                            "${firebaseUser.displayName ?: "Google User"} (${firebaseUser.email.orEmpty()})"
                        } else {
                            s.t(
                                "سجّل الدخول بحساب Google لحفظ تقارير الفروقات والمرتجعات في سحابة Firestore ومزامنتها عبر الأجهزة.",
                                "Sign in with Google to protect and sync warehouse reports across devices via Firebase Firestore."
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate300
                    )

                    if (isAuthBusy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Cyan400)
                    }

                    Button(
                        onClick = {
                            if (firebaseUser != null) onContinueOffline()
                            else activity?.let(onSignInGoogle)
                        },
                        enabled = !isAuthBusy && !needsSupervisorPinSetup,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 50.dp)
                            .testTag("auth_google_signin_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Emerald500,
                            contentColor = Slate950
                        )
                    ) {
                        Icon(
                            if (firebaseUser != null) Icons.AutoMirrored.Rounded.ArrowForward else Icons.Rounded.AccountCircle,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (firebaseUser != null)
                                s.t("الدخول إلى لوحة العمليات", "Enter Warehouse Dashboard")
                            else
                                s.t("تسجيل الدخول بحساب Google", "Sign in with Google"),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Local Operator, Trial Auditor & Supervisor Gate Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp),
                shape = MaterialTheme.shapes.large,
                color = Slate900,
                border = BorderStroke(1.dp, Slate800)
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                s.t("الحساب النشط والصلاحيات", "Active Account & Role"),
                                style = MaterialTheme.typography.titleSmall,
                                color = Slate100
                            )
                            Text(
                                "${currentUser.name} • ${if (isRtl) roleDef?.labelAr else roleDef?.labelEn} (${currentUser.jobId})",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (currentUser.role == UserRole.SUPERVISOR) Amber400 else Emerald400
                            )
                        }
                        TextButton(
                            onClick = onOpenOperators,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("auth_switch_role_button")
                        ) {
                            Text(s.t("تبديل الحساب", "Switch"), color = Cyan400)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusPill(
                            label = if (currentUser.role == UserRole.AUDITOR)
                                s.t("يعتمد وينهي الجلسة", "Can Close Session")
                            else
                                s.t("الإنهاء للمدقق فقط", "Auditor Closes Session"),
                            color = if (currentUser.role == UserRole.AUDITOR) Emerald400 else Amber400
                        )
                        StatusPill(
                            label = if (settings.supervisorPinConfigured)
                                s.t("رمز المشرف مفعّل", "Supervisor PIN Set")
                            else
                                s.t("رمز المشرف غير معيّن", "PIN Not Set"),
                            color = if (settings.supervisorPinConfigured) Cyan400 else Rose400
                        )
                    }

                    // Trial Mode Toggle for Supervisor Override Gate
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                s.t("إلزام تمرير رمز المشرف (بعد 5 محاولات)", "Enforce Supervisor PIN Override"),
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate200
                            )
                            Text(
                                s.t("عطّله مؤقتاً للتجربة والاختبار الميداني", "Disable temporarily during trial testing"),
                                style = MaterialTheme.typography.labelSmall,
                                color = Slate400
                            )
                        }
                        Switch(
                            checked = settings.requireSupervisorOverride,
                            onCheckedChange = onToggleSupervisorGate,
                            modifier = Modifier.testTag("auth_supervisor_gate_switch")
                        )
                    }

                    OutlinedButton(
                        onClick = onContinueOffline,
                        enabled = !needsSupervisorPinSetup,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("auth_continue_offline_button")
                    ) {
                        Icon(Icons.Rounded.OfflineBolt, contentDescription = null, tint = Emerald400)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (needsSupervisorPinSetup)
                                s.t("عيّن رمز المشرف أولاً للمتابعة", "Set Supervisor PIN Above First")
                            else
                                s.t("الدخول بالحساب الحالي (أوفلاين)", "Continue with Current Account"),
                            color = Slate100
                        )
                    }

                    if (currentUser.role != UserRole.AUDITOR) {
                        Button(
                            onClick = {
                                onSwitchToTrialAuditor()
                                onContinueOffline()
                            },
                            enabled = !needsSupervisorPinSetup,
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("auth_trial_auditor_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Indigo500,
                                contentColor = Slate100
                            )
                        ) {
                            Icon(Icons.Rounded.VerifiedUser, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                s.t(
                                    "الدخول بحساب المدقق التجريبي الافتراضي",
                                    "Enter with Default Trial Auditor"
                                ),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
