package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Database(
    entities = [
        MasterItemEntity::class,
        DiscrepancyEntity::class,
        WrongPickEntity::class,
        CompletedInvoiceEntity::class,
        IncompleteInvoiceEntity::class,
        AuditHistoryEntity::class,
        DailySnapshotEntity::class,
        ReturnReportEntity::class,
        ReceivingReportEntity::class,
        InventoryReportEntity::class,
        PackagingRuleEntity::class,
        PickingWaveEntity::class,
        WorkerEntity::class,
        AppUserEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    private val seedMutex = Mutex()

    abstract fun wmsDao(): WmsDao

    suspend fun ensureSeeded() = seedMutex.withLock {
        val dao = wmsDao()
        if (dao.getPackagingRuleCount() == 0) {
            dao.upsertPackagingRules(
                SeedData.defaultPackagingRules.mapIndexed { idx, r -> PackagingRuleEntity.fromDomain(r, idx) }
            )
        }
        if (dao.getWorkerCount() == 0) {
            dao.upsertWorkers(SeedData.defaultWorkers.map { WorkerEntity.fromDomain(it) })
        }
        val existingUsers = dao.getAllAppUsers().map { it.toDomain() }
        if (existingUsers.isEmpty()) {
            dao.upsertAppUsers(SeedData.defaultDemoUsers.map { AppUserEntity.fromDomain(it) })
        } else if (existingUsers.any { it.id == "usr-auditor-1" && it.jobId == "AUD-101" }) {
            val migrated = SeedData.defaultDemoUsers
                .filter { it.id == "usr-sup-1" || it.id == "usr-auditor-1" }
                .map { AppUserEntity.fromDomain(it) }
            dao.upsertAppUsers(migrated)
        }
        if (dao.getAllMasterItems().isEmpty()) {
            dao.insertMasterItems(SeedData.defaultDemoMasterItems.map { MasterItemEntity.fromDomain(it) })
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "wms_auditor_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
