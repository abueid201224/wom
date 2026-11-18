package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AssignmentReturn
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.AppUser
import com.example.model.ROLE_DEFINITIONS
import com.example.ui.WmsStrings
import com.example.ui.navigation.WmsRoute
import com.example.ui.theme.*

private data class DrawerNavItem(
    val route: WmsRoute,
    val tag: String,
    val labelAr: String,
    val labelEn: String,
    val icon: ImageVector,
    val accent: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerticalServicesDrawer(
    isRtl: Boolean,
    currentUser: AppUser,
    activeRoute: WmsRoute,
    onDismiss: () -> Unit,
    onNavigate: (WmsRoute) -> Unit,
    onOpenExcel: () -> Unit,
    onOpenCloud: () -> Unit,
    onOpenOperators: () -> Unit,
    onOpenGuide: () -> Unit
) {
    val s = WmsStrings(isRtl)
    val roleLabel = ROLE_DEFINITIONS[currentUser.role]?.let {
        if (isRtl) it.labelAr else it.labelEn
    }.orEmpty()

    val navItems = listOf(
        DrawerNavItem(WmsRoute.Dashboard, "drawer_dashboard", "لوحة التحكم", "Dashboard", Icons.Rounded.Dashboard, Emerald400),
        DrawerNavItem(WmsRoute.ActiveAudit, "drawer_audit", "التدقيق النشط", "Active Audit", Icons.Rounded.QrCodeScanner, Emerald500),
        DrawerNavItem(WmsRoute.PickingWave, "drawer_waves", "موجات الانتقاء", "Picking Waves", Icons.Rounded.Layers, Indigo400),
        DrawerNavItem(WmsRoute.Receiving, "drawer_receiving", "استلام التوريدات", "Receiving (PO)", Icons.Rounded.LocalShipping, Cyan400),
        DrawerNavItem(WmsRoute.Returns, "drawer_returns", "إدارة المرتجعات RMA", "RMA Returns", Icons.AutoMirrored.Rounded.AssignmentReturn, Amber400),
        DrawerNavItem(WmsRoute.InventoryCount, "drawer_inventory", "الجرد وتفكيك العبوات", "Cycle Count", Icons.Rounded.Inventory2, Purple400),
        DrawerNavItem(WmsRoute.ErrorReport, "drawer_reports", "تقرير الفروقات ISA 500", "Error Reports", Icons.Rounded.Assessment, Rose400),
        DrawerNavItem(WmsRoute.MasterDatabase, "drawer_database", "القاعدة الرئيسية والقواعد", "Master DB & Rules", Icons.Rounded.Storage, Blue400),
        DrawerNavItem(WmsRoute.Settings, "drawer_settings", "الإعدادات والملف", "Settings", Icons.Rounded.Settings, Slate300)
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Slate900,
        contentColor = Slate100
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = Slate950,
                border = BorderStroke(1.dp, Slate800)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(currentUser.name, style = MaterialTheme.typography.titleSmall, color = Slate100)
                        Text("$roleLabel • ${currentUser.jobId}", style = MaterialTheme.typography.bodySmall, color = Emerald400)
                    }
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onOpenOperators()
                        },
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("drawer_switch_operator_button")
                    ) {
                        Text(s.t("تبديل المشغل", "Switch Role"), color = Slate200)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickDrawerToolButton(
                    label = s.t("إكسيل", "Excel"),
                    icon = Icons.Rounded.TableChart,
                    accent = Emerald400,
                    tag = "drawer_excel_button",
                    modifier = Modifier.weight(1f)
                ) {
                    onDismiss(); onOpenExcel()
                }
                QuickDrawerToolButton(
                    label = s.t("السحابة", "Cloud"),
                    icon = Icons.Rounded.CloudSync,
                    accent = Cyan400,
                    tag = "drawer_cloud_button",
                    modifier = Modifier.weight(1f)
                ) {
                    onDismiss(); onOpenCloud()
                }
                QuickDrawerToolButton(
                    label = s.t("الدليل", "Guide"),
                    icon = Icons.AutoMirrored.Rounded.MenuBook,
                    accent = Indigo400,
                    tag = "drawer_guide_button",
                    modifier = Modifier.weight(1f)
                ) {
                    onDismiss(); onOpenGuide()
                }
            }

            LazyColumn(
                modifier = Modifier.heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(navItems, key = { it.tag }) { item ->
                    val selected = item.route == activeRoute
                    Surface(
                        onClick = {
                            onDismiss()
                            onNavigate(item.route)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(item.tag),
                        shape = MaterialTheme.shapes.medium,
                        color = if (selected) item.accent.copy(alpha = 0.14f) else Slate950,
                        border = BorderStroke(1.dp, if (selected) item.accent else Slate800)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(item.icon, contentDescription = null, tint = item.accent)
                            Text(
                                text = if (isRtl) item.labelAr else item.labelEn,
                                style = MaterialTheme.typography.titleSmall,
                                color = Slate100,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Slate400)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun QuickDrawerToolButton(
    label: String,
    icon: ImageVector,
    accent: Color,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .testTag(tag),
        shape = MaterialTheme.shapes.medium,
        color = Slate950,
        border = BorderStroke(1.dp, Slate800)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = label, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = Slate100)
        }
    }
}
