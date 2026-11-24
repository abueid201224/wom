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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val auditStateLock = Any()
    private val scanMutex = Mutex()
    private val persistenceMutex = Mutex()

    private data class ScanCommand(
        val rawInput: String,
        val qtyMultiplier: Int,
        val bypassDebounce: Boolean
    )

    private val scanQueue = Channel<ScanCommand>(Channel.UNLIMITED)
    private val persistenceQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    val settings: StateFlow<AppSettings> = repository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val syncMetadata: StateFlow<SyncMetadata> = repository.syncMetadataFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, SyncMetadata())

    private val _activeSession = MutableStateFlow<ActiveInvoiceSession?>(null)
    val activeSession: StateFlow<ActiveInvoiceSession?> = _activeSession.asStateFlow()
    @Volatile private var activeSessionMutatedLocally = false
    @Volatile private var latestSavedSessionRef: ActiveInvoiceSession? = null

    private val _isCheckpointPending = MutableStateFlow(false)
    val isCheckpointPending: StateFlow<Boolean> = _isCheckpointPending.asStateFlow()

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
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ROLE_DEFINITIONS.getValue(UserRole.SUPERVISOR))

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

    private val _pendingInvoiceSwitch = MutableStateFlow<PendingInvoiceSwitchConfirmation?>(null)
    val pendingInvoiceSwitch: StateFlow<PendingInvoiceSwitchConfirmation?> = _pendingInvoiceSwitch.asStateFlow()

    private val _pendingSupervisorOverride = MutableStateFlow<PendingSupervisorOverride?>(null)
    val pendingSupervisorOverride: StateFlow<PendingSupervisorOverride?> = _pendingSupervisorOverride.asStateFlow()

    private val _lastPlayedTone = MutableStateFlow<WmsAudioTone?>(null)
    val lastPlayedTone: StateFlow<WmsAudioTone?> = _lastPlayedTone.asStateFlow()

    private val _playedToneHistory = MutableStateFlow<List<WmsAudioTone>>(emptyList())
    val playedToneHistory: StateFlow<List<WmsAudioTone>> = _playedToneHistory.asStateFlow()

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
            deferredInvoices = incomplete.size,
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
        activeSession,
        incompleteInvoices
    ) { values ->
        AuditSessionEngine.buildSessionApprovalSummary(
            master = values[0] as List<MasterInvoiceItem>,
            completed = values[1] as List<CompletedInvoiceRecord>,
            history = values[2] as List<InvoiceAuditHistory>,
            rawDiscrepancies = values[3] as List<AuditDiscrepancy>,
            snapshots = values[4] as List<DailyAuditSnapshot>,
            activeSession = values[5] as ActiveInvoiceSession?,
            incomplete = values[6] as List<IncompleteInvoiceRecord>
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionApprovalSummary())

    init {
        viewModelScope.launch {
            for (task in persistenceQueue) {
                persistenceMutex.withLock {
                    runCatching { task() }
                }
            }
        }
        viewModelScope.launch {
            for (cmd in scanQueue) {
                scanMutex.withLock {
                    executeScanCommand(cmd.rawInput, cmd.qtyMultiplier, cmd.bypassDebounce)
                }
            }
        }
        viewModelScope.launch {
            val persisted = repository.activeSessionFlow.first()
            synchronized(auditStateLock) {
                if (!activeSessionMutatedLocally && persisted != null) {
                    val normalized = if (persisted.sessionId.isBlank()) {
                        persisted.copy(
                            sessionId = "ses-${UUID.randomUUID().toString().take(12)}",
                            lastCheckpointAtMs = System.currentTimeMillis()
                        )
                    } else persisted
                    _activeSession.value = normalized
                    latestSavedSessionRef = normalized
                }
            }
        }
        viewModelScope.launch {
            GoogleAuthManager.attemptAutoSignIn(app)
        }
    }

    private fun enqueueSessionCheckpoint() {
        _isCheckpointPending.value = true
        persistenceQueue.trySend {
            val snapshotToSave = synchronized(auditStateLock) {
                val current = _activeSession.value
                if (current == null || current === latestSavedSessionRef) {
                    null
                } else {
                    current
                }
            }
            if (snapshotToSave != null) {
                repository.saveActiveSession(snapshotToSave)
                latestSavedSessionRef = snapshotToSave
            }
            synchronized(auditStateLock) {
                val current = _activeSession.value
                _isCheckpointPending.value = (current != null && current !== latestSavedSessionRef)
            }
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
        val cleanPin = pinAttempt.trim()
        val isValid = when (user.role) {
            UserRole.SUPERVISOR -> {
                val configuredPin = settings.value.supervisorPinCode
                if (settings.value.supervisorPinConfigured && configuredPin.isNotBlank()) {
                    cleanPin == configuredPin || cleanPin == user.pinCode
                } else {
                    cleanPin == user.pinCode
                }
            }
            UserRole.GUEST -> cleanPin == user.pinCode || cleanPin == "0000"
            else -> cleanPin == user.pinCode
        }
        if (!isValid) return false
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

    fun configureSupervisorPin(newPin: String): Boolean {
        val cleanPin = newPin.trim()
        if (cleanPin.length < 4) return false
        viewModelScope.launch {
            val s = settings.value
            repository.saveSettings(
                s.copy(
                    supervisorPinConfigured = true,
                    supervisorPinCode = cleanPin
                )
            )
            val supUsers = appUsers.value.filter { it.role == UserRole.SUPERVISOR }
            supUsers.forEach { sup ->
                repository.upsertAppUser(sup.copy(pinCode = cleanPin))
            }
            _statusMessage.value = "تم تعيين وحفظ رمز المشرف بنجاح"
        }
        return true
    }

    fun switchToTrialAuditor() {
        val trialAuditor = appUsers.value.firstOrNull { it.role == UserRole.AUDITOR }
            ?: SeedData.defaultDemoUsers.first { it.role == UserRole.AUDITOR }
        viewModelScope.launch {
            val s = settings.value
            val updatedSettings = s.copy(
                auditorName = trialAuditor.name,
                auditorId = trialAuditor.jobId,
                auditorTitle = trialAuditor.title ?: "مدقق رقابي تجريبي"
            )
            repository.switchCurrentUser(
                trialAuditor.copy(lastLoginAt = Instant.now().toString()),
                updatedSettings
            )
            _statusMessage.value = "تم التبديل إلى حساب المدقق التجريبي الافتراضي: ${trialAuditor.name}"
        }
    }

    fun isValidSupervisorPin(pinAttempt: String): Boolean {
        val clean = pinAttempt.trim()
        if (clean.isBlank()) return false
        val s = settings.value
        if (s.supervisorPinConfigured && s.supervisorPinCode.isNotBlank() && clean == s.supervisorPinCode) {
            return true
        }
        return appUsers.value.any { it.role == UserRole.SUPERVISOR && it.pinCode == clean }
    }

    fun authorizeSupervisorOverride(pinAttempt: String): Boolean {
        val pending = _pendingSupervisorOverride.value ?: return false
        if (!isValidSupervisorPin(pinAttempt)) return false
        _pendingSupervisorOverride.value = null
        playTone(WmsAudioTone.INVOICE_REOPENED)
        when (pending.actionType) {
            SupervisorOverrideActionType.DEFER_BEYOND_LIMIT -> {
                deferCurrentInvoiceForCompletion(
                    recordShortages = pending.recordShortages,
                    supervisorOverrideGranted = true
                )
            }
            SupervisorOverrideActionType.REOPEN_COMPLETED_INVOICE -> {
                reopenCompletedInvoice(
                    invoiceNo = pending.invoiceNo,
                    supervisorOverrideGranted = true
                )
            }
        }
        return true
    }

    fun dismissSupervisorOverride() {
        _pendingSupervisorOverride.value = null
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

    // --- Audit Session Lifecycle (Thread-Safe + Auto-Checkpointed) ---
    fun confirmPendingInvoiceSwitch() {
        val pending = _pendingInvoiceSwitch.value ?: return
        _pendingInvoiceSwitch.value = null
        startOrOpenInvoice(pending.targetInvoiceNo, bypassSwitchSafety = true)
    }

    fun dismissPendingInvoiceSwitch() {
        _pendingInvoiceSwitch.value = null
    }

    fun startOrOpenInvoice(
        invoiceNoRaw: String,
        skipCompletedCheck: Boolean = false,
        bypassSwitchSafety: Boolean = false
    ): Boolean {
        val invoiceNo = ScannerParser.cleanString(invoiceNoRaw)
        if (invoiceNo.isBlank()) return false

        synchronized(auditStateLock) {
            val currentOpenSession = _activeSession.value
            if (currentOpenSession != null && currentOpenSession.invoiceNo.equals(invoiceNo, ignoreCase = true)) {
                _pendingInvoiceSwitch.value = null
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
            val invoiceRows = if (incomplete == null) {
                masterItems.value.filter { it.invoiceNo.equals(invoiceNo, ignoreCase = true) }
            } else emptyList()

            if (incomplete == null && invoiceRows.isEmpty()) {
                playTone(WmsAudioTone.MISMATCH_ALERT)
                _scanBanner.value = ScanFeedbackBanner.Error(
                    "رقم الفاتورة $invoiceNo غير موجود في القاعدة الرئيسية.",
                    "Invoice $invoiceNo not found in master database."
                )
                return false
            }

            val resolvedInvoiceNo = incomplete?.invoiceNo ?: invoiceRows.first().invoiceNo

            if (!bypassSwitchSafety && currentOpenSession != null) {
                val decision = AuditSessionEngine.evaluateInvoiceSwitchGuard(
                    currentOpenSession = currentOpenSession,
                    targetInvoiceNo = resolvedInvoiceNo,
                    requireDoubleScan = settings.value.requireDoubleScanToSwitchInvoice,
                    pendingConfirmation = _pendingInvoiceSwitch.value
                )
                if (decision is AuditSessionEngine.SwitchSafetyDecision.RequireSecondScan) {
                    _pendingInvoiceSwitch.value = decision.prompt
                    playTone(WmsAudioTone.SURPLUS_WARNING)
                    _scanBanner.value = ScanFeedbackBanner.Warning(
                        "تنبيه أمان التبديل: الفاتورة ${currentOpenSession.invoiceNo} مفتوحة. امسح $resolvedInvoiceNo مرة أخرى خلال ثانيتين للإغلاق والتبديل.",
                        "Switch Guard: ${currentOpenSession.invoiceNo} is open. Scan $resolvedInvoiceNo again within 2s to close & switch."
                    )
                    return false
                }
            }
            _pendingInvoiceSwitch.value = null

            val nowMs = System.currentTimeMillis()
            val nowIso = Instant.now().toString()
            val targetSession: ActiveInvoiceSession = if (incomplete != null) {
                incomplete.session.copy(
                    sessionId = incomplete.session.sessionId.ifBlank { "ses-${UUID.randomUUID().toString().take(12)}" },
                    auditorSignature = null,
                    lastCheckpointAtMs = nowMs
                )
            } else {
                val user = currentUser.value
                AuditSessionEngine.buildNewSession(
                    invoiceRows = invoiceRows,
                    auditorName = user.name,
                    auditorId = user.jobId,
                    nowIso = nowIso,
                    nowMs = nowMs
                )
            }

            // Automatically close the previously active invoice (without signature) before switching
            val user = currentUser.value
            val prevArtifacts = currentOpenSession?.let {
                AuditSessionEngine.buildClosedInvoiceArtifacts(
                    session = it,
                    auditorName = user.name,
                    auditorId = user.jobId,
                    nowIso = nowIso
                )
            }

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
                    "تم فتح الفاتورة المرحّلة للاستكمال ${targetSession.invoiceNo} (ترحيل #${incomplete.deferCount})",
                    "Opened deferred invoice ${targetSession.invoiceNo} (deferral #${incomplete.deferCount})"
                )
                else -> ScanFeedbackBanner.Success(
                    "تم فتح الفاتورة ${targetSession.invoiceNo} وقفلها على عناصرها (${targetSession.items.size} أصناف) — يُمنع أي باركود خارجي",
                    "Opened invoice ${targetSession.invoiceNo} (Locked to ${targetSession.items.size} items only)"
                )
            }

            _isCheckpointPending.value = true
            persistenceQueue.trySend {
                if (prevArtifacts != null) {
                    repository.finalizeAuditSession(
                        discrepancies = prevArtifacts.first,
                        completed = prevArtifacts.second,
                        history = prevArtifacts.third,
                        clearActiveSession = false
                    )
                }
                val latestToSave = synchronized(auditStateLock) { _activeSession.value }
                if (latestToSave != null) {
                    repository.saveActiveSession(latestToSave)
                    latestSavedSessionRef = latestToSave
                }
                synchronized(auditStateLock) {
                    val cur = _activeSession.value
                    _isCheckpointPending.value = (cur != null && cur !== latestSavedSessionRef)
                }
            }
            return true
        }
    }

    fun processAuditScan(rawInput: String, qtyMultiplier: Int = 1, bypassDebounce: Boolean = false) {
        val code = ScannerParser.extractCode(rawInput)
        val s = settings.value
        if (code.length < s.scannerMinLength) return
        if (!bypassDebounce && !scanDebouncer.shouldAccept(code)) return
        scanQueue.trySend(ScanCommand(rawInput = code, qtyMultiplier = qtyMultiplier, bypassDebounce = bypassDebounce))
    }

    private fun executeScanCommand(code: String, qtyMultiplier: Int, @Suppress("UNUSED_PARAMETER") bypassDebounce: Boolean) {
        val s = settings.value
        val curSession = synchronized(auditStateLock) { _activeSession.value }

        val isExplicitInvoicePrefix = s.scannerPrefixInvoice.isNotBlank() &&
            code.startsWith(s.scannerPrefixInvoice, ignoreCase = true)
        val matchesKnownInvoice = masterItems.value.any { it.invoiceNo.equals(code, ignoreCase = true) }

        if (isExplicitInvoicePrefix || (curSession == null && matchesKnownInvoice) ||
            (s.autoSwitchOnNewInvoice && matchesKnownInvoice && curSession?.invoiceNo?.equals(code, ignoreCase = true) == false)
        ) {
            startOrOpenInvoice(code)
            return
        }

        synchronized(auditStateLock) {
            val latestSession = _activeSession.value
            if (latestSession == null) {
                playTone(WmsAudioTone.MISMATCH_ALERT)
                _scanBanner.value = ScanFeedbackBanner.Warning(
                    "افتح فاتورة أولاً قبل مسح باركود الصنف ($code)",
                    "Open an invoice first before scanning item ($code)"
                )
                return
            }

            if (latestSession.isLocked) {
                playTone(WmsAudioTone.INVOICE_LOCKED)
                _scanBanner.value = ScanFeedbackBanner.Warning(
                    "الفاتورة الحالية مقفلة. أعد فتحها للتعديل.",
                    "Current invoice is locked."
                )
                return
            }

            if (code.length > s.longBarcodeThreshold && latestSession.longBarcodePolicy == LongBarcodePolicy.ASK) {
                playTone(WmsAudioTone.LONG_BARCODE_SIREN)
                _pendingLongBarcode.value = PendingLongBarcodePrompt(code, code.length)
                return
            }

            if (code.length > s.longBarcodeThreshold && latestSession.longBarcodePolicy == LongBarcodePolicy.BLOCK) {
                playTone(WmsAudioTone.LONG_BARCODE_SIREN)
                _scanBanner.value = ScanFeedbackBanner.Error(
                    "تم حظر الباركود الطويل ($code) حسب سياسة الجلسة",
                    "Long barcode ($code) blocked by session policy"
                )
                return
            }

            applyItemScanToSession(latestSession, code, qtyMultiplier.coerceAtLeast(1))
        }
    }

    fun resolvePendingLongBarcode(allowForSession: Boolean) {
        val prompt = _pendingLongBarcode.value ?: return
        _pendingLongBarcode.value = null
        synchronized(auditStateLock) {
            val cur = _activeSession.value ?: return
            val updated = cur.copy(
                longBarcodePolicy = if (allowForSession) LongBarcodePolicy.ALLOW else LongBarcodePolicy.BLOCK,
                lastCheckpointAtMs = System.currentTimeMillis()
            )
            activeSessionMutatedLocally = true
            _activeSession.value = updated
            enqueueSessionCheckpoint()
            if (allowForSession) {
                applyItemScanToSession(updated, prompt.code, 1)
            }
        }
    }

    private fun applyItemScanToSession(session: ActiveInvoiceSession, rawCode: String, deltaQty: Int) {
        when (val outcome = AuditSessionEngine.applyItemScan(session, rawCode, deltaQty, masterItems.value)) {
            AuditSessionEngine.ItemScanOutcome.SessionLocked -> Unit
            is AuditSessionEngine.ItemScanOutcome.RejectedOutsideInvoice -> {
                playTone(WmsAudioTone.MISMATCH_ALERT)
                val belong = outcome.belongingInvoiceNo
                _scanBanner.value = ScanFeedbackBanner.Error(
                    if (belong != null)
                        "🚫 باركود مرفوض! (${outcome.rawCode}) خارج الفاتورة ${outcome.activeInvoiceNo} ويتبع الفاتورة $belong — لم يتم تسجيله"
                    else
                        "🚫 باركود مرفوض خارج الفاتورة! الفاتورة ${outcome.activeInvoiceNo} مقفلة على عناصرها فقط — لم يتم تسجيل (${outcome.rawCode})",
                    if (belong != null)
                        "🚫 Rejected! (${outcome.rawCode}) is outside invoice ${outcome.activeInvoiceNo} (belongs to $belong) — Not recorded"
                    else
                        "🚫 Rejected outside barcode! Invoice ${outcome.activeInvoiceNo} is locked to its items only — (${outcome.rawCode}) not recorded"
                )
            }
            is AuditSessionEngine.ItemScanOutcome.Updated -> {
                val updatedSession = outcome.updatedSession
                val updatedItem = outcome.updatedItem
                activeSessionMutatedLocally = true
                _activeSession.value = updatedSession
                if (outcome.reachedExact100Invoice) {
                    playTone(WmsAudioTone.EXACT_COMPLETE)
                    _scanBanner.value = ScanFeedbackBanner.Success(
                        "🎉 اكتمل مسح الفاتورة ${updatedSession.invoiceNo} بنسبة 100%! جميع الأصناف (${updatedSession.exactItemsCount}/${updatedSession.totalItemsCount}) مطابقة وجاهزة للإغلاق.",
                        "🎉 Invoice ${updatedSession.invoiceNo} 100% Complete! All ${updatedSession.totalItemsCount} items matched."
                    )
                } else {
                    when (updatedItem.qtyStatus) {
                        QtyStatus.EXACT -> {
                            playTone(WmsAudioTone.EXACT_COMPLETE)
                            _scanBanner.value = ScanFeedbackBanner.Success(
                                "مطابقة الصنف: ${updatedItem.itemName} (${updatedItem.actualQty}/${updatedItem.requiredQty}) • تقدم الفاتورة ${updatedSession.progressPercent}%",
                                "Item Exact Match: ${updatedItem.itemName} (${updatedItem.actualQty}/${updatedItem.requiredQty}) • Invoice ${updatedSession.progressPercent}%"
                            )
                        }
                        QtyStatus.SURPLUS -> {
                            playTone(WmsAudioTone.SURPLUS_WARNING)
                            _scanBanner.value = ScanFeedbackBanner.Warning(
                                "تنبيه زيادة: ${updatedItem.itemName} (${updatedItem.actualQty}/${updatedItem.requiredQty}) • تقدم الفاتورة ${updatedSession.progressPercent}%",
                                "Surplus Alert: ${updatedItem.itemName} (${updatedItem.actualQty}/${updatedItem.requiredQty}) • Invoice ${updatedSession.progressPercent}%"
                            )
                        }
                        QtyStatus.SHORTAGE -> {
                            playTone(WmsAudioTone.MATCH)
                            _scanBanner.value = ScanFeedbackBanner.Success(
                                "تم المسح: ${updatedItem.itemName} (${updatedItem.actualQty}/${updatedItem.requiredQty}) • التقدم ${updatedSession.progressPercent}%",
                                "Scanned: ${updatedItem.itemName} (${updatedItem.actualQty}/${updatedItem.requiredQty}) • Progress ${updatedSession.progressPercent}%"
                            )
                        }
                    }
                }
                enqueueSessionCheckpoint()
            }
        }
    }

    fun adjustAuditItemQty(key: String, delta: Int) {
        synchronized(auditStateLock) {
            val session = _activeSession.value ?: return
            val updatedSession = AuditSessionEngine.adjustItemQty(session, key, delta) ?: return
            activeSessionMutatedLocally = true
            _activeSession.value = updatedSession
            if (updatedSession.isFullyCompleted) {
                playTone(WmsAudioTone.EXACT_COMPLETE)
                _scanBanner.value = ScanFeedbackBanner.Success(
                    "🎉 اكتمل مسح الفاتورة ${updatedSession.invoiceNo} بنسبة 100%! جميع الأصناف (${updatedSession.exactItemsCount}/${updatedSession.totalItemsCount}) مطابقة وجاهزة للإغلاق.",
                    "🎉 Invoice ${updatedSession.invoiceNo} 100% Complete! All ${updatedSession.totalItemsCount} items matched."
                )
            }
            enqueueSessionCheckpoint()
        }
    }

    fun suspendCurrentSessionToIncomplete() {
        deferCurrentInvoiceForCompletion(recordShortages = false)
    }

    fun deferCurrentInvoiceForCompletion(
        recordShortages: Boolean = false,
        supervisorOverrideGranted: Boolean = false
    ) {
        synchronized(auditStateLock) {
            val session = _activeSession.value ?: return
            val user = currentUser.value
            val s = settings.value
            if (!supervisorOverrideGranted && AuditSessionEngine.requiresSupervisorForDefer(
                    currentDeferCount = session.deferCount,
                    userRole = user.role,
                    requireSupervisorOverride = s.requireSupervisorOverride
                )
            ) {
                val nextAttempt = session.deferCount + 1
                playTone(WmsAudioTone.INVOICE_LOCKED)
                val msgAr = "تم تجاوز المحاولات الخمس للترحيل (محاولة #$nextAttempt للفاتورة ${session.invoiceNo}). يلزم تمرير المشرف بإدخال رمزه."
                val msgEn = "Max 5 deferral attempts exceeded (Attempt #$nextAttempt for ${session.invoiceNo}). Supervisor PIN override required."
                _pendingSupervisorOverride.value = PendingSupervisorOverride(
                    actionType = SupervisorOverrideActionType.DEFER_BEYOND_LIMIT,
                    invoiceNo = session.invoiceNo,
                    recordShortages = recordShortages,
                    attemptNumber = nextAttempt,
                    titleAr = "تمرير صلاحيات المشرف للترحيل",
                    titleEn = "Supervisor Deferral Override",
                    messageAr = msgAr,
                    messageEn = msgEn
                )
                _scanBanner.value = ScanFeedbackBanner.Warning(msgAr, msgEn)
                _statusMessage.value = msgAr
                return
            }

            val record = AuditSessionEngine.buildDeferredInvoiceRecord(
                session = session,
                auditorName = user.name,
                auditorId = user.jobId,
                recordShortagesReport = recordShortages
            )
            activeSessionMutatedLocally = true
            _activeSession.value = null
            latestSavedSessionRef = null
            _isCheckpointPending.value = false
            _pendingInvoiceSwitch.value = null
            persistenceQueue.trySend {
                repository.suspendSession(record)
                playTone(if (recordShortages) WmsAudioTone.SURPLUS_WARNING else WmsAudioTone.INVOICE_LOCKED)
                _statusMessage.value = if (recordShortages) {
                    "تم ترحيل الفاتورة ${session.invoiceNo} مع تسجيل النواقص ضمن تقرير فواتير غير مكتملة (محاولة #${record.deferCount})"
                } else {
                    "تم ترحيل الفاتورة ${session.invoiceNo} للاستكمال وإعادة المسح (محاولة #${record.deferCount})"
                }
            }
        }
    }

    fun closeActiveInvoice() {
        synchronized(auditStateLock) {
            val session = _activeSession.value ?: return
            val user = currentUser.value
            val (discList, completed, history) = AuditSessionEngine.buildClosedInvoiceArtifacts(
                session = session,
                auditorName = user.name,
                auditorId = user.jobId
            )

            val allMasterInvoices = masterItems.value.map { it.invoiceNo }.distinct()
            val nextClosedSet = (completedInvoices.value.map { it.invoiceNo } + session.invoiceNo).toSet()
            val remainingDeferred = incompleteInvoices.value.filterNot {
                it.invoiceNo.equals(session.invoiceNo, ignoreCase = true)
            }
            val allDatabaseInvoicesFinished = allMasterInvoices.isNotEmpty() &&
                remainingDeferred.isEmpty() &&
                allMasterInvoices.all { inv -> nextClosedSet.any { it.equals(inv, ignoreCase = true) } }

            activeSessionMutatedLocally = true
            _activeSession.value = null
            latestSavedSessionRef = null
            _isCheckpointPending.value = false
            _pendingInvoiceSwitch.value = null
            if (allDatabaseInvoicesFinished) {
                _showSessionApprovalModal.value = true
            }

            persistenceQueue.trySend {
                repository.finalizeAuditSession(
                    discrepancies = discList,
                    completed = completed,
                    history = history,
                    clearActiveSession = true
                )
                if (allDatabaseInvoicesFinished) {
                    playTone(WmsAudioTone.SESSION_COMPLETED_FANFARE)
                    _statusMessage.value = "اكتملت جميع الفواتير — التقرير جاهز لاعتماد وإنهاء الجلسة من حساب المدقق"
                } else {
                    playTone(WmsAudioTone.INVOICE_FINISHED)
                    _statusMessage.value = "تم إغلاق الفاتورة ${session.invoiceNo} (الاعتماد النهائي بعد استكمال جميع الفواتير)"
                }
            }
        }
    }

    fun reopenCompletedInvoice(
        invoiceNo: String,
        supervisorOverrideGranted: Boolean = false
    ) {
        val s = settings.value
        if (!supervisorOverrideGranted && s.requireSupervisorOverride && !currentRoleConfig.value.canReopenDocuments) {
            playTone(WmsAudioTone.INVOICE_LOCKED)
            val msgAr = "إعادة فتح الفاتورة المكتملة ($invoiceNo) تتطلب تمرير صلاحيات المشرف بإدخال رمزه."
            val msgEn = "Reopening completed invoice ($invoiceNo) requires Supervisor PIN override."
            _pendingSupervisorOverride.value = PendingSupervisorOverride(
                actionType = SupervisorOverrideActionType.REOPEN_COMPLETED_INVOICE,
                invoiceNo = invoiceNo,
                titleAr = "تمرير المشرف لإعادة الفتح",
                titleEn = "Supervisor Reopen Override",
                messageAr = msgAr,
                messageEn = msgEn
            )
            _statusMessage.value = msgAr
            return
        }
        viewModelScope.launch {
            persistenceMutex.withLock {
                repository.deleteCompletedInvoice(invoiceNo)
            }
            playTone(WmsAudioTone.INVOICE_REOPENED)
            startOrOpenInvoice(invoiceNo, skipCompletedCheck = true)
        }
    }

    fun approveFullSessionReport(signatureBase64: String? = null, notes: String? = null): DailyAuditSnapshot? {
        if (!currentRoleConfig.value.canApproveSession) {
            playTone(WmsAudioTone.BLOCKED_COMPLETED)
            _statusMessage.value = "إنهاء واعتماد الجلسة متاح لحساب المدقق فقط وليس المشرف — يرجى التبديل لحساب المدقق"
            return null
        }
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
            notes = notes ?: "اعتماد وإنهاء الجلسة من المدقق ${user.name} (${summary.auditedInvoicesCount} فاتورة • الدقة ${summary.matchAccuracyPercent}%)"
        )

        _showSessionApprovalModal.value = false
        viewModelScope.launch {
            if (!signatureBase64.isNullOrBlank() && s.auditorSignatureBase64.isNullOrBlank()) {
                repository.saveSettings(s.copy(auditorSignatureBase64 = signatureBase64))
            }
            repository.upsertDailySnapshot(snapshot)
            playTone(WmsAudioTone.EXACT_COMPLETE)
            _statusMessage.value = "تم اعتماد وإنهاء الجلسة بواسطة المدقق ${user.name} (${snapshot.completedInvoices} فاتورة)"
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
        val summary = sessionApprovalSummary.value
        viewModelScope.launch {
            repository.exportDiscrepanciesPdf(
                auditorName = s.auditorName,
                auditorId = s.auditorId,
                signatureBase64 = s.auditorSignatureBase64,
                discrepancies = disc,
                wrongPicks = wrong,
                completedInvoices = completed,
                sessionSummary = summary,
                isRtl = s.language == "ar"
            ).onFailure {
                _statusMessage.value = "تعذر تصدير ملف PDF: ${it.message}"
            }
        }
    }

    fun exportIncompleteInvoicesPdf() {
        val s = settings.value
        val records = incompleteInvoices.value.filter { it.recordShortagesReport }
        if (records.isEmpty()) {
            _statusMessage.value = "لا توجد فواتير مرحّلة مع تسجيل النواقص لتصدير تقرير فواتير غير مكتملة"
            return
        }
        viewModelScope.launch {
            repository.exportIncompleteInvoicesPdf(
                auditorName = s.auditorName,
                auditorId = s.auditorId,
                records = records,
                isRtl = s.language == "ar"
            ).onFailure {
                _statusMessage.value = "تعذر تصدير تقرير فواتير غير مكتملة PDF: ${it.message}"
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
        _lastPlayedTone.value = tone
        _playedToneHistory.value = (_playedToneHistory.value + tone).takeLast(50)
        val s = settings.value
        audio.play(tone, s.soundEnabled, s.vibrationEnabled, s.soundVolume)
    }

    fun clearToneHistory() {
        _lastPlayedTone.value = null
        _playedToneHistory.value = emptyList()
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

    override fun onCleared() {
        scanQueue.close()
        persistenceQueue.close()
        super.onCleared()
    }
}
