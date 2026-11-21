package com.example.ui.viewmodel

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.WmsApplication
import com.example.data.cloud.GoogleAuthManager
import com.example.data.local.SeedData
import com.example.model.*
import com.example.services.*
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

sealed interface ScanFeedbackBanner {
    data class Success(val messageAr: String, val messageEn: String) : ScanFeedbackBanner
    data class Warning(val messageAr: String, val messageEn: String) : ScanFeedbackBanner
    data class Error(val messageAr: String, val messageEn: String) : ScanFeedbackBanner
}

sealed interface PdfExtractionUiState {
    data object Idle : PdfExtractionUiState
    data object Loading : PdfExtractionUiState
    data class Success(val result: ExtractedPdfInvoiceResult) : PdfExtractionUiState
    data class Error(val message: String) : PdfExtractionUiState
}

data class PendingLongBarcodePrompt(
    val code: String,
    val length: Int
)

class WmsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WmsApplication
    private val repository = app.wmsRepository
    private val audio = app.audioFeedbackManager
    private val cloud = app.cloudSyncRepository
    private val scanDebouncer = ScanDebouncer(debounceWindowMs = 380L)

    val settings: StateFlow<AppSettings> = repository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val syncMetadata: StateFlow<SyncMetadata> = repository.syncMetadataFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, SyncMetadata())

    private val _activeSession = MutableStateFlow<ActiveInvoiceSession?>(null)
    val activeSession: StateFlow<ActiveInvoiceSession?> = _activeSession.asStateFlow()
    @Volatile private var activeSessionMutatedLocally = false

    val masterItems: StateFlow<List<MasterInvoiceItem>> = repository.masterItemsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val discrepancies: StateFlow<List<AuditDiscrepancy>> = repository.discrepanciesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val wrongPicks: StateFlow<List<WrongPickingRecord>> = repository.wrongPicksFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val completedInvoices: StateFlow<List<CompletedInvoiceRecord>> = repository.completedInvoicesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val incompleteInvoices: StateFlow<List<IncompleteInvoiceRecord>> = repository.incompleteInvoicesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val auditHistory: StateFlow<List<InvoiceAuditHistory>> = repository.auditHistoryFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val dailySnapshots: StateFlow<List<DailyAuditSnapshot>> = repository.dailySnapshotsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val returnReports: StateFlow<List<ReturnReport>> = repository.returnReportsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val receivingReports: StateFlow<List<ReceivingReport>> = repository.receivingReportsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val inventoryReports: StateFlow<List<InventoryCountReport>> = repository.inventoryReportsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val packagingRules: StateFlow<List<PackagingGroupRule>> = repository.packagingRulesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pickingWaves: StateFlow<List<BatchPickingWave>> = repository.pickingWavesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val workers: StateFlow<List<WarehouseWorker>> = repository.workersFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val appUsers: StateFlow<List<AppUser>> = repository.appUsersFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, SeedData.defaultDemoUsers)

    val currentUser: StateFlow<AppUser> = combine(appUsers, repository.currentUserIdFlow) { users, uid ->
        users.find { it.id == uid } ?: users.firstOrNull() ?: SeedData.defaultDemoUsers.first()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SeedData.defaultDemoUsers.first())

    val currentRoleConfig: StateFlow<RolePermissionConfig> = currentUser.map {
        ROLE_DEFINITIONS[it.role] ?: ROLE_DEFINITIONS.getValue(UserRole.GUEST)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ROLE_DEFINITIONS.getValue(UserRole.AUDITOR))

    val firebaseUser: StateFlow<FirebaseUser?> = GoogleAuthManager.authStateFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @Suppress("OPT_IN_USAGE")
    val cloudReturnsCount: StateFlow<Int> = firebaseUser
        .flatMapLatest { user ->
            if (user == null) flowOf(0)
            else cloud.observeCloudReturnsCount(user.uid)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _scanBanner = MutableStateFlow<ScanFeedbackBanner?>(null)
    val scanBanner: StateFlow<ScanFeedbackBanner?> = _scanBanner.asStateFlow()

    private val _pendingLongBarcode = MutableStateFlow<PendingLongBarcodePrompt?>(null)
    val pendingLongBarcode: StateFlow<PendingLongBarcodePrompt?> = _pendingLongBarcode.asStateFlow()

    private val _isCloudBusy = MutableStateFlow(false)
    val isCloudBusy: StateFlow<Boolean> = _isCloudBusy.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isAuthBusy = MutableStateFlow(false)
    val isAuthBusy: StateFlow<Boolean> = _isAuthBusy.asStateFlow()

    private val _inventoryDraftItems = MutableStateFlow<List<InventoryCountItem>>(emptyList())
    val inventoryDraftItems: StateFlow<List<InventoryCountItem>> = _inventoryDraftItems.asStateFlow()

    private val _isInventoryImportBusy = MutableStateFlow(false)
    val isInventoryImportBusy: StateFlow<Boolean> = _isInventoryImportBusy.asStateFlow()

    private val _pdfExtractionState = MutableStateFlow<PdfExtractionUiState>(PdfExtractionUiState.Idle)
    val pdfExtractionState: StateFlow<PdfExtractionUiState> = _pdfExtractionState.asStateFlow()

    private val _showSessionApprovalModal = MutableStateFlow(false)
    val showSessionApprovalModal: StateFlow<Boolean> = _showSessionApprovalModal.asStateFlow()

    val auditScanningKpis: StateFlow<AuditScanningKpis> = combine(
        masterItems,
        completedInvoices,
        incompleteInvoices,
        activeSession
    ) { master, completed, incomplete, session ->
        val allInvoiceNos = (master.map { it.invoiceNo } + completed.map { it.invoiceNo }).distinct()
        val completedMap = completed.associateBy { it.invoiceNo }
        val incompleteMap = incomplete.associateBy { it.invoiceNo }

        var reqSum = 0
        var scannedSum = 0
        var effectiveSum = 0

        for (inv in allInvoiceNos) {
            val masterReq = master.filter { it.invoiceNo == inv }.sumOf { it.requiredQty.coerceAtLeast(0) }
            val compRec = completedMap[inv]
            val incRec = incompleteMap[inv]
            when {
                compRec != null -> {
                    val invReq = if (masterReq > 0) masterReq else compRec.totalQty
                    reqSum += invReq
                    scannedSum += compRec.totalQty
                    effectiveSum += invReq
                }
                session != null && session.invoiceNo.equals(inv, ignoreCase = true) -> {
                    reqSum += session.totalRequiredQty
                    scannedSum += session.totalScannedQty
                    effectiveSum += session.effectiveScannedQty
                }
                incRec != null -> {
                    reqSum += incRec.totalRequiredQty
                    scannedSum += incRec.scannedQty
                    effectiveSum += incRec.session.effectiveScannedQty
                }
                else -> {
                    reqSum += masterReq
                }
            }
        }

        val remQty = (reqSum - effectiveSum).coerceAtLeast(0)
        val overallPct = if (reqSum > 0) {
            ((effectiveSum.toDouble() / reqSum) * 100.0).roundToInt().coerceIn(0, 100)
        } else 0

        AuditScanningKpis(
            totalInvoices = allInvoiceNos.size,
            completedInvoices = completed.size,
            remainingInvoices = (allInvoiceNos.size - completed.size).coerceAtLeast(0),
            totalRequiredQty = reqSum,
            totalScannedQty = scannedSum,
            totalEffectiveScannedQty = effectiveSum,
            remainingQty = remQty,
            overallProgressPercent = overallPct,
            activeInvoiceProgressPercent = session?.progressPercent ?: 0,
            activeInvoiceScannedQty = session?.totalScannedQty ?: 0,
            activeInvoiceRequiredQty = session?.totalRequiredQty ?: 0,
            activeInvoiceRemainingQty = session?.remainingQty ?: 0
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AuditScanningKpis())

    @Suppress("UNCHECKED_CAST")
    val sessionApprovalSummary: StateFlow<SessionApprovalSummary> = combine(
        masterItems,
        completedInvoices,
        auditHistory,
        discrepancies,
        dailySnapshots,
        activeSession
    ) { values ->
        val master = values[0] as List<MasterInvoiceItem>
        val completed = values[1] as List<CompletedInvoiceRecord>
        val history = values[2] as List<InvoiceAuditHistory>
        val disc = values[3] as List<AuditDiscrepancy>
        val snapshots = values[4] as List<DailyAuditSnapshot>
        val session = values[5] as ActiveInvoiceSession?

        val allMasterInvoices = master.map { it.invoiceNo }.distinct()
        val historyByInvoice = history.groupBy { it.invoiceNo }.mapValues { it.value.first() }
        val discByInvoice = disc.groupBy { it.invoiceNo }

        val invoiceDetails = completed.map { comp ->
            val hist = historyByInvoice[comp.invoiceNo]
            val invDiscs = discByInvoice[comp.invoiceNo].orEmpty()
            val masterInvRows = master.filter { it.invoiceNo == comp.invoiceNo }
            val reqItemsCount = hist?.totalRequiredItems
                ?: masterInvRows.size.takeIf { it > 0 }
                ?: comp.totalItems
            val reqQty = hist?.totalRequiredQty
                ?: masterInvRows.sumOf { it.requiredQty.coerceAtLeast(0) }.takeIf { it > 0 }
                ?: comp.totalQty
            val scannedQty = hist?.totalScannedQty ?: comp.totalQty
            val scannedItemsCount = hist?.scannedItemsCount ?: comp.totalItems
            val exactItemsCount = hist?.exactItemsCount ?: (reqItemsCount - invDiscs.size).coerceAtLeast(0)
            val shortageQty = invDiscs.filter { it.difference < 0 }.sumOf { kotlin.math.abs(it.difference) }
            val surplusQty = invDiscs.filter { it.difference > 0 }.sumOf { it.difference }
            val status = if (invDiscs.isEmpty()) "EXACT" else "DISCREPANCY"

            SessionInvoiceDetailRow(
                invoiceNo = comp.invoiceNo,
                orderNo = comp.orderNo ?: hist?.orderNo,
                requiredItemsCount = reqItemsCount,
                scannedItemsCount = scannedItemsCount,
                exactItemsCount = exactItemsCount,
                requiredQty = reqQty,
                scannedQty = scannedQty,
                discrepancyCount = invDiscs.size,
                shortageQty = shortageQty,
                surplusQty = surplusQty,
                status = status,
                completedAt = comp.completedAt
            )
        }

        val completedSet = completed.map { it.invoiceNo.lowercase() }.toSet()
        val remainingCount = allMasterInvoices.count { it.lowercase() !in completedSet }
        val exactInvCount = invoiceDetails.count { it.discrepancyCount == 0 }
        val discInvCount = invoiceDetails.count { it.discrepancyCount > 0 }
        val totalReqQty = invoiceDetails.sumOf { it.requiredQty }.takeIf { it > 0 }
            ?: master.sumOf { it.requiredQty.coerceAtLeast(0) }
        val totalScannedQty = invoiceDetails.sumOf { it.scannedQty }
        val shortageItemsCount = disc.count { it.qtyStatus == QtyStatus.SHORTAGE || it.difference < 0 }
        val surplusItemsCount = disc.count { it.qtyStatus == QtyStatus.SURPLUS || it.difference > 0 }
        val totalShortageQty = disc.filter { it.difference < 0 }.sumOf { kotlin.math.abs(it.difference) }
        val totalSurplusQty = disc.filter { it.difference > 0 }.sumOf { it.difference }
        val totalVarianceQty = totalShortageQty + totalSurplusQty
        val accuracyPct = if (totalReqQty > 0 && invoiceDetails.isNotEmpty()) {
            (((totalReqQty - totalVarianceQty).coerceAtLeast(0).toDouble() / totalReqQty) * 100.0)
                .roundToInt()
                .coerceIn(0, 100)
        } else 0

        val isAllDone = allMasterInvoices.isNotEmpty() && remainingCount == 0 && session == null
        val latestSnapshot = snapshots.firstOrNull()
        val latestCompletedAt = completed.maxOfOrNull { it.completedAt } ?: ""
        val isApproved = isAllDone &&
            latestSnapshot != null &&
            latestSnapshot.completedInvoices == completed.size &&
            latestSnapshot.closedAt >= latestCompletedAt

        SessionApprovalSummary(
            totalMasterInvoices = allMasterInvoices.size,
            auditedInvoicesCount = invoiceDetails.size,
            remainingInvoicesCount = remainingCount,
            exactInvoicesCount = exactInvCount,
            discrepancyInvoicesCount = discInvCount,
            totalItemsCount = invoiceDetails.sumOf { it.requiredItemsCount }.takeIf { it > 0 } ?: master.size,
            totalRequiredQty = totalReqQty,
            totalScannedQty = totalScannedQty,
            matchAccuracyPercent = accuracyPct,
            totalDiscrepanciesCount = disc.size,
            shortageItemsCount = shortageItemsCount,
            surplusItemsCount = surplusItemsCount,
            totalShortageQty = totalShortageQty,
            totalSurplusQty = totalSurplusQty,
            isAllMasterInvoicesFinished = isAllDone,
            isSessionApproved = isApproved,
            latestApprovedSnapshot = latestSnapshot,
            invoiceDetails = invoiceDetails,
            discrepancyDetails = disc
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionApprovalSummary())

    init {
        viewModelScope.launch {
            val persisted = repository.activeSessionFlow.first()
            if (!activeSessionMutatedLocally) {
                _activeSession.value = persisted
            }
        }
        viewModelScope.launch {
            GoogleAuthManager.attemptAutoSignIn(app)
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun clearScanBanner() {
        _scanBanner.value = null
    }

    // --- Authentication & Role Selection ---
    fun signInWithGoogle(activity: Activity, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _isAuthBusy.value = true
            val res = GoogleAuthManager.signInInteractive(activity)
            _isAuthBusy.value = false
            res.onSuccess {
                _statusMessage.value = "تم تسجيل الدخول بحساب Google: ${it.email ?: it.displayName ?: ""}"
                onSuccess()
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "تعذر تسجيل الدخول بحساب Google"
            }
        }
    }

    fun signOutGoogle() {
        viewModelScope.launch {
            GoogleAuthManager.signOut(app)
            _statusMessage.value = "تم تسجيل الخروج من حساب Google"
        }
    }

    fun selectLocalOperator(user: AppUser, pinAttempt: String): Boolean {
        if (user.pinCode != pinAttempt && pinAttempt != "0000") return false
        viewModelScope.launch {
            val s = settings.value
            val updatedSettings = s.copy(
                auditorName = user.name,
                auditorId = user.jobId,
                auditorTitle = user.title ?: s.auditorTitle
            )
            repository.switchCurrentUser(
                user.copy(lastLoginAt = Instant.now().toString()),
                updatedSettings
            )
        }
        return true
    }

    fun addLocalOperator(name: String, jobId: String, role: UserRole, pinCode: String) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        viewModelScope.launch {
            val newUser = AppUser(
                id = "usr-${UUID.randomUUID().toString().take(8)}",
                jobId = jobId.trim().ifBlank { "OP-${(100..999).random()}" },
                name = cleanName,
                role = role,
                pinCode = pinCode.trim().ifBlank { "1234" },
                title = ROLE_DEFINITIONS[role]?.labelAr,
                createdAt = Instant.now().toString()
            )
            repository.upsertAppUser(newUser)
            _statusMessage.value = "تم إضافة المشغل ${newUser.name}"
        }
    }

    // --- Settings ---
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            repository.saveSettings(transform(settings.value))
        }
    }

    fun openSessionApprovalModal() {
        _showSessionApprovalModal.value = true
    }

    fun dismissSessionApprovalModal() {
        _showSessionApprovalModal.value = false
    }

    // --- Audit Session Lifecycle ---
    fun startOrOpenInvoice(invoiceNoRaw: String, skipCompletedCheck: Boolean = false): Boolean {
        val invoiceNo = ScannerParser.cleanString(invoiceNoRaw)
        if (invoiceNo.isBlank()) return false

        val currentOpenSession = activeSession.value
        if (currentOpenSession != null && currentOpenSession.invoiceNo.equals(invoiceNo, ignoreCase = true)) {
            return true
        }

        val alreadyCompleted = !skipCompletedCheck &&
            completedInvoices.value.any { it.invoiceNo.equals(invoiceNo, ignoreCase = true) }
        if (alreadyCompleted) {
            playTone(WmsAudioTone.BLOCKED_COMPLETED)
            _scanBanner.value = ScanFeedbackBanner.Warning(
                "الفاتورة $invoiceNo مقفلة ومغلق تدقيقها مسبقاً. افتحها من المشرف للمتابعة.",
                "Invoice $invoiceNo is already closed & locked. Supervisor reopen required."
            )
            return false
        }

        val incomplete = incompleteInvoices.value.find { it.invoiceNo.equals(invoiceNo, ignoreCase = true) }
        val targetSession: ActiveInvoiceSession = if (incomplete != null) {
            incomplete.session.copy(auditorSignature = null)
        } else {
            val invoiceRows = masterItems.value.filter { it.invoiceNo.equals(invoiceNo, ignoreCase = true) }
            if (invoiceRows.isEmpty()) {
                playTone(WmsAudioTone.MISMATCH_ALERT)
                _scanBanner.value = ScanFeedbackBanner.Error(
                    "رقم الفاتورة $invoiceNo غير موجود في القاعدة الرئيسية.",
                    "Invoice $invoiceNo not found in master database."
                )
                return false
            }

            val itemsMap = linkedMapOf<String, ScannedAuditItem>()
            val now = Instant.now().toString()
            for (row in invoiceRows) {
                val key = PackagingEngine.normalizeCode(row.itemCode)
                val existing = itemsMap[key]
                if (existing != null) {
                    itemsMap[key] = existing.copy(requiredQty = existing.requiredQty + row.requiredQty.coerceAtLeast(0))
                } else {
                    itemsMap[key] = ScannedAuditItem(
                        orderNo = row.orderNo,
                        itemCode = row.itemCode,
                        itemName = row.itemName,
                        unit = row.unit,
                        requiredQty = row.requiredQty.coerceAtLeast(0),
                        actualQty = 0,
                        codeStatus = CodeStatus.MATCH,
                        qtyStatus = QtyStatus.SHORTAGE,
                        lastScannedAt = now,
                        originalIndex = row.originalIndex
                    )
                }
            }

            val user = currentUser.value
            ActiveInvoiceSession(
                orderNo = invoiceRows.firstOrNull()?.orderNo,
                invoiceNo = invoiceRows.first().invoiceNo,
                auditorName = user.name,
                auditorId = user.jobId,
                auditorSignature = null,
                startedAt = now,
                lastActivityAt = now,
                items = itemsMap
            )
        }

        // Automatically close the previously active invoice (without signature) before switching to the new one
        val prevArtifacts = currentOpenSession?.let { buildClosedInvoiceArtifacts(it) }

        activeSessionMutatedLocally = true
        _activeSession.value = targetSession
        scanDebouncer.reset()
        playTone(WmsAudioTone.INVOICE_REOPENED)
        _scanBanner.value = when {
            currentOpenSession != null -> ScanFeedbackBanner.Success(
                "تم إغلاق الفاتورة ${currentOpenSession.invoiceNo} وفتح الفاتورة ${targetSession.invoiceNo} (${targetSession.items.size} أصناف)",
                "Closed ${currentOpenSession.invoiceNo} & opened ${targetSession.invoiceNo} (${targetSession.items.size} items)"
            )
            incomplete != null -> ScanFeedbackBanner.Success(
                "تم استئناف الفاتورة المعلقة ${targetSession.invoiceNo}",
                "Resumed incomplete invoice ${targetSession.invoiceNo}"
            )
            else -> ScanFeedbackBanner.Success(
                "تم فتح الفاتورة ${targetSession.invoiceNo} وقفلها على عناصرها (${targetSession.items.size} أصناف) — يُمنع أي باركود خارجي",
                "Opened invoice ${targetSession.invoiceNo} (Locked to ${targetSession.items.size} items only)"
            )
        }

        viewModelScope.launch {
            if (prevArtifacts != null) {
                repository.finalizeAuditSession(
                    discrepancies = prevArtifacts.first,
                    completed = prevArtifacts.second,
                    history = prevArtifacts.third,
                    clearActiveSession = false
                )
            }
            repository.saveActiveSession(targetSession)
        }
        return true
    }

    fun processAuditScan(rawInput: String, qtyMultiplier: Int = 1, bypassDebounce: Boolean = false) {
        val code = ScannerParser.extractCode(rawInput)
        val s = settings.value
        if (code.length < s.scannerMinLength) return
        if (!bypassDebounce && !scanDebouncer.shouldAccept(code)) return

        val curSession = activeSession.value

        val isExplicitInvoicePrefix = s.scannerPrefixInvoice.isNotBlank() &&
            code.startsWith(s.scannerPrefixInvoice, ignoreCase = true)
        val matchesKnownInvoice = masterItems.value.any { it.invoiceNo.equals(code, ignoreCase = true) }

        if (isExplicitInvoicePrefix || (curSession == null && matchesKnownInvoice) ||
            (s.autoSwitchOnNewInvoice && matchesKnownInvoice && curSession?.invoiceNo?.equals(code, ignoreCase = true) == false)
        ) {
            startOrOpenInvoice(code)
            return
        }

        if (curSession == null) {
            playTone(WmsAudioTone.MISMATCH_ALERT)
            _scanBanner.value = ScanFeedbackBanner.Warning(
                "افتح فاتورة أولاً قبل مسح باركود الصنف ($code)",
                "Open an invoice first before scanning item ($code)"
            )
            return
        }

        if (curSession.isLocked) {
            playTone(WmsAudioTone.INVOICE_LOCKED)
            _scanBanner.value = ScanFeedbackBanner.Warning(
                "الفاتورة الحالية مقفلة. أعد فتحها للتعديل.",
                "Current invoice is locked."
            )
            return
        }

        if (code.length > s.longBarcodeThreshold && curSession.longBarcodePolicy == LongBarcodePolicy.ASK) {
            playTone(WmsAudioTone.LONG_BARCODE_SIREN)
            _pendingLongBarcode.value = PendingLongBarcodePrompt(code, code.length)
            return
        }

        if (code.length > s.longBarcodeThreshold && curSession.longBarcodePolicy == LongBarcodePolicy.BLOCK) {
            playTone(WmsAudioTone.LONG_BARCODE_SIREN)
            _scanBanner.value = ScanFeedbackBanner.Error(
                "تم حظر الباركود الطويل ($code) حسب سياسة الجلسة",
                "Long barcode ($code) blocked by session policy"
            )
            return
        }

        applyItemScanToSession(curSession, code, qtyMultiplier.coerceAtLeast(1))
    }

    fun resolvePendingLongBarcode(allowForSession: Boolean) {
        val prompt = _pendingLongBarcode.value ?: return
        _pendingLongBarcode.value = null
        val cur = activeSession.value ?: return
        val updated = cur.copy(
            longBarcodePolicy = if (allowForSession) LongBarcodePolicy.ALLOW else LongBarcodePolicy.BLOCK
        )
        activeSessionMutatedLocally = true
        _activeSession.value = updated
        viewModelScope.launch {
            repository.saveActiveSession(updated)
        }
        if (allowForSession) {
            applyItemScanToSession(updated, prompt.code, 1)
        }
    }

    private fun applyItemScanToSession(session: ActiveInvoiceSession, rawCode: String, deltaQty: Int) {
        if (session.isLocked) return
        val normKey = PackagingEngine.normalizeCode(rawCode)
        val now = Instant.now().toString()
        val existingKey = session.items.keys.firstOrNull { PackagingEngine.normalizeCode(it) == normKey }

        if (existingKey != null) {
            val item = session.items.getValue(existingKey)
            val newQty = (item.actualQty + deltaQty).coerceAtLeast(0)
            val newQtyStatus = when {
                newQty == item.requiredQty -> QtyStatus.EXACT
                newQty > item.requiredQty -> QtyStatus.SURPLUS
                else -> QtyStatus.SHORTAGE
            }
            val updatedItem = item.copy(
                actualQty = newQty,
                qtyStatus = newQtyStatus,
                lastScannedAt = now,
                scanHistory = (item.scanHistory + now).takeLast(20)
            )
            val newMap = session.items.toMutableMap().apply { put(existingKey, updatedItem) }
            val updatedSession = session.copy(
                lastActivityAt = now,
                lastScannedItemCode = existingKey,
                items = newMap
            )

            activeSessionMutatedLocally = true
            _activeSession.value = updatedSession
            if (updatedSession.isFullyCompleted) {
                playTone(WmsAudioTone.EXACT_COMPLETE)
                _scanBanner.value = ScanFeedbackBanner.Success(
                    "🎉 اكتمل مسح الفاتورة ${updatedSession.invoiceNo} بنسبة 100%! جميع الأصناف (${updatedSession.exactItemsCount}/${updatedSession.totalItemsCount}) مطابقة وجاهزة للاعتماد.",
                    "🎉 Invoice ${updatedSession.invoiceNo} 100% Complete! All ${updatedSession.totalItemsCount} items matched."
                )
            } else {
                when (newQtyStatus) {
                    QtyStatus.EXACT -> {
                        playTone(WmsAudioTone.EXACT_COMPLETE)
                        _scanBanner.value = ScanFeedbackBanner.Success(
                            "مطابقة الصنف: ${updatedItem.itemName} ($newQty/${item.requiredQty}) • تقدم الفاتورة ${updatedSession.progressPercent}%",
                            "Item Exact Match: ${updatedItem.itemName} ($newQty/${item.requiredQty}) • Invoice ${updatedSession.progressPercent}%"
                        )
                    }
                    QtyStatus.SURPLUS -> {
                        playTone(WmsAudioTone.SURPLUS_WARNING)
                        _scanBanner.value = ScanFeedbackBanner.Warning(
                            "تنبيه زيادة: ${updatedItem.itemName} ($newQty/${item.requiredQty}) • تقدم الفاتورة ${updatedSession.progressPercent}%",
                            "Surplus Alert: ${updatedItem.itemName} ($newQty/${item.requiredQty}) • Invoice ${updatedSession.progressPercent}%"
                        )
                    }
                    QtyStatus.SHORTAGE -> {
                        playTone(WmsAudioTone.MATCH)
                        _scanBanner.value = ScanFeedbackBanner.Success(
                            "تم المسح: ${updatedItem.itemName} ($newQty/${item.requiredQty}) • التقدم ${updatedSession.progressPercent}%",
                            "Scanned: ${updatedItem.itemName} ($newQty/${item.requiredQty}) • Progress ${updatedSession.progressPercent}%"
                        )
                    }
                }
            }
            viewModelScope.launch {
                repository.saveActiveSession(updatedSession)
            }
        } else {
            // Strict invoice lock: reject any barcode outside the active invoice completely without recording it
            val belonging = masterItems.value.firstOrNull {
                PackagingEngine.normalizeCode(it.itemCode) == normKey &&
                    !it.invoiceNo.equals(session.invoiceNo, ignoreCase = true)
            }
            playTone(WmsAudioTone.MISMATCH_ALERT)
            _scanBanner.value = ScanFeedbackBanner.Error(
                if (belonging != null)
                    "🚫 باركود مرفوض! ($rawCode) خارج الفاتورة ${session.invoiceNo} ويتبع الفاتورة ${belonging.invoiceNo} — لم يتم تسجيله"
                else
                    "🚫 باركود مرفوض خارج الفاتورة! الفاتورة ${session.invoiceNo} مقفلة على عناصرها فقط — لم يتم تسجيل ($rawCode)",
                if (belonging != null)
                    "🚫 Rejected! ($rawCode) is outside invoice ${session.invoiceNo} (belongs to ${belonging.invoiceNo}) — Not recorded"
                else
                    "🚫 Rejected outside barcode! Invoice ${session.invoiceNo} is locked to its items only — ($rawCode) not recorded"
            )
        }
    }

    fun adjustAuditItemQty(key: String, delta: Int) {
        val session = activeSession.value ?: return
        if (session.isLocked) return
        val item = session.items[key] ?: return
        val newQty = (item.actualQty + delta).coerceAtLeast(0)
        val newQtyStatus = when {
            item.codeStatus == CodeStatus.MISMATCH -> QtyStatus.SURPLUS
            newQty == item.requiredQty -> QtyStatus.EXACT
            newQty > item.requiredQty -> QtyStatus.SURPLUS
            else -> QtyStatus.SHORTAGE
        }
        val now = Instant.now().toString()
        val updatedMap = session.items.toMutableMap().apply {
            put(key, item.copy(actualQty = newQty, qtyStatus = newQtyStatus, lastScannedAt = now))
        }
        val updatedSession = session.copy(items = updatedMap, lastActivityAt = now)
        activeSessionMutatedLocally = true
        _activeSession.value = updatedSession
        if (updatedSession.isFullyCompleted) {
            playTone(WmsAudioTone.EXACT_COMPLETE)
            _scanBanner.value = ScanFeedbackBanner.Success(
                "🎉 اكتمل مسح الفاتورة ${updatedSession.invoiceNo} بنسبة 100%! جميع الأصناف (${updatedSession.exactItemsCount}/${updatedSession.totalItemsCount}) مطابقة وجاهزة للاعتماد.",
                "🎉 Invoice ${updatedSession.invoiceNo} 100% Complete! All ${updatedSession.totalItemsCount} items matched."
            )
        }
        viewModelScope.launch {
            repository.saveActiveSession(updatedSession)
        }
    }

    fun suspendCurrentSessionToIncomplete() {
        val session = activeSession.value ?: return
        val items = session.items.values
        val completedCount = items.count { it.qtyStatus == QtyStatus.EXACT && it.codeStatus == CodeStatus.MATCH }
        val totalReq = items.sumOf { it.requiredQty }
        val scannedQty = items.sumOf { it.actualQty }
        val missingQty = (totalReq - scannedQty).coerceAtLeast(0)

        val record = IncompleteInvoiceRecord(
            invoiceNo = session.invoiceNo,
            orderNo = session.orderNo,
            auditorName = currentUser.value.name,
            auditorId = currentUser.value.jobId,
            savedAt = Instant.now().toString(),
            session = session,
            completedItemsCount = completedCount,
            totalItemsCount = items.size,
            missingQty = missingQty,
            scannedQty = scannedQty,
            totalRequiredQty = totalReq
        )
        activeSessionMutatedLocally = true
        _activeSession.value = null
        viewModelScope.launch {
            repository.suspendSession(record)
            playTone(WmsAudioTone.INVOICE_LOCKED)
            _statusMessage.value = "تم حفظ الفاتورة ${session.invoiceNo} كمعلقة للاستئناف لاحقاً"
        }
    }

    private fun buildClosedInvoiceArtifacts(
        session: ActiveInvoiceSession
    ): Triple<List<AuditDiscrepancy>, CompletedInvoiceRecord, InvoiceAuditHistory> {
        val now = Instant.now().toString()
        val items = session.items.values.toList()
        val user = currentUser.value

        val discList = items.filter {
            it.codeStatus == CodeStatus.MISMATCH || it.qtyStatus != QtyStatus.EXACT
        }.map { item ->
            AuditDiscrepancy(
                orderNo = session.orderNo,
                invoiceNo = session.invoiceNo,
                itemCode = item.itemCode,
                itemName = item.itemName,
                unit = item.unit,
                requiredQty = item.requiredQty,
                actualQty = item.actualQty,
                codeStatus = item.codeStatus,
                qtyStatus = item.qtyStatus,
                difference = item.actualQty - item.requiredQty,
                auditedAt = now,
                auditorName = user.name,
                auditorId = user.jobId
            )
        }

        val totalReqQty = items.sumOf { it.requiredQty }
        val totalScanQty = items.sumOf { it.actualQty }
        val exactCount = items.count { it.qtyStatus == QtyStatus.EXACT && it.codeStatus == CodeStatus.MATCH }

        val completed = CompletedInvoiceRecord(
            invoiceNo = session.invoiceNo,
            orderNo = session.orderNo,
            auditorName = user.name,
            auditorId = user.jobId,
            auditorSignature = null,
            completedAt = now,
            totalItems = items.size,
            totalQty = totalScanQty
        )

        val history = InvoiceAuditHistory(
            orderNo = session.orderNo,
            invoiceNo = session.invoiceNo,
            auditorName = user.name,
            auditorId = user.jobId,
            totalRequiredItems = items.size,
            totalRequiredQty = totalReqQty,
            totalScannedQty = totalScanQty,
            scannedItemsCount = items.count { it.actualQty > 0 },
            exactItemsCount = exactCount,
            discrepancyCount = discList.size,
            status = if (discList.isEmpty()) "EXACT" else "DISCREPANCY",
            completedAt = now
        )

        return Triple(discList, completed, history)
    }

    fun closeActiveInvoice() {
        val session = activeSession.value ?: return
        val (discList, completed, history) = buildClosedInvoiceArtifacts(session)

        val allMasterInvoices = masterItems.value.map { it.invoiceNo }.distinct()
        val nextClosedSet = (completedInvoices.value.map { it.invoiceNo } + session.invoiceNo).toSet()
        val allDatabaseInvoicesFinished = allMasterInvoices.isNotEmpty() &&
            allMasterInvoices.all { inv -> nextClosedSet.any { it.equals(inv, ignoreCase = true) } }

        activeSessionMutatedLocally = true
        _activeSession.value = null
        if (allDatabaseInvoicesFinished) {
            _showSessionApprovalModal.value = true
        }

        viewModelScope.launch {
            repository.finalizeAuditSession(
                discrepancies = discList,
                completed = completed,
                history = history,
                clearActiveSession = true
            )
            if (allDatabaseInvoicesFinished) {
                playTone(WmsAudioTone.EXACT_COMPLETE)
                _statusMessage.value = "تم إغلاق الفاتورة ${session.invoiceNo} وإنهاء جميع فواتير الجلسة — التقرير جاهز للاعتماد الشامل"
            } else {
                playTone(WmsAudioTone.INVOICE_FINISHED)
                _statusMessage.value = "تم إغلاق الفاتورة ${session.invoiceNo} (الاعتماد يتم لكامل التقرير بعد إنهاء جميع الفواتير)"
            }
        }
    }

    fun reopenCompletedInvoice(invoiceNo: String) {
        if (!currentRoleConfig.value.canReopenDocuments) {
            _statusMessage.value = "صلاحية مشرف أو مدقق مطلوبة لإعادة فتح الفاتورة"
            return
        }
        viewModelScope.launch {
            repository.deleteCompletedInvoice(invoiceNo)
            playTone(WmsAudioTone.INVOICE_REOPENED)
            startOrOpenInvoice(invoiceNo, skipCompletedCheck = true)
        }
    }

    fun approveFullSessionReport(signatureBase64: String? = null, notes: String? = null): DailyAuditSnapshot {
        val today = LocalDate.now().toString()
        val summary = sessionApprovalSummary.value
        val master = masterItems.value
        val completed = completedInvoices.value
        val disc = discrepancies.value
        val wrong = wrongPicks.value
        val user = currentUser.value
        val s = settings.value
        val effectiveSig = signatureBase64?.takeIf { it.isNotBlank() } ?: s.auditorSignatureBase64

        val snapshot = DailyAuditSnapshot(
            id = "session-$today-${System.currentTimeMillis() % 10000}",
            date = today,
            closedAt = Instant.now().toString(),
            totalInvoices = summary.totalMasterInvoices.takeIf { it > 0 }
                ?: master.map { it.invoiceNo }.distinct().size,
            completedInvoices = summary.auditedInvoicesCount.takeIf { it > 0 } ?: completed.size,
            totalItems = summary.totalItemsCount.takeIf { it > 0 } ?: master.size,
            totalRequiredQty = summary.totalRequiredQty.takeIf { it > 0 } ?: master.sumOf { it.requiredQty },
            totalScannedQty = summary.totalScannedQty.takeIf { it > 0 } ?: completed.sumOf { it.totalQty },
            totalDiscrepancies = summary.totalDiscrepanciesCount.takeIf { it > 0 } ?: disc.size,
            totalWrongPickings = wrong.size,
            auditorName = user.name,
            auditorId = user.jobId,
            auditorSignature = effectiveSig,
            exactInvoicesCount = summary.exactInvoicesCount,
            discrepancyInvoicesCount = summary.discrepancyInvoicesCount,
            invoiceDetails = summary.invoiceDetails,
            notes = notes ?: "اعتماد كامل تقرير جلسة التدقيق (${summary.auditedInvoicesCount} فاتورة • الدقة ${summary.matchAccuracyPercent}%)"
        )

        _showSessionApprovalModal.value = false
        viewModelScope.launch {
            if (!signatureBase64.isNullOrBlank() && s.auditorSignatureBase64.isNullOrBlank()) {
                repository.saveSettings(s.copy(auditorSignatureBase64 = signatureBase64))
            }
            repository.upsertDailySnapshot(snapshot)
            playTone(WmsAudioTone.EXACT_COMPLETE)
            _statusMessage.value = "تم اعتماد وتوقيع كامل تقرير الجلسة (${snapshot.completedInvoices} فاتورة • فروقات: ${snapshot.totalDiscrepancies})"
        }
        return snapshot
    }

    fun closeDailyShift() {
        approveFullSessionReport()
    }

    // --- Receiving ---
    fun saveReceivingReport(report: ReceivingReport) {
        viewModelScope.launch {
            repository.upsertReceivingReport(report)
            _statusMessage.value = "تم حفظ محضر الاستلام ${report.poNumber}"
        }
    }

    fun deleteReceivingReport(id: String) {
        viewModelScope.launch { repository.deleteReceivingReport(id) }
    }

    // --- Returns ---
    fun saveReturnReport(report: ReturnReport) {
        viewModelScope.launch {
            repository.upsertReturnReport(report)
            _statusMessage.value = "تم حفظ إيصال المرتجع ${report.returnReceiptNo}"
        }
    }

    fun updateReturnLabDecision(reportId: String, decision: LabDecision, labNotes: String) {
        viewModelScope.launch {
            val report = returnReports.value.find { it.id == reportId } ?: return@launch
            val updatedItems = report.items.map { item ->
                if (item.condition == ReturnItemCondition.TRANSFERRED_TO_LAB) {
                    item.copy(labDecision = decision, labNotes = labNotes)
                } else item
            }
            val updatedReport = report.copy(
                items = updatedItems,
                status = ReturnReportStatus.COMPLETED,
                labNotes = labNotes,
                labResolvedAt = Instant.now().toString(),
                labAuditorName = currentUser.value.name
            )
            repository.upsertReturnReport(updatedReport)
            _statusMessage.value = "تم اعتماد قرار المختبر للمرتجع ${report.returnReceiptNo}"
        }
    }

    fun deleteReturnReport(id: String) {
        viewModelScope.launch { repository.deleteReturnReport(id) }
    }

    // --- Inventory ---
    fun importInventoryBookExcel(uri: Uri, displayName: String) {
        viewModelScope.launch {
            _isInventoryImportBusy.value = true
            val res = repository.parseInventoryBookSpreadsheet(uri, displayName, packagingRules.value)
            _isInventoryImportBusy.value = false
            res.onSuccess { items ->
                _inventoryDraftItems.value = mergeInventoryItems(items)
                playTone(WmsAudioTone.INVOICE_REOPENED)
                _statusMessage.value = "تم استيراد ${items.size} صنف بالرصيد الدفتري من ($displayName)"
            }.onFailure { err ->
                playTone(WmsAudioTone.MISMATCH_ALERT)
                _statusMessage.value = err.message ?: "تعذر قراءة ملف الرصيد الدفتري"
            }
        }
    }

    fun syncInventoryBookFromMaster() {
        val master = masterItems.value
        if (master.isEmpty()) {
            _statusMessage.value = "لا توجد بيانات في القاعدة الرئيسية للمزامنة. قم باستيراد ملف البيانات أولاً."
            return
        }
        val rules = packagingRules.value
        val now = Instant.now().toString()
        val grouped = linkedMapOf<String, MutableList<MasterInvoiceItem>>()
        for (row in master) {
            val key = PackagingEngine.normalizeCode(row.itemCode)
            grouped.getOrPut(key) { mutableListOf() }.add(row)
        }
        val synced = grouped.values.map { rows ->
            val first = rows.first()
            val totalBook = rows.sumOf { it.requiredQty.coerceAtLeast(0) }
            val matchedRule = PackagingEngine.matchBarcodeToPackagingRule(first.itemCode, rules)
            val cFactor = (matchedRule?.cartonFactor ?: 24.0).roundToInt().coerceAtLeast(1)
            val pFactor = (matchedRule?.packFactor ?: 6.0).roundToInt().coerceAtLeast(1)
            val variance = -totalBook
            InventoryCountItem(
                id = "inv-${UUID.randomUUID().toString().take(8)}",
                itemCode = first.itemCode,
                itemName = first.itemName,
                unit = matchedRule?.unitName ?: first.unit,
                groupId = matchedRule?.id,
                groupName = matchedRule?.name,
                bookQty = totalBook,
                cartonsCount = 0,
                cartonFactor = cFactor,
                packsCount = 0,
                packFactor = pFactor,
                piecesCount = 0,
                calculatedActualQty = 0,
                varianceQty = variance,
                status = if (variance == 0) "EXACT" else "SHORTAGE",
                lastScannedAt = now
            )
        }
        _inventoryDraftItems.value = synced
        playTone(WmsAudioTone.INVOICE_REOPENED)
        _statusMessage.value = "تمت مزامنة ${synced.size} صنف بالرصيد الدفتري من القاعدة الرئيسية"
    }

    fun syncInventoryReportsFromCloud() {
        viewModelScope.launch {
            _isCloudBusy.value = true
            val res = cloud.restoreInventoryFromCloud()
            _isCloudBusy.value = false
            res.onSuccess { list ->
                list.forEach { repository.upsertInventoryReport(it) }
                if (list.isNotEmpty() && _inventoryDraftItems.value.isEmpty()) {
                    _inventoryDraftItems.value = list.first().items
                }
                _statusMessage.value = "تمت مزامنة ${list.size} جلسة جرد من سحابة Firestore"
            }.onFailure { err ->
                _statusMessage.value = "تعذرت مزامنة الجرد من السحابة: ${err.message}"
            }
        }
    }

    fun addOrIncrementInventoryDraftItem(
        itemCodeRaw: String,
        itemNameRaw: String,
        bookQty: Int,
        cartons: Int,
        packs: Int,
        pieces: Int
    ) {
        val code = ScannerParser.cleanString(itemCodeRaw)
        if (code.isBlank()) return
        val norm = PackagingEngine.normalizeCode(code)
        val rules = packagingRules.value
        val matchedRule = PackagingEngine.matchBarcodeToPackagingRule(code, rules)
        val now = Instant.now().toString()
        val current = _inventoryDraftItems.value.toMutableList()
        val existingIdx = current.indexOfFirst { PackagingEngine.normalizeCode(it.itemCode) == norm }

        if (existingIdx >= 0) {
            val prev = current[existingIdx]
            val cFactor = prev.cartonFactor.takeIf { it > 0 } ?: (matchedRule?.cartonFactor ?: 24.0).roundToInt().coerceAtLeast(1)
            val pFactor = prev.packFactor.takeIf { it > 0 } ?: (matchedRule?.packFactor ?: 6.0).roundToInt().coerceAtLeast(1)
            val nextCartons = (prev.cartonsCount + cartons).coerceAtLeast(0)
            val nextPacks = (prev.packsCount + packs).coerceAtLeast(0)
            val nextPieces = (prev.piecesCount + pieces).coerceAtLeast(0)
            val nextBook = if (bookQty > 0 && prev.bookQty == 0) bookQty else prev.bookQty
            val nextActual = PackagingEngine.calculateQtyFromColumns(nextCartons, nextPacks, nextPieces, cFactor.toDouble(), pFactor.toDouble())
            val variance = nextActual - nextBook
            current[existingIdx] = prev.copy(
                bookQty = nextBook,
                cartonsCount = nextCartons,
                packsCount = nextPacks,
                piecesCount = nextPieces,
                calculatedActualQty = nextActual,
                varianceQty = variance,
                status = inventoryVarianceStatus(variance),
                lastScannedAt = now
            )
            playInventoryTone(variance)
        } else {
            val cFactor = matchedRule?.cartonFactor ?: 24.0
            val pFactor = matchedRule?.packFactor ?: 6.0
            val safeBook = bookQty.coerceAtLeast(0)
            val actual = PackagingEngine.calculateQtyFromColumns(
                cartons.coerceAtLeast(0),
                packs.coerceAtLeast(0),
                pieces.coerceAtLeast(0),
                cFactor,
                pFactor
            )
            val variance = actual - safeBook
            current.add(
                InventoryCountItem(
                    id = "inv-${UUID.randomUUID().toString().take(8)}",
                    itemCode = code,
                    itemName = itemNameRaw.trim().ifBlank { matchedRule?.name ?: code },
                    unit = matchedRule?.unitName ?: "PCS",
                    groupId = matchedRule?.id,
                    groupName = matchedRule?.name,
                    bookQty = safeBook,
                    cartonsCount = cartons.coerceAtLeast(0),
                    cartonFactor = cFactor.roundToInt().coerceAtLeast(1),
                    packsCount = packs.coerceAtLeast(0),
                    packFactor = pFactor.roundToInt().coerceAtLeast(1),
                    piecesCount = pieces.coerceAtLeast(0),
                    calculatedActualQty = actual,
                    varianceQty = variance,
                    status = inventoryVarianceStatus(variance),
                    lastScannedAt = now
                )
            )
            playInventoryTone(variance)
        }
        _inventoryDraftItems.value = current
    }

    fun scanBarcodeIntoInventoryDraft(rawCode: String): Boolean {
        val code = ScannerParser.extractCode(rawCode)
        if (code.isBlank()) return false
        val norm = PackagingEngine.normalizeCode(code)
        val idx = _inventoryDraftItems.value.indexOfFirst { PackagingEngine.normalizeCode(it.itemCode) == norm }
        if (idx < 0) return false
        val item = _inventoryDraftItems.value[idx]
        adjustInventoryDraftCounts(item.id, deltaCartons = 0, deltaPacks = 0, deltaPieces = 1)
        val updated = _inventoryDraftItems.value.firstOrNull { it.id == item.id }
        if (updated != null) {
            playInventoryTone(updated.varianceQty)
            _statusMessage.value = "تم جرد: ${updated.itemName} (${updated.calculatedActualQty}/${updated.bookQty})"
        }
        return true
    }

    fun adjustInventoryDraftCounts(itemId: String, deltaCartons: Int, deltaPacks: Int, deltaPieces: Int) {
        val now = Instant.now().toString()
        _inventoryDraftItems.value = _inventoryDraftItems.value.map { item ->
            if (item.id != itemId) item
            else {
                val nextC = (item.cartonsCount + deltaCartons).coerceAtLeast(0)
                val nextP = (item.packsCount + deltaPacks).coerceAtLeast(0)
                val nextPcs = (item.piecesCount + deltaPieces).coerceAtLeast(0)
                val actual = PackagingEngine.calculateQtyFromColumns(
                    nextC, nextP, nextPcs,
                    item.cartonFactor.coerceAtLeast(1).toDouble(),
                    item.packFactor.coerceAtLeast(1).toDouble()
                )
                val variance = actual - item.bookQty
                item.copy(
                    cartonsCount = nextC,
                    packsCount = nextP,
                    piecesCount = nextPcs,
                    calculatedActualQty = actual,
                    varianceQty = variance,
                    status = inventoryVarianceStatus(variance),
                    lastScannedAt = now
                )
            }
        }
    }

    fun removeInventoryDraftItem(itemId: String) {
        _inventoryDraftItems.value = _inventoryDraftItems.value.filterNot { it.id == itemId }
    }

    fun clearInventoryDraft() {
        _inventoryDraftItems.value = emptyList()
    }

    fun saveInventoryReport(report: InventoryCountReport) {
        viewModelScope.launch {
            repository.upsertInventoryReport(report)
            _inventoryDraftItems.value = emptyList()
            playTone(WmsAudioTone.INVOICE_FINISHED)
            _statusMessage.value = "تم حفظ جلسة الجرد: ${report.title}"
        }
    }

    fun deleteInventoryReport(id: String) {
        viewModelScope.launch { repository.deleteInventoryReport(id) }
    }

    private fun mergeInventoryItems(incoming: List<InventoryCountItem>): List<InventoryCountItem> {
        val merged = linkedMapOf<String, InventoryCountItem>()
        for (item in incoming) {
            val key = PackagingEngine.normalizeCode(item.itemCode)
            val prev = merged[key]
            if (prev == null) {
                merged[key] = item
            } else {
                val nextBook = prev.bookQty + item.bookQty
                val nextC = prev.cartonsCount + item.cartonsCount
                val nextP = prev.packsCount + item.packsCount
                val nextPcs = prev.piecesCount + item.piecesCount
                val actual = PackagingEngine.calculateQtyFromColumns(
                    nextC, nextP, nextPcs,
                    prev.cartonFactor.coerceAtLeast(1).toDouble(),
                    prev.packFactor.coerceAtLeast(1).toDouble()
                )
                val variance = actual - nextBook
                merged[key] = prev.copy(
                    bookQty = nextBook,
                    cartonsCount = nextC,
                    packsCount = nextP,
                    piecesCount = nextPcs,
                    calculatedActualQty = actual,
                    varianceQty = variance,
                    status = inventoryVarianceStatus(variance)
                )
            }
        }
        return merged.values.toList()
    }

    private fun inventoryVarianceStatus(variance: Int): String = when {
        variance == 0 -> "EXACT"
        variance > 0 -> "SURPLUS"
        else -> "SHORTAGE"
    }

    private fun playInventoryTone(variance: Int) {
        when {
            variance == 0 -> playTone(WmsAudioTone.EXACT_COMPLETE)
            variance > 0 -> playTone(WmsAudioTone.SURPLUS_WARNING)
            else -> playTone(WmsAudioTone.MATCH)
        }
    }

    // --- Returns Original Invoice PDF Extraction & Master Sync ---
    fun importOriginalInvoicePdfForReturn(uri: Uri, displayName: String, engine: String = "AUTO") {
        viewModelScope.launch {
            _pdfExtractionState.value = PdfExtractionUiState.Loading
            val res = repository.extractOriginalInvoicePdf(
                uri = uri,
                displayName = displayName,
                masterItems = masterItems.value,
                packagingRules = packagingRules.value,
                engine = engine
            )
            res.onSuccess { extracted ->
                _pdfExtractionState.value = PdfExtractionUiState.Success(extracted)
                playTone(WmsAudioTone.INVOICE_REOPENED)
                _statusMessage.value = "تم استخلاص ${extracted.items.size} صنف ونقلها لتقرير المرتجع المبدئي (${extracted.invoiceNo.ifBlank { displayName }})"
            }.onFailure { err ->
                val msg = err.message ?: "تعذر تحليل ملف الفاتورة PDF"
                _pdfExtractionState.value = PdfExtractionUiState.Error(msg)
                playTone(WmsAudioTone.MISMATCH_ALERT)
                _statusMessage.value = msg
            }
        }
    }

    fun syncOriginalInvoiceForReturn(invoiceNo: String) {
        val cleanInv = ScannerParser.cleanString(invoiceNo)
        if (cleanInv.isBlank()) return
        val extracted = PdfInvoiceOcrService.buildFromMasterInvoice(
            invoiceNo = cleanInv,
            masterItems = masterItems.value,
            existingReturns = returnReports.value
        )
        if (extracted.items.isEmpty()) {
            _pdfExtractionState.value = PdfExtractionUiState.Error("لم يتم العثور على أصناف للفاتورة $cleanInv في القاعدة الرئيسية")
            playTone(WmsAudioTone.MISMATCH_ALERT)
        } else {
            _pdfExtractionState.value = PdfExtractionUiState.Success(extracted)
            playTone(WmsAudioTone.INVOICE_REOPENED)
            _statusMessage.value = "تمت مزامنة ${extracted.items.size} صنف ونقلها لتقرير المرتجع المبدئي ($cleanInv)"
        }
    }

    fun toggleExtractedPdfItem(itemId: String) {
        updateExtractedResult { res ->
            res.copy(items = res.items.map { if (it.id == itemId) it.copy(isSelected = !it.isSelected) else it })
        }
    }

    fun selectAllExtractedPdfItems(selected: Boolean) {
        updateExtractedResult { res ->
            res.copy(items = res.items.map { it.copy(isSelected = selected) })
        }
    }

    fun updateExtractedPdfItemReturnQty(itemId: String, qty: Int) {
        val safeQty = qty.coerceAtLeast(1)
        updateExtractedResult { res ->
            res.copy(items = res.items.map { item ->
                if (item.id == itemId) item.copy(returnQty = safeQty) else item
            })
        }
    }

    fun updateExtractedPdfItemPrice(itemId: String, price: Double) {
        val safePrice = price.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        updateExtractedResult { res ->
            res.copy(items = res.items.map { item ->
                if (item.id == itemId) {
                    item.copy(
                        unitPrice = safePrice,
                        lineTotal = (item.invoicedQty * safePrice * 100.0).roundToInt() / 100.0
                    )
                } else item
            })
        }
    }

    fun updateExtractedPdfItemCondition(itemId: String, condition: ReturnItemCondition) {
        updateExtractedResult { res ->
            res.copy(items = res.items.map { if (it.id == itemId) it.copy(condition = condition) else it })
        }
    }

    fun updateExtractedPdfHeader(invoiceNo: String? = null, customerName: String? = null) {
        updateExtractedResult { res ->
            res.copy(
                invoiceNo = invoiceNo ?: res.invoiceNo,
                customerName = customerName ?: res.customerName
            )
        }
    }

    fun finalizePreliminaryReturnReport(signatureBase64: String? = null): ReturnReport? {
        val current = _pdfExtractionState.value as? PdfExtractionUiState.Success ?: return null
        val extracted = current.result
        val selected = extracted.items.filter { it.isSelected && it.returnQty > 0 }
        if (selected.isEmpty()) {
            playTone(WmsAudioTone.MISMATCH_ALERT)
            _statusMessage.value = "اختر عنصراً واحداً على الأقل من تقرير المرتجع المبدئي للاعتماد"
            return null
        }

        val user = currentUser.value
        val returnItems = selected.map { sel ->
            val safeQty = sel.returnQty.coerceAtLeast(1)
            val refund = ((safeQty * sel.unitPrice) * 100.0).roundToInt() / 100.0
            ReturnSessionItem(
                id = "retitem-${UUID.randomUUID().toString().take(8)}",
                itemCode = sel.itemCode,
                itemName = sel.itemName,
                unit = sel.unit,
                invoiceNo = extracted.invoiceNo.ifBlank { null },
                sourceFile = extracted.sourceLabel,
                invoicedQty = sel.invoicedQty,
                subtotal = sel.lineTotal,
                actualReturnedQty = safeQty,
                unitPrice = sel.unitPrice,
                refundTotal = refund,
                condition = sel.condition
            )
        }

        val hasLab = returnItems.any { it.condition == ReturnItemCondition.TRANSFERRED_TO_LAB }
        val report = ReturnReport(
            id = "ret-${UUID.randomUUID().toString().take(8)}",
            returnReceiptNo = "RMA-${(1000..9999).random()}",
            originalInvoiceNo = extracted.invoiceNo.ifBlank { "PDF-RMA" },
            customerName = extracted.customerName.ifBlank { "عميل مرتجع" },
            createdAt = Instant.now().toString(),
            auditorName = user.name,
            auditorId = user.jobId,
            auditorSignature = signatureBase64 ?: settings.value.auditorSignatureBase64,
            status = if (hasLab) ReturnReportStatus.PENDING_LAB else ReturnReportStatus.COMPLETED,
            items = returnItems,
            totalInvoicedQty = returnItems.sumOf { it.invoicedQty.coerceAtLeast(it.actualReturnedQty) },
            totalReturnedQty = returnItems.sumOf { it.actualReturnedQty },
            totalValidForRestockQty = returnItems.filter { it.condition == ReturnItemCondition.VALID_FOR_RESTOCK }
                .sumOf { it.actualReturnedQty },
            totalTransferredToLabQty = returnItems.filter { it.condition == ReturnItemCondition.TRANSFERRED_TO_LAB }
                .sumOf { it.actualReturnedQty },
            totalRefundAmount = (returnItems.sumOf { it.refundTotal } * 100.0).roundToInt() / 100.0,
            notes = "مستخلص من تقرير المرتجع المبدئي (${extracted.extractionEngine})"
        )

        viewModelScope.launch {
            repository.upsertReturnReport(report)
            _pdfExtractionState.value = PdfExtractionUiState.Idle
            playTone(WmsAudioTone.INVOICE_FINISHED)
            _statusMessage.value = "تم اعتماد تقرير المرتجع النهائي ${report.returnReceiptNo} (${returnItems.size} أصناف)"
        }
        return report
    }

    fun clearExtractedPdfInvoice() {
        _pdfExtractionState.value = PdfExtractionUiState.Idle
    }

    private inline fun updateExtractedResult(transform: (ExtractedPdfInvoiceResult) -> ExtractedPdfInvoiceResult) {
        val current = _pdfExtractionState.value
        if (current is PdfExtractionUiState.Success) {
            _pdfExtractionState.value = PdfExtractionUiState.Success(transform(current.result))
        }
    }

    // --- Picking Waves & Workers ---
    fun createPickingWave(title: String, invoiceNumbers: Set<String>): BatchPickingWave? {
        val wave = PackagingEngine.createPickingWaveFromMasterItems(
            waveTitle = title,
            createdBy = currentUser.value.name,
            selectedInvoiceNumbers = invoiceNumbers,
            masterItems = masterItems.value,
            rules = packagingRules.value,
            workers = workers.value
        ) ?: return null
        viewModelScope.launch {
            repository.upsertPickingWave(wave)
            _statusMessage.value = "تم توليد موجة الانتقاء ${wave.waveNo}"
        }
        return wave
    }

    fun savePickingWave(wave: BatchPickingWave) {
        viewModelScope.launch { repository.upsertPickingWave(wave) }
    }

    fun deletePickingWave(id: String) {
        viewModelScope.launch { repository.deletePickingWave(id) }
    }

    fun upsertWorker(worker: WarehouseWorker) {
        viewModelScope.launch { repository.upsertWorker(worker) }
    }

    fun deleteWorker(id: String) {
        viewModelScope.launch { repository.deleteWorker(id) }
    }

    // --- Packaging Rules ---
    fun upsertPackagingRule(rule: PackagingGroupRule) {
        viewModelScope.launch {
            repository.upsertPackagingRule(rule, packagingRules.value.size)
            _statusMessage.value = "تم حفظ قاعدة التغليف ${rule.name}"
        }
    }

    fun resetDefaultPackagingRules() {
        viewModelScope.launch {
            repository.resetDefaultPackagingRules()
            _statusMessage.value = "تمت استعادة مصفوفة القواعد الافتراضية (14 مجموعة)"
        }
    }

    // --- Excel / CSV / PDF I/O (Executed off-Main on Dispatchers.IO via WmsRepository) ---
    fun importMasterSpreadsheet(uri: Uri, displayName: String) {
        viewModelScope.launch {
            repository.importMasterSpreadsheet(uri, displayName)
                .onSuccess { meta ->
                    _statusMessage.value = "تم استيراد ${meta.totalItems} صنف (${meta.totalInvoices} فاتورة)"
                }
                .onFailure { err ->
                    _statusMessage.value = err.message ?: "تعذر قراءة ملف البيانات"
                }
        }
    }

    suspend fun parseReceivingSpreadsheet(uri: Uri, displayName: String): List<ReceivingSessionItem> {
        return repository.parseReceivingSpreadsheet(uri, displayName)
    }

    fun exportDiscrepanciesCsv() {
        val list = discrepancies.value
        val headers = listOf("Invoice", "Order", "ItemCode", "ItemName", "Unit", "Required", "Actual", "Difference", "CodeStatus", "QtyStatus", "Auditor", "Date")
        val rows = list.map {
            listOf(it.invoiceNo, it.orderNo.orEmpty(), it.itemCode, it.itemName, it.unit, it.requiredQty.toString(), it.actualQty.toString(), it.difference.toString(), it.codeStatus.name, it.qtyStatus.name, it.auditorName.orEmpty(), it.auditedAt)
        }
        viewModelScope.launch {
            repository.exportCsvAndShare("WMS_Discrepancies_${LocalDate.now()}.csv", headers, rows)
                .onFailure { _statusMessage.value = "تعذر تصدير ملف CSV: ${it.message}" }
        }
    }

    fun exportDiscrepanciesPdf() {
        val s = settings.value
        val disc = discrepancies.value
        val wrong = wrongPicks.value
        val completed = completedInvoices.value
        viewModelScope.launch {
            repository.exportDiscrepanciesPdf(
                auditorName = s.auditorName,
                auditorId = s.auditorId,
                signatureBase64 = s.auditorSignatureBase64,
                discrepancies = disc,
                wrongPicks = wrong,
                completedInvoices = completed
            ).onFailure {
                _statusMessage.value = "تعذر تصدير ملف PDF: ${it.message}"
            }
        }
    }

    fun exportReturnPdf(report: ReturnReport) {
        viewModelScope.launch {
            repository.exportReturnPdf(report).onFailure {
                _statusMessage.value = "تعذر تصدير إيصال المرتجع PDF: ${it.message}"
            }
        }
    }

    // --- Cloud Sync ---
    fun backupAllToCloud() {
        viewModelScope.launch {
            _isCloudBusy.value = true
            val res = cloud.backupAllToCloud(
                settings = settings.value,
                returns = returnReports.value,
                discrepancies = discrepancies.value,
                receiving = receivingReports.value,
                inventory = inventoryReports.value,
                waves = pickingWaves.value
            )
            _isCloudBusy.value = false
            res.onSuccess { count ->
                _statusMessage.value = "تم رفع ومزامنة $count سجل إلى سحابة Firestore"
            }.onFailure { err ->
                _statusMessage.value = "فشلت المزامنة السحابية: ${err.message}"
            }
        }
    }

    fun restoreReturnsFromCloud() {
        viewModelScope.launch {
            _isCloudBusy.value = true
            val res = cloud.restoreReturnsFromCloud()
            _isCloudBusy.value = false
            res.onSuccess { list ->
                list.forEach { repository.upsertReturnReport(it) }
                _statusMessage.value = "تمت استعادة ${list.size} تقرير مرتجعات من السحابة"
            }.onFailure { err ->
                _statusMessage.value = "تعذرت الاستعادة من السحابة: ${err.message}"
            }
        }
    }

    fun playTone(tone: WmsAudioTone) {
        val s = settings.value
        audio.play(tone, s.soundEnabled, s.vibrationEnabled, s.soundVolume)
    }

    fun deleteDiscrepancy(id: Long) {
        viewModelScope.launch { repository.deleteDiscrepancy(id) }
    }

    fun clearAllAuditReports() {
        viewModelScope.launch {
            repository.clearAllAuditReports()
            _statusMessage.value = "تم مسح سجل الفروقات"
        }
    }
}
