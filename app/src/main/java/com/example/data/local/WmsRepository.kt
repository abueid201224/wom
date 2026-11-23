package com.example.data.local

import android.content.Context
import android.net.Uri
import com.example.model.*
import com.example.services.PdfInvoiceOcrService
import com.example.services.PdfService
import com.example.services.SpreadsheetService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

class WmsRepository(
    private val context: Context,
    private val dao: WmsDao,
    private val prefs: PreferencesManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    val settingsFlow: Flow<AppSettings> = prefs.settingsFlow
    val syncMetadataFlow: Flow<SyncMetadata> = prefs.syncMetadataFlow
    val activeSessionFlow: Flow<ActiveInvoiceSession?> = prefs.activeSessionFlow
    val currentUserIdFlow: Flow<String> = prefs.currentUserIdFlow

    val masterItemsFlow: Flow<List<MasterInvoiceItem>> = dao.observeMasterItems()
        .map { list -> list.map { it.toDomain() } }
        .flowOn(ioDispatcher)

    val discrepanciesFlow: Flow<List<AuditDiscrepancy>> = dao.observeDiscrepancies()
        .map { list -> list.map { it.toDomain() } }
        .flowOn(ioDispatcher)

    val wrongPicksFlow: Flow<List<WrongPickingRecord>> = dao.observeWrongPicks()
        .map { list -> list.map { it.toDomain() } }
        .flowOn(ioDispatcher)

    val completedInvoicesFlow: Flow<List<CompletedInvoiceRecord>> = dao.observeCompletedInvoices()
        .map { list -> list.map { it.toDomain() } }
        .flowOn(ioDispatcher)

    val incompleteInvoicesFlow: Flow<List<IncompleteInvoiceRecord>> = dao.observeIncompleteInvoices()
        .map { list -> list.map { it.toDomain() } }
        .flowOn(ioDispatcher)

    val auditHistoryFlow: Flow<List<InvoiceAuditHistory>> = dao.observeAuditHistory()
        .map { list -> list.map { it.toDomain() } }
        .flowOn(ioDispatcher)

    val dailySnapshotsFlow: Flow<List<DailyAuditSnapshot>> = dao.observeDailySnapshots()
        .map { list -> list.map { it.toDomain() } }
        .flowOn(ioDispatcher)

    val returnReportsFlow: Flow<List<ReturnReport>> = dao.observeReturnReports()
        .map { list -> list.map { it.toDomain() } }
        .flowOn(ioDispatcher)

    val receivingReportsFlow: Flow<List<ReceivingReport>> = dao.observeReceivingReports()
        .map { list -> list.map { it.toDomain() } }
        .flowOn(ioDispatcher)

    val inventoryReportsFlow: Flow<List<InventoryCountReport>> = dao.observeInventoryReports()
        .map { list -> list.map { it.toDomain() } }
        .flowOn(ioDispatcher)

    val packagingRulesFlow: Flow<List<PackagingGroupRule>> = dao.observePackagingRules()
        .map { list -> list.map { it.toDomain() } }
        .flowOn(ioDispatcher)

    val pickingWavesFlow: Flow<List<BatchPickingWave>> = dao.observePickingWaves()
        .map { list -> list.map { it.toDomain() } }
        .flowOn(ioDispatcher)

    val workersFlow: Flow<List<WarehouseWorker>> = dao.observeWorkers()
        .map { list -> list.map { it.toDomain() } }
        .flowOn(ioDispatcher)

    val appUsersFlow: Flow<List<AppUser>> = dao.observeAppUsers()
        .map { list -> list.map { it.toDomain() } }
        .flowOn(ioDispatcher)

    suspend fun saveSettings(settings: AppSettings) = withContext(ioDispatcher) {
        prefs.saveSettings(settings)
    }

    suspend fun saveActiveSession(session: ActiveInvoiceSession?) = withContext(ioDispatcher) {
        prefs.saveActiveSession(session)
    }

    suspend fun switchCurrentUser(user: AppUser, updatedSettings: AppSettings) = withContext(ioDispatcher) {
        prefs.setCurrentUserId(user.id)
        prefs.saveSettings(updatedSettings)
        dao.upsertAppUser(AppUserEntity.fromDomain(user))
    }

    suspend fun upsertAppUser(user: AppUser) = withContext(ioDispatcher) {
        dao.upsertAppUser(AppUserEntity.fromDomain(user))
    }

    suspend fun insertWrongPick(record: WrongPickingRecord) = withContext(ioDispatcher) {
        dao.insertWrongPick(WrongPickEntity.fromDomain(record))
    }

    suspend fun suspendSession(record: IncompleteInvoiceRecord) = withContext(ioDispatcher) {
        dao.upsertIncompleteInvoice(IncompleteInvoiceEntity.fromDomain(record))
        prefs.saveActiveSession(null)
    }

    suspend fun finalizeAuditSession(
        discrepancies: List<AuditDiscrepancy>,
        completed: CompletedInvoiceRecord,
        history: InvoiceAuditHistory,
        clearActiveSession: Boolean = true
    ) = withContext(ioDispatcher) {
        dao.deleteDiscrepanciesByInvoice(completed.invoiceNo)
        dao.deleteAuditHistoryByInvoice(completed.invoiceNo)
        if (discrepancies.isNotEmpty()) {
            dao.insertDiscrepancies(discrepancies.map { DiscrepancyEntity.fromDomain(it) })
        }
        dao.upsertCompletedInvoice(CompletedInvoiceEntity.fromDomain(completed))
        dao.deleteIncompleteInvoice(completed.invoiceNo)
        dao.insertAuditHistory(AuditHistoryEntity.fromDomain(history))
        if (clearActiveSession) {
            prefs.saveActiveSession(null)
        }
    }

    suspend fun deleteCompletedInvoice(invoiceNo: String) = withContext(ioDispatcher) {
        dao.deleteCompletedInvoice(invoiceNo)
        dao.deleteDiscrepanciesByInvoice(invoiceNo)
        dao.deleteAuditHistoryByInvoice(invoiceNo)
    }

    suspend fun upsertDailySnapshot(snapshot: DailyAuditSnapshot) = withContext(ioDispatcher) {
        dao.upsertDailySnapshot(DailySnapshotEntity.fromDomain(snapshot))
    }

    suspend fun upsertReceivingReport(report: ReceivingReport) = withContext(ioDispatcher) {
        dao.upsertReceivingReport(ReceivingReportEntity.fromDomain(report))
    }

    suspend fun deleteReceivingReport(id: String) = withContext(ioDispatcher) {
        dao.deleteReceivingReport(id)
    }

    suspend fun upsertReturnReport(report: ReturnReport) = withContext(ioDispatcher) {
        dao.upsertReturnReport(ReturnReportEntity.fromDomain(report))
    }

    suspend fun deleteReturnReport(id: String) = withContext(ioDispatcher) {
        dao.deleteReturnReport(id)
    }

    suspend fun upsertInventoryReport(report: InventoryCountReport) = withContext(ioDispatcher) {
        dao.upsertInventoryReport(InventoryReportEntity.fromDomain(report))
    }

    suspend fun deleteInventoryReport(id: String) = withContext(ioDispatcher) {
        dao.deleteInventoryReport(id)
    }

    suspend fun upsertPickingWave(wave: BatchPickingWave) = withContext(ioDispatcher) {
        dao.upsertPickingWave(PickingWaveEntity.fromDomain(wave))
    }

    suspend fun deletePickingWave(id: String) = withContext(ioDispatcher) {
        dao.deletePickingWave(id)
    }

    suspend fun upsertWorker(worker: WarehouseWorker) = withContext(ioDispatcher) {
        dao.upsertWorker(WorkerEntity.fromDomain(worker))
    }

    suspend fun deleteWorker(id: String) = withContext(ioDispatcher) {
        dao.deleteWorker(id)
    }

    suspend fun upsertPackagingRule(rule: PackagingGroupRule, order: Int) = withContext(ioDispatcher) {
        dao.upsertPackagingRules(listOf(PackagingRuleEntity.fromDomain(rule, order)))
    }

    suspend fun resetDefaultPackagingRules() = withContext(ioDispatcher) {
        dao.clearPackagingRules()
        dao.upsertPackagingRules(
            SeedData.defaultPackagingRules.mapIndexed { idx, r -> PackagingRuleEntity.fromDomain(r, idx) }
        )
    }

    suspend fun deleteDiscrepancy(id: Long) = withContext(ioDispatcher) {
        dao.deleteDiscrepancy(id)
    }

    suspend fun clearAllAuditReports() = withContext(ioDispatcher) {
        dao.clearDiscrepancies()
        dao.clearWrongPicks()
    }

    suspend fun importMasterSpreadsheet(uri: Uri, displayName: String): Result<SyncMetadata> = withContext(ioDispatcher) {
        runCatching {
            val parsed = context.contentResolver.openInputStream(uri)?.use { stream ->
                SpreadsheetService.parseMasterFile(stream, displayName)
            } ?: emptyList()
            require(parsed.isNotEmpty()) { "لم يتم العثور على صفوف فواتير صالحة في الملف" }
            dao.replaceMasterItems(parsed.map { MasterItemEntity.fromDomain(it) })
            val meta = SyncMetadata(
                lastSyncDate = java.time.Instant.now().toString(),
                totalInvoices = parsed.map { it.invoiceNo }.distinct().size,
                totalItems = parsed.size,
                fileName = displayName
            )
            prefs.saveSyncMetadata(meta)
            meta
        }
    }

    suspend fun parseReceivingSpreadsheet(uri: Uri, displayName: String): List<ReceivingSessionItem> = withContext(ioDispatcher) {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            SpreadsheetService.parseReceivingPoFile(stream, displayName)
        } ?: emptyList()
    }

    suspend fun parseInventoryBookSpreadsheet(
        uri: Uri,
        displayName: String,
        packagingRules: List<PackagingGroupRule>
    ): Result<List<InventoryCountItem>> = withContext(ioDispatcher) {
        runCatching {
            val parsed = context.contentResolver.openInputStream(uri)?.use { stream ->
                SpreadsheetService.parseInventoryBookFile(stream, displayName, packagingRules)
            } ?: emptyList()
            require(parsed.isNotEmpty()) {
                "لم يتم العثور على أصناف رصيد دفتري صالحة في الملف / No valid book-balance rows found"
            }
            parsed
        }
    }

    suspend fun extractOriginalInvoicePdf(
        uri: Uri,
        displayName: String,
        masterItems: List<MasterInvoiceItem>,
        packagingRules: List<PackagingGroupRule> = emptyList(),
        engine: String = "AUTO"
    ): Result<ExtractedPdfInvoiceResult> = withContext(ioDispatcher) {
        PdfInvoiceOcrService.extractOriginalInvoiceFromPdf(
            context = context,
            uri = uri,
            displayName = displayName,
            masterItems = masterItems,
            packagingRules = packagingRules,
            engine = engine
        )
    }

    suspend fun exportCsvAndShare(
        fileName: String,
        headers: List<String>,
        rows: List<List<String>>
    ): Result<File> = withContext(ioDispatcher) {
        SpreadsheetService.exportCsvAndShare(context, fileName, headers, rows)
    }

    suspend fun exportDiscrepanciesPdf(
        auditorName: String,
        auditorId: String,
        signatureBase64: String?,
        discrepancies: List<AuditDiscrepancy>,
        wrongPicks: List<WrongPickingRecord>,
        completedInvoices: List<CompletedInvoiceRecord>,
        sessionSummary: SessionApprovalSummary? = null,
        isRtl: Boolean = true
    ): Result<File> = withContext(ioDispatcher) {
        PdfService.exportDiscrepancyAuditPdf(
            context = context,
            auditorName = auditorName,
            auditorId = auditorId,
            signatureBase64 = signatureBase64,
            discrepancies = discrepancies,
            wrongPicks = wrongPicks,
            completedInvoices = completedInvoices,
            sessionSummary = sessionSummary,
            isRtl = isRtl
        )
    }

    suspend fun exportReturnPdf(report: ReturnReport): Result<File> = withContext(ioDispatcher) {
        PdfService.exportReturnReportPdf(context, report)
    }
}
