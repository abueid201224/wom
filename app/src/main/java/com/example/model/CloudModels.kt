package com.example.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class CloudUserSettings(
    @DocumentId val id: String = "",
    val userId: String = "",
    val language: String = "ar",
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val longBarcodeThreshold: Long = 10L,
    val auditorName: String = "",
    val auditorId: String = "",
    val auditorTitle: String = "",
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CloudReturnReport(
    @DocumentId val id: String = "",
    val userId: String = "",
    val returnReceiptNo: String = "",
    val originalInvoiceNo: String = "",
    val orderNo: String = "",
    val customerName: String = "",
    val paymentMethod: String = "COD",
    val status: String = "COMPLETED_WAREHOUSE",
    val totalRefundAmount: Double = 0.0,
    val totalItems: Long = 0L,
    val auditorName: String = "",
    val itemsJson: String = "[]",
    val notes: String = "",
    val createdAtIso: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CloudAuditDiscrepancy(
    @DocumentId val id: String = "",
    val userId: String = "",
    val invoiceNo: String = "",
    val orderNo: String = "",
    val itemCode: String = "",
    val itemName: String = "",
    val unit: String = "PCS",
    val requiredQty: Long = 0L,
    val actualQty: Long = 0L,
    val difference: Long = 0L,
    val codeStatus: String = "MATCH",
    val qtyStatus: String = "SHORTAGE",
    val auditorId: String = "",
    val auditedAtIso: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CloudReceivingReport(
    @DocumentId val id: String = "",
    val userId: String = "",
    val poNumber: String = "",
    val supplierName: String = "",
    val status: String = "LOCKED",
    val totalItems: Long = 0L,
    val totalReceivedQty: Long = 0L,
    val itemsJson: String = "[]",
    val receivedAtIso: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CloudInventoryReport(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val totalItems: Long = 0L,
    val totalCountedPieces: Long = 0L,
    val itemsJson: String = "[]",
    val countedAtIso: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CloudPickingWave(
    @DocumentId val id: String = "",
    val userId: String = "",
    val waveNo: String = "",
    val status: String = "OPEN",
    val totalInvoices: Long = 0L,
    val totalItems: Long = 0L,
    val waveJson: String = "{}",
    val createdAtIso: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
