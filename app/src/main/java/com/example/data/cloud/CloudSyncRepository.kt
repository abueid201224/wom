package com.example.data.cloud

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.local.wmsJson
import com.example.model.*
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString

enum class OperationType { CREATE, UPDATE, DELETE, LIST, GET, WRITE }

fun handleFirestoreError(
    error: Throwable,
    operationType: OperationType,
    path: String,
    auth: FirebaseAuth = Firebase.auth
) {
    val user = auth.currentUser
    val providers = user?.providerData?.joinToString(",") { "${it.providerId}:${it.email.orEmpty()}" }.orEmpty()
    Log.e(
        "FirestoreError",
        """{"error":"${error.message?.replace("\"", "'")}","operationType":"$operationType","path":"$path","uid":"${user?.uid}","providers":"$providers"}""",
        error
    )
}

class CloudSyncRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun sanitizeDocId(raw: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        return cleaned.take(120).ifBlank { "doc_${System.currentTimeMillis()}" }
    }

    private suspend fun upsertPreservingCreatedAt(
        docRef: DocumentReference,
        createDto: Any,
        updateFields: Map<String, Any>
    ) {
        val snap = docRef.get().await()
        if (snap.exists() && snap.getTimestamp("createdAt") != null) {
            docRef.update(updateFields + ("updatedAt" to FieldValue.serverTimestamp())).await()
        } else {
            docRef.set(createDto).await()
        }
    }

    suspend fun syncSettings(settings: AppSettings): Result<Unit> {
        var path = "users/unknown/settings/current"
        return try {
            val uid = requireUserId()
            path = "users/$uid/settings/current"
            val doc = CloudUserSettings(
                userId = uid,
                language = if (settings.language == "en") "en" else "ar",
                soundEnabled = settings.soundEnabled,
                vibrationEnabled = settings.vibrationEnabled,
                longBarcodeThreshold = settings.longBarcodeThreshold.coerceIn(1, 64).toLong(),
                auditorName = settings.auditorName.take(128).ifBlank { "Auditor" },
                auditorId = settings.auditorId.take(64).ifBlank { "AUD-101" },
                auditorTitle = settings.auditorTitle.take(128),
                updatedAt = null
            )
            db.collection("users").document(uid)
                .collection("settings").document("current")
                .set(doc)
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
            Result.failure(e)
        }
    }

    suspend fun backupAllToCloud(
        settings: AppSettings,
        returns: List<ReturnReport>,
        discrepancies: List<AuditDiscrepancy>,
        receiving: List<ReceivingReport>,
        inventory: List<InventoryCountReport>,
        waves: List<BatchPickingWave>
    ): Result<Int> {
        var path = "users/unknown"
        return try {
            val uid = requireUserId()
            path = "users/$uid"
            syncSettings(settings).getOrThrow()
            var count = 0

            for (ret in returns) {
                val docId = sanitizeDocId(ret.id)
                val cloudStatus = when (ret.status) {
                    ReturnReportStatus.PENDING_LAB -> "PENDING_LAB"
                    ReturnReportStatus.DRAFT -> "DRAFT"
                    else -> "COMPLETED_WAREHOUSE"
                }
                val receiptNo = ret.returnReceiptNo.take(128).ifBlank { docId }
                val origInv = ret.originalInvoiceNo.take(128)
                val ordNo = ret.orderNo.orEmpty().take(128)
                val cust = ret.customerName.take(256)
                val pay = ret.paymentMethod.take(64)
                val refundAmt = ret.totalRefundAmount.coerceAtLeast(0.0)
                val totalCnt = ret.items.size.toLong().coerceAtLeast(0L)
                val audName = ret.auditorName.take(128)
                val itemsStr = wmsJson.encodeToString(ret.items).take(499000)
                val notesStr = ret.notes.orEmpty().take(1024)
                val createdIso = ret.createdAt.take(64).ifBlank { "2026-01-01T00:00:00Z" }

                val dto = CloudReturnReport(
                    userId = uid,
                    returnReceiptNo = receiptNo,
                    originalInvoiceNo = origInv,
                    orderNo = ordNo,
                    customerName = cust,
                    paymentMethod = pay,
                    status = cloudStatus,
                    totalRefundAmount = refundAmt,
                    totalItems = totalCnt,
                    auditorName = audName,
                    itemsJson = itemsStr,
                    notes = notesStr,
                    createdAtIso = createdIso,
                    createdAt = null,
                    updatedAt = null
                )
                val docRef = db.collection("users").document(uid).collection("returns").document(docId)
                upsertPreservingCreatedAt(
                    docRef = docRef,
                    createDto = dto,
                    updateFields = mapOf(
                        "userId" to uid,
                        "returnReceiptNo" to receiptNo,
                        "originalInvoiceNo" to origInv,
                        "orderNo" to ordNo,
                        "customerName" to cust,
                        "paymentMethod" to pay,
                        "status" to cloudStatus,
                        "totalRefundAmount" to refundAmt,
                        "totalItems" to totalCnt,
                        "auditorName" to audName,
                        "itemsJson" to itemsStr,
                        "notes" to notesStr,
                        "createdAtIso" to createdIso
                    )
                )
                count++
            }

            for (disc in discrepancies.take(100)) {
                val docId = sanitizeDocId("disc_${disc.invoiceNo}_${disc.itemCode}_${disc.id}")
                val invNo = disc.invoiceNo.take(128).ifBlank { "UNKNOWN" }
                val ordNo = disc.orderNo.orEmpty().take(128)
                val code = disc.itemCode.take(128).ifBlank { "UNKNOWN" }
                val name = disc.itemName.take(256)
                val unit = disc.unit.take(32).ifBlank { "PCS" }
                val reqQty = disc.requiredQty.toLong().coerceAtLeast(0L)
                val actQty = disc.actualQty.toLong().coerceAtLeast(0L)
                val diff = disc.difference.toLong()
                val audId = disc.auditorId.orEmpty().take(64)
                val audIso = disc.auditedAt.take(64).ifBlank { "2026-01-01T00:00:00Z" }

                val dto = CloudAuditDiscrepancy(
                    userId = uid,
                    invoiceNo = invNo,
                    orderNo = ordNo,
                    itemCode = code,
                    itemName = name,
                    unit = unit,
                    requiredQty = reqQty,
                    actualQty = actQty,
                    difference = diff,
                    codeStatus = disc.codeStatus.name,
                    qtyStatus = disc.qtyStatus.name,
                    auditorId = audId,
                    auditedAtIso = audIso,
                    createdAt = null,
                    updatedAt = null
                )
                val docRef = db.collection("users").document(uid).collection("audit_discrepancies").document(docId)
                upsertPreservingCreatedAt(
                    docRef = docRef,
                    createDto = dto,
                    updateFields = mapOf(
                        "userId" to uid,
                        "invoiceNo" to invNo,
                        "orderNo" to ordNo,
                        "itemCode" to code,
                        "itemName" to name,
                        "unit" to unit,
                        "requiredQty" to reqQty,
                        "actualQty" to actQty,
                        "difference" to diff,
                        "codeStatus" to disc.codeStatus.name,
                        "qtyStatus" to disc.qtyStatus.name,
                        "auditorId" to audId,
                        "auditedAtIso" to audIso
                    )
                )
                count++
            }

            for (rec in receiving) {
                val docId = sanitizeDocId(rec.id)
                val po = rec.poNumber.take(128).ifBlank { docId }
                val supplier = rec.supplierName.take(256)
                val cloudRecStatus = if (rec.status == "DRAFT") "DRAFT" else "LOCKED"
                val totalCnt = rec.items.size.toLong().coerceAtLeast(0L)
                val recQty = rec.totalReceivedQty.toLong().coerceAtLeast(0L)
                val itemsStr = wmsJson.encodeToString(rec.items).take(499000)
                val recIso = rec.createdAt.take(64).ifBlank { "2026-01-01T00:00:00Z" }

                val dto = CloudReceivingReport(
                    userId = uid,
                    poNumber = po,
                    supplierName = supplier,
                    status = cloudRecStatus,
                    totalItems = totalCnt,
                    totalReceivedQty = recQty,
                    itemsJson = itemsStr,
                    receivedAtIso = recIso,
                    createdAt = null,
                    updatedAt = null
                )
                val docRef = db.collection("users").document(uid).collection("receiving_reports").document(docId)
                upsertPreservingCreatedAt(
                    docRef = docRef,
                    createDto = dto,
                    updateFields = mapOf(
                        "userId" to uid,
                        "poNumber" to po,
                        "supplierName" to supplier,
                        "status" to cloudRecStatus,
                        "totalItems" to totalCnt,
                        "totalReceivedQty" to recQty,
                        "itemsJson" to itemsStr,
                        "receivedAtIso" to recIso
                    )
                )
                count++
            }

            for (inv in inventory) {
                val docId = sanitizeDocId(inv.id)
                val title = inv.title.take(256).ifBlank { "Cycle Count" }
                val totalCnt = inv.items.size.toLong().coerceAtLeast(0L)
                val countedPcs = inv.totalActualQty.toLong().coerceAtLeast(0L)
                val itemsStr = wmsJson.encodeToString(inv.items).take(499000)
                val cntIso = inv.createdAt.take(64).ifBlank { "2026-01-01T00:00:00Z" }

                val dto = CloudInventoryReport(
                    userId = uid,
                    title = title,
                    totalItems = totalCnt,
                    totalCountedPieces = countedPcs,
                    itemsJson = itemsStr,
                    countedAtIso = cntIso,
                    createdAt = null,
                    updatedAt = null
                )
                val docRef = db.collection("users").document(uid).collection("inventory_reports").document(docId)
                upsertPreservingCreatedAt(
                    docRef = docRef,
                    createDto = dto,
                    updateFields = mapOf(
                        "userId" to uid,
                        "title" to title,
                        "totalItems" to totalCnt,
                        "totalCountedPieces" to countedPcs,
                        "itemsJson" to itemsStr,
                        "countedAtIso" to cntIso
                    )
                )
                count++
            }

            for (w in waves) {
                val docId = sanitizeDocId(w.id)
                val cloudWaveStatus = when (w.status) {
                    "COMPLETED" -> "COMPLETED"
                    "APPROVED" -> "APPROVED"
                    else -> "OPEN"
                }
                val waveNo = w.waveNo.take(128).ifBlank { docId }
                val totalInv = w.totalInvoicesCount.toLong().coerceAtLeast(0L)
                val totalIt = w.totalItemsCount.toLong().coerceAtLeast(0L)
                val waveStr = wmsJson.encodeToString(w).take(499000)
                val createdIso = w.createdAt.take(64).ifBlank { "2026-01-01T00:00:00Z" }

                val dto = CloudPickingWave(
                    userId = uid,
                    waveNo = waveNo,
                    status = cloudWaveStatus,
                    totalInvoices = totalInv,
                    totalItems = totalIt,
                    waveJson = waveStr,
                    createdAtIso = createdIso,
                    createdAt = null,
                    updatedAt = null
                )
                val docRef = db.collection("users").document(uid).collection("picking_waves").document(docId)
                upsertPreservingCreatedAt(
                    docRef = docRef,
                    createDto = dto,
                    updateFields = mapOf(
                        "userId" to uid,
                        "waveNo" to waveNo,
                        "status" to cloudWaveStatus,
                        "totalInvoices" to totalInv,
                        "totalItems" to totalIt,
                        "waveJson" to waveStr,
                        "createdAtIso" to createdIso
                    )
                )
                count++
            }

            Result.success(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
            Result.failure(e)
        }
    }

    suspend fun restoreReturnsFromCloud(): Result<List<ReturnReport>> {
        var path = "users/unknown/returns"
        return try {
            val uid = requireUserId()
            path = "users/$uid/returns"
            val snap = db.collection("users").document(uid)
                .collection("returns")
                .get()
                .await()
            val list = snap.toObjects(CloudReturnReport::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .mapNotNull { dto ->
                    runCatching {
                        val items = wmsJson.decodeFromString<List<ReturnSessionItem>>(dto.itemsJson)
                        val mappedStatus = when (dto.status) {
                            "PENDING_LAB" -> ReturnReportStatus.PENDING_LAB
                            "DRAFT" -> ReturnReportStatus.DRAFT
                            else -> ReturnReportStatus.COMPLETED
                        }
                        ReturnReport(
                            id = dto.id,
                            returnReceiptNo = dto.returnReceiptNo,
                            originalInvoiceNo = dto.originalInvoiceNo,
                            orderNo = dto.orderNo.ifBlank { null },
                            customerName = dto.customerName,
                            paymentMethod = dto.paymentMethod,
                            createdAt = dto.createdAtIso,
                            auditorName = dto.auditorName,
                            status = mappedStatus,
                            items = items,
                            totalRefundAmount = dto.totalRefundAmount,
                            notes = dto.notes.ifBlank { null }
                        )
                    }.getOrNull()
                }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path, auth)
            Result.failure(e)
        }
    }

    suspend fun restoreInventoryFromCloud(): Result<List<InventoryCountReport>> {
        var path = "users/unknown/inventory_reports"
        return try {
            val uid = requireUserId()
            path = "users/$uid/inventory_reports"
            val snap = db.collection("users").document(uid)
                .collection("inventory_reports")
                .get()
                .await()
            val list = snap.toObjects(CloudInventoryReport::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .mapNotNull { dto ->
                    runCatching {
                        val items = wmsJson.decodeFromString<List<InventoryCountItem>>(dto.itemsJson)
                        InventoryCountReport(
                            id = dto.id,
                            title = dto.title,
                            createdAt = dto.countedAtIso,
                            auditorName = auth.currentUser?.displayName ?: "Cloud Sync",
                            auditorId = uid,
                            items = items,
                            totalBookQty = items.sumOf { it.bookQty },
                            totalActualQty = items.sumOf { it.calculatedActualQty },
                            totalVarianceQty = items.sumOf { it.varianceQty }
                        )
                    }.getOrNull()
                }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path, auth)
            Result.failure(e)
        }
    }

    fun observeCloudReturnsCount(userId: String): Flow<Int> = flow {
        val path = "users/$userId/returns"
        emitAll(
            db.collection("users").document(userId).collection("returns")
                .snapshots()
                .map { it.size() }
                .catch { error ->
                    if (error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(error, OperationType.LIST, path, auth)
                    }
                    emit(0)
                }
        )
    }

    suspend fun testConnection(): Boolean {
        val uid = auth.currentUser?.uid ?: return false
        return try {
            db.collection("users").document(uid).collection("settings").document("current").get().await()
            true
        } catch (_: Exception) {
            false
        }
    }
}
