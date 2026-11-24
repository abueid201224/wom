package com.example

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.cloud.configureAppCheck
import com.example.services.KeyboardWedgeDetector
import com.example.services.PdaBroadcastScanner
import com.example.ui.WmsStrings
import com.example.ui.components.*
import com.example.ui.navigation.WmsRoute
import com.example.ui.screens.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.WmsViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: WmsViewModel by viewModels()

    private val keyboardWedgeDetector = KeyboardWedgeDetector { scannedBarcode ->
        viewModel.processAuditScan(scannedBarcode)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyboardWedgeDetector.onKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        setContent {
            WmsAuditorAppRoot(viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WmsAuditorAppRoot(viewModel: WmsViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isRtl = settings.language == "ar"
    val s = WmsStrings(isRtl)

    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val roleConfig by viewModel.currentRoleConfig.collectAsStateWithLifecycle()
    val appUsers by viewModel.appUsers.collectAsStateWithLifecycle()
    val firebaseUser by viewModel.firebaseUser.collectAsStateWithLifecycle()
    val cloudReturnsCount by viewModel.cloudReturnsCount.collectAsStateWithLifecycle()
    val isCloudBusy by viewModel.isCloudBusy.collectAsStateWithLifecycle()
    val isAuthBusy by viewModel.isAuthBusy.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    val syncMetadata by viewModel.syncMetadata.collectAsStateWithLifecycle()
    val activeSession by viewModel.activeSession.collectAsStateWithLifecycle()
    val masterItems by viewModel.masterItems.collectAsStateWithLifecycle()
    val completedInvoices by viewModel.completedInvoices.collectAsStateWithLifecycle()
    val incompleteInvoices by viewModel.incompleteInvoices.collectAsStateWithLifecycle()
    val discrepancies by viewModel.discrepancies.collectAsStateWithLifecycle()
    val wrongPicks by viewModel.wrongPicks.collectAsStateWithLifecycle()
    val auditHistory by viewModel.auditHistory.collectAsStateWithLifecycle()
    val dailySnapshots by viewModel.dailySnapshots.collectAsStateWithLifecycle()
    val returnReports by viewModel.returnReports.collectAsStateWithLifecycle()
    val receivingReports by viewModel.receivingReports.collectAsStateWithLifecycle()
    val inventoryReports by viewModel.inventoryReports.collectAsStateWithLifecycle()
    val packagingRules by viewModel.packagingRules.collectAsStateWithLifecycle()
    val pickingWaves by viewModel.pickingWaves.collectAsStateWithLifecycle()
    val workers by viewModel.workers.collectAsStateWithLifecycle()
    val scanBanner by viewModel.scanBanner.collectAsStateWithLifecycle()
    val pendingLongBarcode by viewModel.pendingLongBarcode.collectAsStateWithLifecycle()
    val inventoryDraftItems by viewModel.inventoryDraftItems.collectAsStateWithLifecycle()
    val isInventoryImportBusy by viewModel.isInventoryImportBusy.collectAsStateWithLifecycle()
    val pdfExtractionState by viewModel.pdfExtractionState.collectAsStateWithLifecycle()
    val auditKpis by viewModel.auditScanningKpis.collectAsStateWithLifecycle()
    val sessionApprovalSummary by viewModel.sessionApprovalSummary.collectAsStateWithLifecycle()
    val showSessionApprovalModal by viewModel.showSessionApprovalModal.collectAsStateWithLifecycle()
    val pendingInvoiceSwitch by viewModel.pendingInvoiceSwitch.collectAsStateWithLifecycle()
    val pendingSupervisorOverride by viewModel.pendingSupervisorOverride.collectAsStateWithLifecycle()

    DisposableEffect(context) {
        val receiver = PdaBroadcastScanner.register(context) { scannedCode ->
            viewModel.processAuditScan(scannedCode)
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val isAuthScreen = currentDest?.hasRoute<WmsRoute.Auth>() == true

    var showDrawer by remember { mutableStateOf(false) }
    var showExcelModal by remember { mutableStateOf(false) }
    var showCloudModal by remember { mutableStateOf(false) }
    var showOperatorsModal by remember { mutableStateOf(false) }
    var showGuideModal by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusMessage) {
        val msg = statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    val activeRoute: WmsRoute = when {
        currentDest?.hasRoute<WmsRoute.ActiveAudit>() == true -> WmsRoute.ActiveAudit
        currentDest?.hasRoute<WmsRoute.Receiving>() == true -> WmsRoute.Receiving
        currentDest?.hasRoute<WmsRoute.Returns>() == true -> WmsRoute.Returns
        currentDest?.hasRoute<WmsRoute.ErrorReport>() == true -> WmsRoute.ErrorReport
        currentDest?.hasRoute<WmsRoute.InventoryCount>() == true -> WmsRoute.InventoryCount
        currentDest?.hasRoute<WmsRoute.PickingWave>() == true -> WmsRoute.PickingWave
        currentDest?.hasRoute<WmsRoute.MasterDatabase>() == true -> WmsRoute.MasterDatabase
        currentDest?.hasRoute<WmsRoute.Settings>() == true -> WmsRoute.Settings
        currentDest?.hasRoute<WmsRoute.Auth>() == true -> WmsRoute.Auth
        else -> WmsRoute.Dashboard
    }

    val navigateTo: (WmsRoute) -> Unit = { route ->
        navController.navigate(route) {
            launchSingleTop = true
        }
    }

    WmsAuditorTheme(language = settings.language) {
        if (showDrawer) {
            VerticalServicesDrawer(
                isRtl = isRtl,
                currentUser = currentUser,
                activeRoute = activeRoute,
                onDismiss = { showDrawer = false },
                onNavigate = navigateTo,
                onOpenExcel = { showExcelModal = true },
                onOpenCloud = { showCloudModal = true },
                onOpenOperators = { showOperatorsModal = true },
                onOpenGuide = { showGuideModal = true }
            )
        }
        if (showExcelModal) {
            ExcelSyncModal(
                isRtl = isRtl,
                syncMetadata = syncMetadata,
                onDismiss = { showExcelModal = false },
                onImportSpreadsheet = { uri, name -> viewModel.importMasterSpreadsheet(uri, name) },
                onExportCsv = { viewModel.exportDiscrepanciesCsv() },
                onExportPdf = { viewModel.exportDiscrepanciesPdf() }
            )
        }
        if (showCloudModal) {
            FirebaseSyncModal(
                isRtl = isRtl,
                firebaseUser = firebaseUser,
                cloudReturnsCount = cloudReturnsCount,
                isBusy = isCloudBusy,
                onDismiss = { showCloudModal = false },
                onSignInGoogle = { act -> viewModel.signInWithGoogle(act) },
                onSignOutGoogle = { viewModel.signOutGoogle() },
                onBackupAll = { viewModel.backupAllToCloud() },
                onRestoreReturns = { viewModel.restoreReturnsFromCloud() }
            )
        }
        if (showOperatorsModal) {
            UserAuthModal(
                isRtl = isRtl,
                users = appUsers,
                currentUser = currentUser,
                onDismiss = { showOperatorsModal = false },
                onSelectOperator = { usr, pin -> viewModel.selectLocalOperator(usr, pin) },
                onAddOperator = { n, j, r, p -> viewModel.addLocalOperator(n, j, r, p) }
            )
        }
        if (showGuideModal) {
            LogicGuideModal(isRtl = isRtl, onDismiss = { showGuideModal = false })
        }
        if (showSessionApprovalModal) {
            FullSessionApprovalDialog(
                isRtl = isRtl,
                auditorName = settings.auditorName,
                currentUser = currentUser,
                summary = sessionApprovalSummary,
                onDismiss = { viewModel.dismissSessionApprovalModal() },
                onApproveWithSignature = { sig -> viewModel.approveFullSessionReport(sig) },
                onSwitchToTrialAuditor = { viewModel.switchToTrialAuditor() },
                onExportPdf = { viewModel.exportDiscrepanciesPdf() }
            )
        }
        pendingSupervisorOverride?.let { overridePrompt ->
            SupervisorOverrideDialog(
                isRtl = isRtl,
                pending = overridePrompt,
                onAuthorize = { pin -> viewModel.authorizeSupervisorOverride(pin) },
                onDismiss = { viewModel.dismissSupervisorOverride() }
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp && !isAuthScreen

            Scaffold(
                containerColor = Slate950,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = {
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.imePadding()
                    )
                },
                topBar = {
                    if (!isAuthScreen) {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Slate900,
                                titleContentColor = Slate100
                            ),
                            navigationIcon = {
                                IconButton(
                                    onClick = { showDrawer = true },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("topbar_menu_button")
                                ) {
                                    Icon(Icons.Rounded.Menu, contentDescription = "Open Navigation Drawer", tint = Emerald400)
                                }
                            },
                            title = {
                                Text(
                                    text = if (activeSession != null)
                                        "${s.t("تدقيق", "Audit")} #${activeSession?.invoiceNo}"
                                    else
                                        s.t("مدقق المستودع", "WMS Auditor"),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1
                                )
                            },
                            actions = {
                                IconButton(
                                    onClick = {
                                        viewModel.updateSettings { it.copy(soundEnabled = !it.soundEnabled) }
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("topbar_sound_toggle")
                                ) {
                                    Icon(
                                        imageVector = if (settings.soundEnabled) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                                        contentDescription = s.t("تبديل الصوت", "Toggle Sound"),
                                        tint = if (settings.soundEnabled) Emerald400 else Rose400
                                    )
                                }
                                IconButton(
                                    onClick = { showExcelModal = true },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("topbar_excel_button")
                                ) {
                                    Icon(Icons.Rounded.TableChart, contentDescription = "Excel Sync", tint = Emerald400)
                                }
                                IconButton(
                                    onClick = { showCloudModal = true },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("topbar_cloud_button")
                                ) {
                                    Icon(
                                        Icons.Rounded.CloudSync,
                                        contentDescription = "Cloud Sync",
                                        tint = if (firebaseUser != null) Cyan400 else Slate400
                                    )
                                }
                            }
                        )
                    }
                },
                bottomBar = {
                    if (!isAuthScreen && !useRail) {
                        NavigationBar(
                            containerColor = Slate900,
                            windowInsets = WindowInsets.navigationBars
                        ) {
                            NavigationBarItem(
                                selected = activeRoute == WmsRoute.Dashboard,
                                onClick = { navigateTo(WmsRoute.Dashboard) },
                                icon = { Icon(Icons.Rounded.Dashboard, contentDescription = s.t("الرئيسية", "Home")) },
                                label = { Text(s.t("الرئيسية", "Home")) },
                                modifier = Modifier.testTag("nav_dashboard")
                            )
                            NavigationBarItem(
                                selected = activeRoute == WmsRoute.ActiveAudit,
                                onClick = { navigateTo(WmsRoute.ActiveAudit) },
                                icon = { Icon(Icons.Rounded.QrCodeScanner, contentDescription = s.t("التدقيق", "Audit")) },
                                label = { Text(s.t("التدقيق", "Audit")) },
                                modifier = Modifier.testTag("nav_audit")
                            )
                            NavigationBarItem(
                                selected = activeRoute == WmsRoute.PickingWave,
                                onClick = { navigateTo(WmsRoute.PickingWave) },
                                icon = { Icon(Icons.Rounded.Layers, contentDescription = s.t("الموجات", "Waves")) },
                                label = { Text(s.t("الموجات", "Waves")) },
                                modifier = Modifier.testTag("nav_waves")
                            )
                            NavigationBarItem(
                                selected = activeRoute == WmsRoute.ErrorReport,
                                onClick = { navigateTo(WmsRoute.ErrorReport) },
                                icon = { Icon(Icons.Rounded.Assessment, contentDescription = s.t("التقارير", "Reports")) },
                                label = { Text(s.t("التقارير", "Reports")) },
                                modifier = Modifier.testTag("nav_reports")
                            )
                            NavigationBarItem(
                                selected = activeRoute == WmsRoute.Settings,
                                onClick = { navigateTo(WmsRoute.Settings) },
                                icon = { Icon(Icons.Rounded.Settings, contentDescription = s.t("المزيد", "More")) },
                                label = { Text(s.t("المزيد", "More")) },
                                modifier = Modifier.testTag("nav_settings")
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useRail) {
                        NavigationRail(containerColor = Slate900) {
                            Spacer(Modifier.height(8.dp))
                            NavigationRailItem(
                                selected = activeRoute == WmsRoute.Dashboard,
                                onClick = { navigateTo(WmsRoute.Dashboard) },
                                icon = { Icon(Icons.Rounded.Dashboard, contentDescription = s.t("الرئيسية", "Home")) },
                                label = { Text(s.t("الرئيسية", "Home")) }
                            )
                            NavigationRailItem(
                                selected = activeRoute == WmsRoute.ActiveAudit,
                                onClick = { navigateTo(WmsRoute.ActiveAudit) },
                                icon = { Icon(Icons.Rounded.QrCodeScanner, contentDescription = s.t("التدقيق", "Audit")) },
                                label = { Text(s.t("التدقيق", "Audit")) }
                            )
                            NavigationRailItem(
                                selected = activeRoute == WmsRoute.PickingWave,
                                onClick = { navigateTo(WmsRoute.PickingWave) },
                                icon = { Icon(Icons.Rounded.Layers, contentDescription = s.t("الموجات", "Waves")) },
                                label = { Text(s.t("الموجات", "Waves")) }
                            )
                            NavigationRailItem(
                                selected = activeRoute == WmsRoute.ErrorReport,
                                onClick = { navigateTo(WmsRoute.ErrorReport) },
                                icon = { Icon(Icons.Rounded.Assessment, contentDescription = s.t("التقارير", "Reports")) },
                                label = { Text(s.t("التقارير", "Reports")) }
                            )
                            NavigationRailItem(
                                selected = activeRoute == WmsRoute.Settings,
                                onClick = { navigateTo(WmsRoute.Settings) },
                                icon = { Icon(Icons.Rounded.Settings, contentDescription = s.t("المزيد", "More")) },
                                label = { Text(s.t("المزيد", "More")) }
                            )
                        }
                    }

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 920.dp)
                        ) {
                            NavHost(
                                navController = navController,
                                startDestination = WmsRoute.Auth
                            ) {
                                composable<WmsRoute.Auth> {
                                    AuthScreen(
                                        isRtl = isRtl,
                                        firebaseUser = firebaseUser,
                                        currentUser = currentUser,
                                        settings = settings,
                                        isAuthBusy = isAuthBusy,
                                        onSignInGoogle = { act ->
                                            viewModel.signInWithGoogle(act) {
                                                navController.navigate(WmsRoute.Dashboard) {
                                                    popUpTo(WmsRoute.Auth) { inclusive = true }
                                                }
                                            }
                                        },
                                        onContinueOffline = {
                                            navController.navigate(WmsRoute.Dashboard) {
                                                popUpTo(WmsRoute.Auth) { inclusive = true }
                                            }
                                        },
                                        onConfigureSupervisorPin = { pin ->
                                            viewModel.configureSupervisorPin(pin)
                                        },
                                        onSwitchToTrialAuditor = {
                                            viewModel.switchToTrialAuditor()
                                        },
                                        onToggleSupervisorGate = { enabled ->
                                            viewModel.updateSettings { it.copy(requireSupervisorOverride = enabled) }
                                        },
                                        onOpenOperators = { showOperatorsModal = true },
                                        onToggleLanguage = {
                                            viewModel.updateSettings {
                                                it.copy(language = if (it.language == "ar") "en" else "ar")
                                            }
                                        }
                                    )
                                }

                                composable<WmsRoute.Dashboard> {
                                    WelcomeDashboardScreen(
                                        isRtl = isRtl,
                                        currentUser = currentUser,
                                        firebaseUser = firebaseUser,
                                        cloudReturnsCount = cloudReturnsCount,
                                        syncMetadata = syncMetadata,
                                        masterItems = masterItems,
                                        completedInvoices = completedInvoices,
                                        incompleteInvoices = incompleteInvoices,
                                        discrepancies = discrepancies,
                                        returnReports = returnReports,
                                        pickingWaves = pickingWaves,
                                        dailySnapshots = dailySnapshots,
                                        sessionSummary = sessionApprovalSummary,
                                        onNavigate = navigateTo,
                                        onResumeIncomplete = { inv ->
                                            viewModel.startOrOpenInvoice(inv)
                                            navigateTo(WmsRoute.ActiveAudit)
                                        },
                                        onCloseShift = { viewModel.openSessionApprovalModal() },
                                        onOpenExcel = { showExcelModal = true },
                                        onOpenCloud = { showCloudModal = true }
                                    )
                                }

                                composable<WmsRoute.ActiveAudit> {
                                    ActiveAuditScreen(
                                         isRtl = isRtl,
                                         settings = settings,
                                         activeSession = activeSession,
                                         masterItems = masterItems,
                                         completedInvoices = completedInvoices,
                                         incompleteInvoices = incompleteInvoices,
                                         auditKpis = auditKpis,
                                         sessionSummary = sessionApprovalSummary,
                                         scanBanner = scanBanner,
                                         pendingLongBarcode = pendingLongBarcode,
                                         pendingInvoiceSwitch = pendingInvoiceSwitch,
                                         onDismissBanner = { viewModel.clearScanBanner() },
                                         onResolveLongBarcode = { allow -> viewModel.resolvePendingLongBarcode(allow) },
                                         onConfirmPendingSwitch = { viewModel.confirmPendingInvoiceSwitch() },
                                         onDismissPendingSwitch = { viewModel.dismissPendingInvoiceSwitch() },
                                         onToggleSound = {
                                             viewModel.updateSettings { it.copy(soundEnabled = !it.soundEnabled) }
                                         },
                                         onToggleVibration = {
                                             viewModel.updateSettings { it.copy(vibrationEnabled = !it.vibrationEnabled) }
                                         },
                                         onOpenInvoice = { inv -> viewModel.startOrOpenInvoice(inv) },
                                         onProcessScan = { raw -> viewModel.processAuditScan(raw, bypassDebounce = true) },
                                         onAdjustQty = { key, delta -> viewModel.adjustAuditItemQty(key, delta) },
                                         onSuspendSession = { viewModel.deferCurrentInvoiceForCompletion(recordShortages = false) },
                                         onDeferWithShortages = { viewModel.deferCurrentInvoiceForCompletion(recordShortages = true) },
                                         onCloseInvoice = { viewModel.closeActiveInvoice() },
                                         onOpenSessionApproval = { viewModel.openSessionApprovalModal() },
                                         onExportSessionPdf = { viewModel.exportDiscrepanciesPdf() },
                                         onReopenCompleted = { inv -> viewModel.reopenCompletedInvoice(inv) },
                                         onChangeSortMode = { mode ->
                                             viewModel.updateSettings { it.copy(itemSortMode = mode) }
                                         }
                                     )
                                 }

                                composable<WmsRoute.Receiving> {
                                    ReceivingScreen(
                                        isRtl = isRtl,
                                        currentUser = currentUser,
                                        receivingReports = receivingReports,
                                        onParsePoSpreadsheet = { uri, name -> viewModel.parseReceivingSpreadsheet(uri, name) },
                                        onSaveReceivingReport = { rep -> viewModel.saveReceivingReport(rep) },
                                        onDeleteReceivingReport = { id -> viewModel.deleteReceivingReport(id) }
                                    )
                                }

                                composable<WmsRoute.Returns> {
                                    ReturnsScreen(
                                        isRtl = isRtl,
                                        currentUser = currentUser,
                                        canApproveLab = roleConfig.canApproveLab,
                                        returnReports = returnReports,
                                        masterItems = masterItems,
                                        pdfExtractionState = pdfExtractionState,
                                        isCloudBusy = isCloudBusy,
                                        onImportOriginalPdf = { uri, name, engine ->
                                            viewModel.importOriginalInvoicePdfForReturn(uri, name, engine)
                                        },
                                        onSyncMasterInvoice = { invNo ->
                                            viewModel.syncOriginalInvoiceForReturn(invNo)
                                        },
                                        onRestoreReturnsFromCloud = { viewModel.restoreReturnsFromCloud() },
                                        onTogglePdfItem = { id -> viewModel.toggleExtractedPdfItem(id) },
                                        onSelectAllPdfItems = { sel -> viewModel.selectAllExtractedPdfItems(sel) },
                                        onUpdatePdfItemReturnQty = { id, qty ->
                                            viewModel.updateExtractedPdfItemReturnQty(id, qty)
                                        },
                                        onUpdatePdfItemPrice = { id, price ->
                                            viewModel.updateExtractedPdfItemPrice(id, price)
                                        },
                                        onUpdatePdfItemCondition = { id, cond ->
                                            viewModel.updateExtractedPdfItemCondition(id, cond)
                                        },
                                        onUpdatePdfHeader = { invNo, cust ->
                                            viewModel.updateExtractedPdfHeader(invNo, cust)
                                        },
                                        onFinalizePreliminaryReturn = { sig ->
                                            viewModel.finalizePreliminaryReturnReport(sig)
                                        },
                                        onClearExtractedPdf = { viewModel.clearExtractedPdfInvoice() },
                                        onSaveReturnReport = { rep -> viewModel.saveReturnReport(rep) },
                                        onLabDecision = { id, dec, notes -> viewModel.updateReturnLabDecision(id, dec, notes) },
                                        onExportPdf = { rep -> viewModel.exportReturnPdf(rep) },
                                        onDeleteReturnReport = { id -> viewModel.deleteReturnReport(id) }
                                    )
                                }

                                composable<WmsRoute.ErrorReport> {
                                    ErrorReportScreen(
                                        isRtl = isRtl,
                                        discrepancies = discrepancies,
                                        wrongPicks = wrongPicks,
                                        auditHistory = auditHistory,
                                        incompleteInvoices = incompleteInvoices,
                                        sessionSummary = sessionApprovalSummary,
                                        onOpenSessionApproval = { viewModel.openSessionApprovalModal() },
                                        onOpenDeferredInvoice = { inv ->
                                            viewModel.startOrOpenInvoice(inv)
                                            navigateTo(WmsRoute.ActiveAudit)
                                        },
                                        onExportIncompletePdf = { viewModel.exportIncompleteInvoicesPdf() },
                                        onExportCsv = { viewModel.exportDiscrepanciesCsv() },
                                        onExportPdf = { viewModel.exportDiscrepanciesPdf() },
                                        onDeleteDiscrepancy = { id -> viewModel.deleteDiscrepancy(id) },
                                        onClearAllReports = { viewModel.clearAllAuditReports() }
                                    )
                                }

                                composable<WmsRoute.InventoryCount> {
                                    InventoryCountScreen(
                                        isRtl = isRtl,
                                        currentUser = currentUser,
                                        packagingRules = packagingRules,
                                        inventoryReports = inventoryReports,
                                        draftItems = inventoryDraftItems,
                                        isImportBusy = isInventoryImportBusy,
                                        isCloudBusy = isCloudBusy,
                                        onImportBookExcel = { uri, name ->
                                            viewModel.importInventoryBookExcel(uri, name)
                                        },
                                        onSyncBookFromMaster = { viewModel.syncInventoryBookFromMaster() },
                                        onSyncReportsFromCloud = { viewModel.syncInventoryReportsFromCloud() },
                                        onAddOrIncrementItem = { code, name, book, c, p, pcs ->
                                            viewModel.addOrIncrementInventoryDraftItem(code, name, book, c, p, pcs)
                                        },
                                        onScanBarcodeIntoDraft = { code ->
                                            viewModel.scanBarcodeIntoInventoryDraft(code)
                                        },
                                        onAdjustDraftCounts = { id, dc, dp, dpcs ->
                                            viewModel.adjustInventoryDraftCounts(id, dc, dp, dpcs)
                                        },
                                        onRemoveDraftItem = { id -> viewModel.removeInventoryDraftItem(id) },
                                        onClearDraft = { viewModel.clearInventoryDraft() },
                                        onSaveInventoryReport = { rep -> viewModel.saveInventoryReport(rep) },
                                        onDeleteInventoryReport = { id -> viewModel.deleteInventoryReport(id) }
                                    )
                                }

                                composable<WmsRoute.PickingWave> {
                                    PickingWaveScreen(
                                        isRtl = isRtl,
                                        masterItems = masterItems,
                                        pickingWaves = pickingWaves,
                                        workers = workers,
                                        onCreateWave = { title, invs -> viewModel.createPickingWave(title, invs) },
                                        onSaveWave = { wave -> viewModel.savePickingWave(wave) },
                                        onDeleteWave = { id -> viewModel.deletePickingWave(id) },
                                        onUpsertWorker = { w -> viewModel.upsertWorker(w) },
                                        onDeleteWorker = { id -> viewModel.deleteWorker(id) }
                                    )
                                }

                                composable<WmsRoute.MasterDatabase> {
                                    MasterDatabaseScreen(
                                        isRtl = isRtl,
                                        masterItems = masterItems,
                                        completedInvoices = completedInvoices,
                                        incompleteInvoices = incompleteInvoices,
                                        packagingRules = packagingRules,
                                        onOpenInvoiceInAudit = { inv ->
                                            viewModel.startOrOpenInvoice(inv)
                                            navigateTo(WmsRoute.ActiveAudit)
                                        },
                                        onResetDefaultRules = { viewModel.resetDefaultPackagingRules() },
                                        onOpenExcelSync = { showExcelModal = true }
                                    )
                                }

                                composable<WmsRoute.Settings> {
                                    SettingsScreen(
                                        isRtl = isRtl,
                                        settings = settings,
                                        currentUser = currentUser,
                                        firebaseUser = firebaseUser,
                                        onUpdateSettings = { fn -> viewModel.updateSettings(fn) },
                                        onConfigureSupervisorPin = { pin -> viewModel.configureSupervisorPin(pin) },
                                        onSwitchToTrialAuditor = { viewModel.switchToTrialAuditor() },
                                        onOpenOperators = { showOperatorsModal = true },
                                        onOpenCloudSync = { showCloudModal = true },
                                        onOpenGuide = { showGuideModal = true }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
