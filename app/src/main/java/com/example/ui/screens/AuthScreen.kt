package com.example.ui.screens

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AppUser
import com.example.model.ROLE_DEFINITIONS
import com.example.ui.WmsStrings
import com.example.ui.components.StatusPill
import com.example.ui.theme.*
import com.google.firebase.auth.FirebaseUser

@Composable
fun AuthScreen(
    isRtl: Boolean,
    firebaseUser: FirebaseUser?,
    currentUser: AppUser,
    isAuthBusy: Boolean,
    onSignInGoogle: (Activity) -> Unit,
    onContinueOffline: () -> Unit,
    onOpenOperators: () -> Unit,
    onToggleLanguage: () -> Unit
) {
    val s = WmsStrings(isRtl)
    val activity = LocalContext.current as? Activity
    val roleDef = ROLE_DEFINITIONS[currentUser.role]

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
            verticalArrangement = Arrangement.spacedBy(18.dp)
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

            Spacer(Modifier.height(6.dp))

            Image(
                painter = painterResource(id = R.drawable.ic_wms_logo),
                contentDescription = "WMS Auditor Logo",
                modifier = Modifier
                    .size(92.dp)
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
                        "تدقيق الفواتير • الاستلام • الجرد • المرتجعات • موجات الانتقاء",
                        "Dispatch Audit • PO Receiving • Cycle Count • RMA • Wave Picking"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400
                )
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
                        enabled = !isAuthBusy,
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

            // Local Offline Operator Card
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
                        Column {
                            Text(
                                s.t("ملف المشغل الميداني (أوفلاين)", "Local Field Operator"),
                                style = MaterialTheme.typography.titleSmall,
                                color = Slate100
                            )
                            Text(
                                "${currentUser.name} • ${if (isRtl) roleDef?.labelAr else roleDef?.labelEn}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Emerald400
                            )
                        }
                        TextButton(
                            onClick = onOpenOperators,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("auth_switch_role_button")
                        ) {
                            Text(s.t("تغيير الدور", "Switch"), color = Cyan400)
                        }
                    }

                    OutlinedButton(
                        onClick = onContinueOffline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("auth_continue_offline_button")
                    ) {
                        Icon(Icons.Rounded.OfflineBolt, contentDescription = null, tint = Amber400)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            s.t("العمل أوفلاين 100% الآن", "Continue 100% Offline"),
                            color = Slate100
                        )
                    }
                }
            }
        }
    }
}
