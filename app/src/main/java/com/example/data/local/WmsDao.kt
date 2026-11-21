package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WmsDao {
    @Query("SELECT * FROM master_items ORDER BY originalIndex ASC")
    fun observeMasterItems(): Flow<List<MasterItemEntity>>

    @Query("SELECT * FROM master_items ORDER BY originalIndex ASC")
    suspend fun getAllMasterItems(): List<MasterItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMasterItems(items: List<MasterItemEntity>)

    @Query("DELETE FROM master_items")
    suspend fun clearMasterItems()

    @Transaction
    suspend fun replaceMasterItems(items: List<MasterItemEntity>) {
        clearMasterItems()
        insertMasterItems(items)
    }

    @Query("SELECT * FROM discrepancies ORDER BY id DESC")
    fun observeDiscrepancies(): Flow<List<DiscrepancyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDiscrepancies(items: List<DiscrepancyEntity>)

    @Query("DELETE FROM discrepancies WHERE id = :id")
    suspend fun deleteDiscrepancy(id: Long)

    @Query("DELETE FROM discrepancies WHERE invoiceNo = :invoiceNo")
    suspend fun deleteDiscrepanciesByInvoice(invoiceNo: String)

    @Query("DELETE FROM discrepancies")
    suspend fun clearDiscrepancies()

    @Query("SELECT * FROM wrong_picks ORDER BY id DESC")
    fun observeWrongPicks(): Flow<List<WrongPickEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWrongPick(item: WrongPickEntity)

    @Query("DELETE FROM wrong_picks")
    suspend fun clearWrongPicks()

    @Query("SELECT * FROM completed_invoices ORDER BY completedAt DESC")
    fun observeCompletedInvoices(): Flow<List<CompletedInvoiceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCompletedInvoice(item: CompletedInvoiceEntity)

    @Query("DELETE FROM completed_invoices WHERE invoiceNo = :invoiceNo")
    suspend fun deleteCompletedInvoice(invoiceNo: String)

    @Query("DELETE FROM completed_invoices")
    suspend fun clearCompletedInvoices()

    @Query("SELECT * FROM incomplete_invoices ORDER BY savedAt DESC")
    fun observeIncompleteInvoices(): Flow<List<IncompleteInvoiceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIncompleteInvoice(item: IncompleteInvoiceEntity)

    @Query("DELETE FROM incomplete_invoices WHERE invoiceNo = :invoiceNo")
    suspend fun deleteIncompleteInvoice(invoiceNo: String)

    @Query("DELETE FROM incomplete_invoices")
    suspend fun clearIncompleteInvoices()

    @Query("SELECT * FROM audit_history ORDER BY id DESC")
    fun observeAuditHistory(): Flow<List<AuditHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditHistory(item: AuditHistoryEntity)

    @Query("DELETE FROM audit_history WHERE invoiceNo = :invoiceNo")
    suspend fun deleteAuditHistoryByInvoice(invoiceNo: String)

    @Query("SELECT * FROM daily_snapshots ORDER BY closedAt DESC")
    fun observeDailySnapshots(): Flow<List<DailySnapshotEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailySnapshot(item: DailySnapshotEntity)

    @Query("SELECT * FROM return_reports ORDER BY createdAt DESC")
    fun observeReturnReports(): Flow<List<ReturnReportEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReturnReport(item: ReturnReportEntity)

    @Query("DELETE FROM return_reports WHERE id = :id")
    suspend fun deleteReturnReport(id: String)

    @Query("SELECT * FROM receiving_reports ORDER BY createdAt DESC")
    fun observeReceivingReports(): Flow<List<ReceivingReportEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReceivingReport(item: ReceivingReportEntity)

    @Query("DELETE FROM receiving_reports WHERE id = :id")
    suspend fun deleteReceivingReport(id: String)

    @Query("SELECT * FROM inventory_reports ORDER BY createdAt DESC")
    fun observeInventoryReports(): Flow<List<InventoryReportEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInventoryReport(item: InventoryReportEntity)

    @Query("DELETE FROM inventory_reports WHERE id = :id")
    suspend fun deleteInventoryReport(id: String)

    @Query("SELECT * FROM packaging_rules ORDER BY sortOrder ASC")
    fun observePackagingRules(): Flow<List<PackagingRuleEntity>>

    @Query("SELECT COUNT(*) FROM packaging_rules")
    suspend fun getPackagingRuleCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPackagingRules(rules: List<PackagingRuleEntity>)

    @Query("DELETE FROM packaging_rules")
    suspend fun clearPackagingRules()

    @Query("SELECT * FROM picking_waves ORDER BY createdAt DESC")
    fun observePickingWaves(): Flow<List<PickingWaveEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPickingWave(wave: PickingWaveEntity)

    @Query("DELETE FROM picking_waves WHERE id = :id")
    suspend fun deletePickingWave(id: String)

    @Query("SELECT * FROM workers")
    fun observeWorkers(): Flow<List<WorkerEntity>>

    @Query("SELECT COUNT(*) FROM workers")
    suspend fun getWorkerCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkers(workers: List<WorkerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorker(worker: WorkerEntity)

    @Query("DELETE FROM workers WHERE id = :id")
    suspend fun deleteWorker(id: String)

    @Query("SELECT * FROM app_users")
    fun observeAppUsers(): Flow<List<AppUserEntity>>

    @Query("SELECT COUNT(*) FROM app_users")
    suspend fun getAppUserCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppUsers(users: List<AppUserEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppUser(user: AppUserEntity)
}
