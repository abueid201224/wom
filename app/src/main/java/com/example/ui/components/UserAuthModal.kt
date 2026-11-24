package com.example.ui.components

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.model.AppUser
import com.example.model.ROLE_DEFINITIONS
import com.example.model.UserRole
import com.example.ui.WmsStrings
import com.example.ui.theme.*

@Composable
fun UserAuthModal(
    isRtl: Boolean,
    users: List<AppUser>,
    currentUser: AppUser,
    onDismiss: () -> Unit,
    onSelectOperator: (AppUser, String) -> Boolean,
    onAddOperator: (name: String, jobId: String, role: UserRole, pin: String) -> Unit
) {
    val s = WmsStrings(isRtl)
    var selectedCandidate by remember { mutableStateOf<AppUser?>(null) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    var showAddForm by remember { mutableStateOf(false) }

    var newName by remember { mutableStateOf("") }
    var newJobId by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("1234") }
    var newRole by remember { mutableStateOf(UserRole.WAREHOUSE_KEEPER) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp),
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
                        Icon(Icons.Rounded.Badge, contentDescription = null, tint = Emerald400)
                        Text(
                            s.t("مشغلو المستودع والصلاحيات", "Operator Profiles & Roles"),
                            style = MaterialTheme.typography.titleMedium,
                            color = Slate100
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("user_auth_close_button")
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Slate300)
                    }
                }

                if (selectedCandidate != null) {
                    val target = selectedCandidate!!
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = Slate950,
                        border = BorderStroke(1.dp, Indigo500.copy(alpha = 0.4f))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "${s.t("إدخال رمز PIN للمشغل:", "Enter PIN for")} ${target.name}",
                                style = MaterialTheme.typography.titleSmall,
                                color = Slate100
                            )
                            Text(
                                text = if (target.role == UserRole.SUPERVISOR)
                                    s.t("أدخل رمز المشرف المعتمد (الافتراضي قبل التعيين: 1234)", "Enter Supervisor PIN (default before setup: 1234)")
                                else
                                    s.t("الرمز الافتراضي للمدقق التجريبي والمشغلين: 1234", "Trial Auditor & Operator PIN: 1234"),
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate400
                            )
                            OutlinedTextField(
                                value = pinInput,
                                onValueChange = {
                                    pinInput = it
                                    pinError = false
                                },
                                label = { Text("PIN") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                isError = pinError,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("operator_pin_input")
                            )
                            if (pinError) {
                                Text(
                                    s.t("رمز الدخول غير صحيح", "Invalid PIN code"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Rose400
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { selectedCandidate = null },
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                ) {
                                    Text(s.t("رجوع", "Back"), color = Slate200)
                                }
                                Button(
                                    onClick = {
                                        if (onSelectOperator(target, pinInput)) {
                                            selectedCandidate = null
                                            pinInput = ""
                                            onDismiss()
                                        } else {
                                            pinError = true
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("confirm_operator_pin_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Slate950)
                                ) {
                                    Text(s.t("تبديل الحساب", "Switch"))
                                }
                            }
                        }
                    }
                } else if (showAddForm) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text(s.t("اسم الموظف", "Operator Name")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = newJobId,
                                onValueChange = { newJobId = it },
                                label = { Text(s.t("الرقم الوظيفي", "Job ID")) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = newPin,
                                onValueChange = { newPin = it },
                                label = { Text("PIN") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(UserRole.WAREHOUSE_KEEPER, UserRole.SUPERVISOR, UserRole.AUDITOR).forEach { r ->
                                FilterChip(
                                    selected = newRole == r,
                                    onClick = { newRole = r },
                                    label = {
                                        val def = ROLE_DEFINITIONS[r]
                                        Text(if (isRtl) def?.labelAr.orEmpty() else def?.labelEn.orEmpty(), maxLines = 1)
                                    }
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showAddForm = false },
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                            ) {
                                Text(s.t("إلغاء", "Cancel"), color = Slate200)
                            }
                            Button(
                                onClick = {
                                    if (newName.isNotBlank()) {
                                        onAddOperator(newName, newJobId, newRole, newPin)
                                        showAddForm = false
                                        newName = ""
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("save_new_operator_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Slate950)
                            ) {
                                Text(s.t("حفظ", "Save"))
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(users, key = { it.id }) { user ->
                            val isActive = user.id == currentUser.id
                            val def = ROLE_DEFINITIONS[user.role]
                            Surface(
                                onClick = {
                                    selectedCandidate = user
                                    pinInput = ""
                                    pinError = false
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("operator_card_${user.id}"),
                                shape = MaterialTheme.shapes.medium,
                                color = if (isActive) Emerald950 else Slate950,
                                border = BorderStroke(1.dp, if (isActive) Emerald500 else Slate800)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${user.name} (${user.jobId})",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = Slate100
                                        )
                                        Text(
                                            text = if (isRtl) def?.labelAr.orEmpty() else def?.labelEn.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Emerald400
                                        )
                                    }
                                    if (isActive) {
                                        StatusPill(s.t("نشط", "Active"), Emerald400)
                                    }
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { showAddForm = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("add_operator_open_button")
                    ) {
                        Icon(Icons.Rounded.PersonAdd, contentDescription = null, tint = Emerald400)
                        Spacer(Modifier.width(8.dp))
                        Text(s.t("إضافة مشغل جديد", "Add Operator"), color = Slate100)
                    }
                }
            }
        }
    }
}
