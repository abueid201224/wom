package com.example.model

import kotlinx.serialization.Serializable

@Serializable
enum class UserRole {
    GUEST, WAREHOUSE_KEEPER, SUPERVISOR, AUDITOR
}

data class RolePermissionConfig(
    val role: UserRole,
    val labelAr: String,
    val labelEn: String,
    val descriptionAr: String,
    val descriptionEn: String,
    val canAudit: Boolean,
    val canReceive: Boolean,
    val canCount: Boolean,
    val canPick: Boolean,
    val canManageReturns: Boolean,
    val canApproveLab: Boolean,
    val canSignReports: Boolean,
    val canReopenDocuments: Boolean,
    val canExportExcel: Boolean,
    val canManageUsers: Boolean
)

val ROLE_DEFINITIONS: Map<UserRole, RolePermissionConfig> = mapOf(
    UserRole.GUEST to RolePermissionConfig(
        role = UserRole.GUEST,
        labelAr = "مستخدم ضيف",
        labelEn = "Guest User",
        descriptionAr = "استعراض تجريبي، اطلاع على الدليل وقاعدة البيانات (قراءة فقط)",
        descriptionEn = "Demo exploration, read-only guide & database access",
        canAudit = false, canReceive = false, canCount = false, canPick = false,
        canManageReturns = false, canApproveLab = false, canSignReports = false,
        canReopenDocuments = false, canExportExcel = false, canManageUsers = false
    ),
    UserRole.WAREHOUSE_KEEPER to RolePermissionConfig(
        role = UserRole.WAREHOUSE_KEEPER,
        labelAr = "أمين مستودع",
        labelEn = "Warehouse Keeper",
        descriptionAr = "استلام التوريدات، الجرد وتفكيك العبوات، موجات الانتقاء والتجهيز، مسح الباركود",
        descriptionEn = "Receiving, physical cycle count, wave picking & barcode scans",
        canAudit = true, canReceive = true, canCount = true, canPick = true,
        canManageReturns = true, canApproveLab = false, canSignReports = false,
        canReopenDocuments = false, canExportExcel = true, canManageUsers = false
    ),
    UserRole.SUPERVISOR to RolePermissionConfig(
        role = UserRole.SUPERVISOR,
        labelAr = "مشرف مستودع",
        labelEn = "Warehouse Supervisor",
        descriptionAr = "مراجعة الفروقات، فتح المستندات المقفلة، تصدير التقارير، تعيين فرق العمل",
        descriptionEn = "Discrepancy review, reopen closed docs, wave assignments & reports",
        canAudit = true, canReceive = true, canCount = true, canPick = true,
        canManageReturns = true, canApproveLab = true, canSignReports = true,
        canReopenDocuments = true, canExportExcel = true, canManageUsers = true
    ),
    UserRole.AUDITOR to RolePermissionConfig(
        role = UserRole.AUDITOR,
        labelAr = "مراجع / مدقق رقابي",
        labelEn = "Auditor / Quality Inspector",
        descriptionAr = "تدقيق ومطابقة الفواتير، التحكيم المخبري للمرتجعات، التوقيع الرقمي المعتمد، رقابة ISA 500",
        descriptionEn = "Dispatch audit, ISA 500 compliance, lab arbitration & certified signatures",
        canAudit = true, canReceive = true, canCount = true, canPick = true,
        canManageReturns = true, canApproveLab = true, canSignReports = true,
        canReopenDocuments = true, canExportExcel = true, canManageUsers = true
    )
)

@Serializable
data class AppUser(
    val id: String,
    val jobId: String,
    val phone: String? = null,
    val name: String,
    val role: UserRole,
    val pinCode: String,
    val department: String? = null,
    val title: String? = null,
    val signatureText: String? = null,
    val avatarColor: String = "emerald",
    val createdAt: String,
    val lastLoginAt: String? = null
)

@Serializable
data class MasterInvoiceItem(
    val id: Long = 0L,
    val orderNo: String? = null,
    val invoiceNo: String,
    val itemCode: String,
    val itemName: String,
    val unit: String = "PCS",
    val requiredQty: Int,
    val importedAt: String,
    val originalIndex: Int = 0
)

@Serializable
enum class CodeStatus { MATCH, MISMATCH }

@Serializable
enum class QtyStatus { EXACT, SHORTAGE, SURPLUS }

@Serializable
enum class LongBarcodePolicy { ASK, ALLOW, BLOCK }

@Serializable
data class ScannedAuditItem(
    val orderNo: String? = null,
    val itemCode: String,
    val itemName: String,
    val unit: String,
    val requiredQty: Int,
    val actualQty: Int,
    val codeStatus: CodeStatus,
    val qtyStatus: QtyStatus,
    val lastScannedAt: String,
    val scanHistory: List<String> = emptyList(),
    val originalIndex: Int = 0
)

@Serializable
data class ActiveInvoiceSession(
    val orderNo: String? = null,
    val invoiceNo: String,
    val auditorName: String? = null,
    val auditorId: String? = null,
    val auditorSignature: String? = null,
    val startedAt: String,
    val lastActivityAt: String,
    val items: Map<String, ScannedAuditItem> = emptyMap(),
    val isLocked: Boolean = false,
    val lastScannedItemCode: String? = null,
    val longBarcodePolicy: LongBarcodePolicy = LongBarcodePolicy.ASK
) {
    val totalRequiredQty: Int get() = items.values.sumOf { it.requiredQty }
    val totalScannedQty: Int get() = items.values.sumOf { it.actualQty }
    val remainingQty: Int get() = (totalRequiredQty - totalScannedQty).coerceAtLeast(0)
    val exactItemsCount: Int get() = items.values.count { it.qtyStatus == QtyStatus.EXACT }
    val totalItemsCount: Int get() = items.size
    val progressPercent: Int get() {
        if (items.isEmpty() || totalRequiredQty <= 0) return 0
        return ((totalScannedQty.toDouble() / totalRequiredQty) * 100).toInt().coerceIn(0, 100)
    }
    val isFullyCompleted: Boolean get() = items.isNotEmpty() && items.values.all {
        it.qtyStatus == QtyStatus.EXACT && it.codeStatus == CodeStatus.MATCH
    }
}

@Serializable
data class IncompleteInvoiceRecord(
    val invoiceNo: String,
    val orderNo: String? = null,
    val auditorName: String? = null,
    val auditorId: String? = null,
    val savedAt: String,
    val session: ActiveInvoiceSession,
    val completedItemsCount: Int,
    val totalItemsCount: Int,
    val missingQty: Int,
    val scannedQty: Int,
    val totalRequiredQty: Int
)

@Serializable
data class CompletedInvoiceRecord(
    val invoiceNo: String,
    val orderNo: String? = null,
    val auditorName: String? = null,
    val auditorId: String? = null,
    val auditorSignature: String? = null,
    val completedAt: String,
    val totalItems: Int,
    val totalQty: Int
)

@Serializable
data class AuditDiscrepancy(
    val id: Long = 0L,
    val orderNo: String? = null,
    val invoiceNo: String,
    val itemCode: String,
    val itemName: String,
    val unit: String,
    val requiredQty: Int,
    val actualQty: Int,
    val codeStatus: CodeStatus,
    val qtyStatus: QtyStatus,
    val difference: Int,
    val auditedAt: String,
    val auditorName: String? = null,
    val auditorId: String? = null,
    val notes: String? = null
)

@Serializable
data class WrongPickingRecord(
    val id: Long = 0L,
    val orderNo: String? = null,
    val activeInvoiceNo: String,
    val itemCode: String,
    val itemName: String? = null,
    val unit: String? = null,
    val actualBelongingInvoiceNo: String? = null,
    val actualBelongingOrderNo: String? = null,
    val scannedAt: String,
    val auditorName: String? = null,
    val auditorId: String? = null,
    val quantity: Int = 1,
    val notes: String? = null
)

@Serializable
data class InvoiceAuditHistory(
    val id: Long = 0L,
    val orderNo: String? = null,
    val invoiceNo: String,
    val auditorName: String? = null,
    val auditorId: String? = null,
    val totalRequiredItems: Int,
    val totalRequiredQty: Int = 0,
    val totalScannedQty: Int = 0,
    val scannedItemsCount: Int,
    val exactItemsCount: Int,
    val discrepancyCount: Int,
    val status: String,
    val completedAt: String,
    val durationSeconds: Int = 0
)

@Serializable
data class DailyAuditSnapshot(
    val id: String,
    val date: String,
    val closedAt: String,
    val totalInvoices: Int,
    val completedInvoices: Int,
    val totalItems: Int,
    val totalRequiredQty: Int,
    val totalScannedQty: Int,
    val totalDiscrepancies: Int,
    val totalWrongPickings: Int,
    val auditorName: String? = null,
    val auditorId: String? = null,
    val notes: String? = null
)

@Serializable
data class SyncMetadata(
    val lastSyncDate: String? = null,
    val totalInvoices: Int = 0,
    val totalItems: Int = 0,
    val fileName: String? = null
)

@Serializable
enum class GroupDifficultyLevel { HIGH_EXPERT, MEDIUM_INTERMEDIATE, LOW_NOVICE }

@Serializable
enum class WorkerExperienceLevel { EXPERT, INTERMEDIATE, NOVICE }

@Serializable
data class PackagingGroupRule(
    val id: String,
    val name: String,
    val startBarcode: String,
    val endBarcode: String,
    val category: String? = null,
    val cartonFactor: Double,
    val packFactor: Double,
    val unitName: String,
    val systemUnit: String? = null,
    val baseUnit: String? = null,
    val cartonSizeSystemUnit: String? = null,
    val cartonSizeBaseUnit: String? = null,
    val packSizeDesc: String? = null,
    val boxesPerCarton: Int? = null,
    val conversionEquation: String? = null,
    val cartonEquation: String? = null,
    val difficultyLevel: GroupDifficultyLevel = GroupDifficultyLevel.MEDIUM_INTERMEDIATE,
    val assignedRoleDesc: String? = null,
    val isActive: Boolean = true,
    val notes: String? = null,
    val createdAt: String = ""
)

@Serializable
enum class ReturnItemCondition { VALID_FOR_RESTOCK, TRANSFERRED_TO_LAB, INTACT, DAMAGED }

@Serializable
enum class LabDecision { PENDING, APPROVED_FOR_RESTOCK, REJECTED_SCRAP }

@Serializable
enum class ReturnReportStatus { COMPLETED, PENDING_LAB, DRAFT, CANCELLED }

@Serializable
data class ReturnSessionItem(
    val id: String,
    val itemCode: String,
    val itemName: String,
    val unit: String = "PCS",
    val invoiceNo: String? = null,
    val sourceFile: String? = null,
    val invoicedQty: Int = 0,
    val subtotal: Double = 0.0,
    val actualReturnedQty: Int = 0,
    val scannedQty: Int = 0,
    val unitPrice: Double = 0.0,
    val refundTotal: Double = 0.0,
    val condition: ReturnItemCondition = ReturnItemCondition.VALID_FOR_RESTOCK,
    val inspectionDecision: String = "WAREHOUSE",
    val size: String? = null,
    val color: String? = null,
    val packagingCondition: String? = null,
    val reasonText: String? = null,
    val inspectorName: String? = null,
    val labDecision: LabDecision = LabDecision.PENDING,
    val labNotes: String? = null,
    val notes: String? = null,
    val isIncludedInRefund: Boolean = true
)

@Serializable
data class ReturnReport(
    val id: String,
    val returnReceiptNo: String,
    val rmaNo: String? = null,
    val originalInvoiceNo: String,
    val orderNo: String? = null,
    val customerName: String = "",
    val paymentMethod: String = "COD",
    val createdAt: String,
    val auditorName: String = "",
    val auditorId: String = "",
    val auditorSignature: String? = null,
    val status: ReturnReportStatus = ReturnReportStatus.COMPLETED,
    val items: List<ReturnSessionItem> = emptyList(),
    val totalInvoicedQty: Int = 0,
    val totalReturnedQty: Int = 0,
    val totalValidForRestockQty: Int = 0,
    val totalTransferredToLabQty: Int = 0,
    val totalRefundAmount: Double = 0.0,
    val labName: String? = null,
    val labTestType: String? = null,
    val labTransferDate: String? = null,
    val labNotes: String? = null,
    val labResolvedAt: String? = null,
    val labAuditorName: String? = null,
    val isOverdueForLab: Boolean = false,
    val notes: String? = null
)

@Serializable
data class ReceivingSessionItem(
    val id: String,
    val itemCode: String,
    val itemName: String,
    val unit: String = "PCS",
    val expectedQty: Int = 0,
    val receivedQty: Int = 0,
    val damagedQty: Int = 0,
    val cartonFactor: Int = 24,
    val packFactor: Int = 6,
    val cartonsCount: Int = 0,
    val packsCount: Int = 0,
    val piecesCount: Int = 0,
    val batchNumber: String? = null,
    val expiryDate: String? = null,
    val status: String = "EXACT",
    val notes: String? = null
)

@Serializable
data class ReceivingReport(
    val id: String,
    val poNumber: String,
    val supplierName: String,
    val deliveryNoteNo: String? = null,
    val createdAt: String,
    val auditorName: String,
    val auditorId: String,
    val auditorSignature: String? = null,
    val status: String = "ACCEPTED_FULL",
    val items: List<ReceivingSessionItem> = emptyList(),
    val totalExpectedQty: Int = 0,
    val totalReceivedQty: Int = 0,
    val totalDamagedQty: Int = 0,
    val notes: String? = null
)

@Serializable
data class InventoryCountItem(
    val id: String,
    val itemCode: String,
    val itemName: String,
    val unit: String = "PCS",
    val groupId: String? = null,
    val groupName: String? = null,
    val bookQty: Int = 0,
    val cartonsCount: Int = 0,
    val cartonFactor: Int = 24,
    val packsCount: Int = 0,
    val packFactor: Int = 6,
    val piecesCount: Int = 0,
    val calculatedActualQty: Int = 0,
    val varianceQty: Int = 0,
    val status: String = "EXACT",
    val lastScannedAt: String? = null,
    val notes: String? = null
)

@Serializable
data class InventoryCountReport(
    val id: String,
    val title: String,
    val sectionOrAisle: String? = null,
    val createdAt: String,
    val auditorName: String,
    val auditorId: String,
    val auditorSignature: String? = null,
    val items: List<InventoryCountItem> = emptyList(),
    val totalBookQty: Int = 0,
    val totalActualQty: Int = 0,
    val totalVarianceQty: Int = 0,
    val notes: String? = null
)

@Serializable
data class WarehouseWorker(
    val id: String,
    val name: String,
    val code: String,
    val experienceLevel: WorkerExperienceLevel,
    val isActive: Boolean = true,
    val phone: String? = null,
    val specialty: String? = null,
    val createdAt: String = ""
)

@Serializable
data class InvoiceItemSource(
    val invoiceNo: String,
    val orderNo: String? = null,
    val customerName: String? = null,
    val qty: Int
)

@Serializable
data class AggregatedPickingItem(
    val id: String,
    val itemCode: String,
    val itemName: String,
    val unit: String = "PCS",
    val systemUnit: String? = null,
    val baseUnit: String? = null,
    val groupId: String,
    val groupName: String,
    val totalRequiredQty: Int,
    val pickedQty: Int = 0,
    val cartonFactor: Int = 24,
    val packFactor: Int = 6,
    val cartonsCount: Int = 0,
    val packsCount: Int = 0,
    val piecesCount: Int = 0,
    val boxesPerCarton: Int? = null,
    val cartonEquation: String? = null,
    val conversionEquation: String? = null,
    val packSizeDesc: String? = null,
    val cartonSizeDesc: String? = null,
    val invoiceSources: List<InvoiceItemSource> = emptyList(),
    val status: String = "PENDING",
    val location: String? = null,
    val notes: String? = null
)

@Serializable
data class PickingProductGroup(
    val groupId: String,
    val groupName: String,
    val difficulty: GroupDifficultyLevel = GroupDifficultyLevel.MEDIUM_INTERMEDIATE,
    val assignedWorkerId: String? = null,
    val assignedWorkerName: String? = null,
    val assignedWorkerLevel: WorkerExperienceLevel? = null,
    val cartonEquation: String? = null,
    val conversionEquation: String? = null,
    val systemUnit: String? = null,
    val baseUnit: String? = null,
    val items: List<AggregatedPickingItem> = emptyList(),
    val totalQty: Int = 0,
    val totalCartons: Int = 0,
    val totalPacks: Int = 0,
    val totalPieces: Int = 0,
    val invoicesCount: Int = 0,
    val status: String = "PENDING"
)

@Serializable
data class BatchPickingWave(
    val id: String,
    val waveNo: String,
    val title: String,
    val createdAt: String,
    val createdBy: String,
    val totalInvoicesCount: Int,
    val invoiceNumbers: List<String> = emptyList(),
    val totalItemsCount: Int,
    val totalQuantity: Int,
    val totalCartons: Int,
    val totalPacks: Int,
    val totalPieces: Int,
    val groups: List<PickingProductGroup> = emptyList(),
    val status: String = "ASSIGNED",
    val phase1Completed: Boolean = false,
    val preparedInvoices: List<String> = emptyList(),
    val notes: String? = null
)

@Serializable
enum class ItemSortMode { LAST_SCANNED, ORIGINAL_ORDER, PENDING_FIRST, ERRORS_FIRST }

@Serializable
data class AppSettings(
    val language: String = "ar",
    val soundEnabled: Boolean = true,
    val soundVolume: Float = 0.8f,
    val vibrationEnabled: Boolean = true,
    val scannerPrefixInvoice: String = "INV-",
    val scannerMinLength: Int = 3,
    val autoSwitchOnNewInvoice: Boolean = true,
    val itemSortMode: ItemSortMode = ItemSortMode.LAST_SCANNED,
    val enableCameraQr: Boolean = true,
    val longBarcodeThreshold: Int = 10,
    val auditorName: String = "أحمد حمادة",
    val auditorId: String = "AUD-101",
    val auditorTitle: String = "مدير ومراقب عمليات المستودع",
    val auditorSignatureBase64: String? = null
)

enum class ActiveTargetColumn { CARTONS, PACKS, PIECES, DIRECT_QTY }

@Serializable
data class ExtractedPdfInvoiceItem(
    val id: String,
    val itemCode: String,
    val itemName: String,
    val unit: String = "PCS",
    val invoicedQty: Int = 1,
    val unitPrice: Double = 0.0,
    val lineTotal: Double = 0.0,
    val isSelected: Boolean = true,
    val returnQty: Int = 1,
    val condition: ReturnItemCondition = ReturnItemCondition.VALID_FOR_RESTOCK
)

@Serializable
data class ExtractedPdfInvoiceResult(
    val invoiceNo: String = "",
    val orderNo: String? = null,
    val customerName: String = "",
    val currency: String = "SAR",
    val sourceLabel: String = "",
    val extractionEngine: String = "GEMINI_RTL_OCR",
    val items: List<ExtractedPdfInvoiceItem> = emptyList(),
    val notes: String? = null
)

