package com.example.ui.components

import android.app.Activity
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
import com.example.ui.WmsStrings
import com.example.ui.theme.*
import com.google.firebase.auth.FirebaseUser

@Composable
fun FirebaseSyncModal(
    isRtl: Boolean,
    firebaseUser: FirebaseUser?,
    cloudReturnsCount: Int,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onSignInGoogle: (Activity) -> Unit,
    onSignOutGoogle: () -> Unit,
    onBackupAll: () -> Unit,
    onRestoreReturns: () -> Unit
) {
    val s = WmsStrings(isRtl)
    val activity = LocalContext.current as? Activity

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
                        Icon(Icons.Rounded.CloudSync, contentDescription = null, tint = Cyan400)
                        Text(
                            s.t("المزامنة السحابية Firebase", "Firebase Cloud Sync"),
                            style = MaterialTheme.typography.titleMedium,
                            color = Slate100
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Slate300)
                    }
                }

                if (firebaseUser == null) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = Slate950,
                        border = BorderStroke(1.dp, Amber500.copy(alpha = 0.4f))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                s.t("تسجيل الدخول مطلوب للنسخ الاحتياطي", "Google Sign-In Required"),
                                style = MaterialTheme.typography.titleSmall,
                                color = Amber400
                            )
                            Text(
                                s.t(
                                    "يعمل التطبيق 100% أوفلاين، ولكن لمزامنة التقارير مع سحابة Firestore الآمنة يرجى ربط حساب Google.",
                                    "Offline-first mode is always active. Sign in with Google to sync reports to Firestore."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate300
                            )
                            Button(
                                onClick = { activity?.let(onSignInGoogle) },
                                enabled = !isBusy && activity != null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("cloud_modal_google_signin_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Slate950)
                            ) {
                                Icon(Icons.Rounded.AccountCircle, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(s.t("متابعة بحساب Google", "Sign in with Google"))
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = Emerald950,
                        border = BorderStroke(1.dp, Emerald500.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    firebaseUser.displayName ?: "Google User",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Slate100
                                )
                                Text(
                                    firebaseUser.email ?: firebaseUser.uid,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Emerald400
                                )
                            }
                            StatusPill("$cloudReturnsCount RMA", Cyan400)
                        }
                    }

                    if (isBusy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Cyan400)
                    }

                    Button(
                        onClick = onBackupAll,
                        enabled = !isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("cloud_backup_all_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan500, contentColor = Slate950)
                    ) {
                        Icon(Icons.Rounded.CloudUpload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(s.t("نسخ احتياطي كامل للسحابة", "Backup All Data to Cloud"))
                    }

                    OutlinedButton(
                        onClick = onRestoreReturns,
                        enabled = !isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("cloud_restore_returns_button")
                    ) {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = Emerald400)
                        Spacer(Modifier.width(8.dp))
                        Text(s.t("استعادة تقارير المرتجعات", "Restore Returns from Cloud"), color = Slate100)
                    }

                    TextButton(
                        onClick = onSignOutGoogle,
                        modifier = Modifier
                            .align(Alignment.End)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("cloud_signout_button")
                    ) {
                        Text(s.t("فصل حساب Google", "Disconnect Google Account"), color = Rose400)
                    }
                }
            }
        }
    }
}
