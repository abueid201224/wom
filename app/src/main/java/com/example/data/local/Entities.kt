package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val wmsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Entity(
    tableName = "master_items",
    indices = [
        androidx.room.Index(value = ["invoiceNo"]),
        androidx.room.Index(value = ["itemCode"]),
        androidx.room.Index(value = ["invoiceNo", "itemCode"])
    ]
)
data class MasterItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val orderNo: String?,
    val invoiceNo: String,
    val itemCode: String,
    val itemName: String,
    val unit: String,
    val requiredQty: Int,
    val importedAt: String,
    val originalIndex: Int
) {
    fun toDomain() = MasterInvoiceItem(id, orderNo, invoiceNo, itemCode, itemName, unit, requiredQty, importedAt, originalIndex)
    companion object {
        fun fromDomain(m: MasterInvoiceItem) = MasterItemEntity(0L, m.orderNo, m.invoiceNo, m.itemCode, m.itemName, m.unit, m.requiredQty, m.importedAt, m.originalIndex)
    }
}

@Entity(
    tableName = "discrepancies",
    indices = [
        androidx.room.Index(value = ["invoiceNo"]),
        androidx.room.Index(value = ["itemCode"])
    ]
)
data class DiscrepancyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val orderNo: String?,
    val invoiceNo: String,
    val itemCode: String,
    val itemName: String,
    val unit: String,
    val requiredQty: Int,
    val actualQty: Int,
    val codeStatus: String,
    val qtyStatus: String,
    val difference: Int,
    val auditedAt: String,
    val auditorName: String?,
    val auditorId: String?,
    val notes: String?
) {
    fun toDomain() = AuditDiscrepancy(
        id = id, orderNo = orderNo, invoiceNo = invoiceNo, itemCode = itemCode, itemName = itemName,
        unit = unit, requiredQty = requiredQty, actualQty = actualQty,
        codeStatus = runCatching { CodeStatus.valueOf(codeStatus) }.getOrDefault(CodeStatus.MATCH),
        qtyStatus = runCatching { QtyStatus.valueOf(qtyStatus) }.getOrDefault(QtyStatus.SHORTAGE),
        difference = difference, auditedAt = auditedAt, auditorName = auditorName, auditorId = auditorId, notes = notes
    )
    companion object {
        fun fromDomain(d: AuditDiscrepancy) = DiscrepancyEntity(
            id = d.id, orderNo = d.orderNo, invoiceNo = d.invoiceNo, itemCode = d.itemCode, itemName = d.itemName,
            unit = d.unit, requiredQty = d.requiredQty, actualQty = d.actualQty,
            codeStatus = d.codeStatus.name, qtyStatus = d.qtyStatus.name,
            difference = d.difference, auditedAt = d.auditedAt, auditorName = d.auditorName, auditorId = d.auditorId, notes = d.notes
        )
    }
}

@Entity(
    tableName = "wrong_picks",
    indices = [
        androidx.room.Index(value = ["activeInvoiceNo"]),
        androidx.room.Index(value = ["itemCode"])
    ]
)
data class WrongPickEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val orderNo: String?,
    val activeInvoiceNo: String,
    val itemCode: String,
    val itemName: String?,
    val unit: String?,
    val actualBelongingInvoiceNo: String?,
    val actualBelongingOrderNo: String?,
    val scannedAt: String,
    val auditorName: String?,
    val auditorId: String?,
    val quantity: Int,
    val notes: String?
) {
    fun toDomain() = WrongPickingRecord(
        id, orderNo, activeInvoiceNo, itemCode, itemName, unit,
        actualBelongingInvoiceNo, actualBelongingOrderNo, scannedAt, auditorName, auditorId, quantity, notes
    )
    companion object {
        fun fromDomain(w: WrongPickingRecord) = WrongPickEntity(
            w.id, w.orderNo, w.activeInvoiceNo, w.itemCode, w.itemName, w.unit,
            w.actualBelongingInvoiceNo, w.actualBelongingOrderNo, w.scannedAt, w.auditorName, w.auditorId, w.quantity, w.notes
        )
    }
}

@Entity(tableName = "completed_invoices")
data class CompletedInvoiceEntity(
    @PrimaryKey val invoiceNo: String,
    val orderNo: String?,
    val auditorName: String?,
    val auditorId: String?,
    val auditorSignature: String?,
    val completedAt: String,
    val totalItems: Int,
    val totalQty: Int
) {
    fun toDomain() = CompletedInvoiceRecord(invoiceNo, orderNo, auditorName, auditorId, auditorSignature, completedAt, totalItems, totalQty)
    companion object {
        fun fromDomain(c: CompletedInvoiceRecord) = CompletedInvoiceEntity(
            c.invoiceNo, c.orderNo, c.auditorName, c.auditorId, c.auditorSignature, c.completedAt, c.totalItems, c.totalQty
        )
    }
}

@Entity(tableName = "incomplete_invoices")
data class IncompleteInvoiceEntity(
    @PrimaryKey val invoiceNo: String,
    val payloadJson: String,
    val savedAt: String
) {
    fun toDomain(): IncompleteInvoiceRecord = wmsJson.decodeFromString(payloadJson)
    companion object {
        fun fromDomain(r: IncompleteInvoiceRecord) = IncompleteInvoiceEntity(
            r.invoiceNo, wmsJson.encodeToString(r), r.savedAt
        )
    }
}

@Entity(
    tableName = "audit_history",
    indices = [androidx.room.Index(value = ["invoiceNo"])]
)
data class AuditHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val orderNo: String?,
    val invoiceNo: String,
    val auditorName: String?,
    val auditorId: String?,
    val totalRequiredItems: Int,
    val totalRequiredQty: Int,
    val totalScannedQty: Int,
    val scannedItemsCount: Int,
    val exactItemsCount: Int,
    val discrepancyCount: Int,
    val status: String,
    val completedAt: String,
    val durationSeconds: Int
) {
    fun toDomain() = InvoiceAuditHistory(
        id, orderNo, invoiceNo, auditorName, auditorId, totalRequiredItems, totalRequiredQty,
        totalScannedQty, scannedItemsCount, exactItemsCount, discrepancyCount, status, completedAt, durationSeconds
    )
    companion object {
        fun fromDomain(h: InvoiceAuditHistory) = AuditHistoryEntity(
            h.id, h.orderNo, h.invoiceNo, h.auditorName, h.auditorId, h.totalRequiredItems, h.totalRequiredQty,
            h.totalScannedQty, h.scannedItemsCount, h.exactItemsCount, h.discrepancyCount, h.status, h.completedAt, h.durationSeconds
        )
    }
}

@Entity(tableName = "daily_snapshots")
data class DailySnapshotEntity(
    @PrimaryKey val id: String,
    val payloadJson: String,
    val closedAt: String
) {
    fun toDomain(): DailyAuditSnapshot = wmsJson.decodeFromString(payloadJson)
    companion object {
        fun fromDomain(d: DailyAuditSnapshot) = DailySnapshotEntity(d.id, wmsJson.encodeToString(d), d.closedAt)
    }
}

@Entity(tableName = "return_reports")
data class ReturnReportEntity(
    @PrimaryKey val id: String,
    val payloadJson: String,
    val createdAt: String
) {
    fun toDomain(): ReturnReport = wmsJson.decodeFromString(payloadJson)
    companion object {
        fun fromDomain(r: ReturnReport) = ReturnReportEntity(r.id, wmsJson.encodeToString(r), r.createdAt)
    }
}

@Entity(tableName = "receiving_reports")
data class ReceivingReportEntity(
    @PrimaryKey val id: String,
    val payloadJson: String,
    val createdAt: String
) {
    fun toDomain(): ReceivingReport = wmsJson.decodeFromString(payloadJson)
    companion object {
        fun fromDomain(r: ReceivingReport) = ReceivingReportEntity(r.id, wmsJson.encodeToString(r), r.createdAt)
    }
}

@Entity(tableName = "inventory_reports")
data class InventoryReportEntity(
    @PrimaryKey val id: String,
    val payloadJson: String,
    val createdAt: String
) {
    fun toDomain(): InventoryCountReport = wmsJson.decodeFromString(payloadJson)
    companion object {
        fun fromDomain(r: InventoryCountReport) = InventoryReportEntity(r.id, wmsJson.encodeToString(r), r.createdAt)
    }
}

@Entity(tableName = "packaging_rules")
data class PackagingRuleEntity(
    @PrimaryKey val id: String,
    val payloadJson: String,
    val sortOrder: Int
) {
    fun toDomain(): PackagingGroupRule = wmsJson.decodeFromString(payloadJson)
    companion object {
        fun fromDomain(r: PackagingGroupRule, order: Int = 0) = PackagingRuleEntity(r.id, wmsJson.encodeToString(r), order)
    }
}

@Entity(tableName = "picking_waves")
data class PickingWaveEntity(
    @PrimaryKey val id: String,
    val payloadJson: String,
    val createdAt: String
) {
    fun toDomain(): BatchPickingWave = wmsJson.decodeFromString(payloadJson)
    companion object {
        fun fromDomain(w: BatchPickingWave) = PickingWaveEntity(w.id, wmsJson.encodeToString(w), w.createdAt)
    }
}

@Entity(tableName = "workers")
data class WorkerEntity(
    @PrimaryKey val id: String,
    val payloadJson: String
) {
    fun toDomain(): WarehouseWorker = wmsJson.decodeFromString(payloadJson)
    companion object {
        fun fromDomain(w: WarehouseWorker) = WorkerEntity(w.id, wmsJson.encodeToString(w))
    }
}

@Entity(tableName = "app_users")
data class AppUserEntity(
    @PrimaryKey val id: String,
    val payloadJson: String
) {
    fun toDomain(): AppUser = wmsJson.decodeFromString(payloadJson)
    companion object {
        fun fromDomain(u: AppUser) = AppUserEntity(u.id, wmsJson.encodeToString(u))
    }
}
