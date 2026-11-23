package com.example.services

import com.example.model.*
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure, UI-independent WMS Audit Domain Engine.
 * Enforces:
 * - Strict invoice locking (no outside barcodes recorded)
 * - Zero cross-item compensation (a surplus in SKU-B NEVER cancels a shortage in SKU-A)
 * - Deterministic discrepancy deduplication on invoice reopen/reclose
 * - Optional 2-second double-scan safety guard for switching open invoices
 */
object AuditSessionEngine {

    const val DOUBLE_SCAN_WINDOW_MS = 2000L

    sealed interface ItemScanOutcome {
        data class Updated(
            val updatedSession: ActiveInvoiceSession,
            val updatedItem: ScannedAuditItem,
            val reachedExact100Invoice: Boolean
        ) : ItemScanOutcome

        data class RejectedOutsideInvoice(
            val rawCode: String,
            val activeInvoiceNo: String,
            val belongingInvoiceNo: String?
        ) : ItemScanOutcome

        data object SessionLocked : ItemScanOutcome
    }

    sealed interface SwitchSafetyDecision {
        data object ProceedImmediately : SwitchSafetyDecision
        data class RequireSecondScan(val prompt: PendingInvoiceSwitchConfirmation) : SwitchSafetyDecision
    }

    fun buildNewSession(
        invoiceRows: List<MasterInvoiceItem>,
        auditorName: String,
        auditorId: String,
        nowIso: String = Instant.now().toString(),
        nowMs: Long = System.currentTimeMillis()
    ): ActiveInvoiceSession {
        val itemsMap = linkedMapOf<String, ScannedAuditItem>()
        for (row in invoiceRows) {
            val key = PackagingEngine.normalizeCode(row.itemCode)
            val existing = itemsMap[key]
            if (existing != null) {
                itemsMap[key] = existing.copy(
                    requiredQty = existing.requiredQty + row.requiredQty.coerceAtLeast(0)
                )
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
                    lastScannedAt = nowIso,
                    originalIndex = row.originalIndex
                )
            }
        }
        return ActiveInvoiceSession(
            sessionId = "ses-${UUID.randomUUID().toString().take(12)}",
            orderNo = invoiceRows.firstOrNull()?.orderNo,
            invoiceNo = invoiceRows.first().invoiceNo,
            auditorName = auditorName,
            auditorId = auditorId,
            auditorSignature = null,
            startedAt = nowIso,
            lastActivityAt = nowIso,
            lastCheckpointAtMs = nowMs,
            items = itemsMap
        )
    }

    fun evaluateInvoiceSwitchGuard(
        currentOpenSession: ActiveInvoiceSession?,
        targetInvoiceNo: String,
        requireDoubleScan: Boolean,
        pendingConfirmation: PendingInvoiceSwitchConfirmation?,
        nowMs: Long = System.currentTimeMillis()
    ): SwitchSafetyDecision {
        if (currentOpenSession == null || !requireDoubleScan) {
            return SwitchSafetyDecision.ProceedImmediately
        }
        if (currentOpenSession.invoiceNo.equals(targetInvoiceNo, ignoreCase = true)) {
            return SwitchSafetyDecision.ProceedImmediately
        }
        val isSecondScanWithinWindow = pendingConfirmation != null &&
            pendingConfirmation.currentInvoiceNo.equals(currentOpenSession.invoiceNo, ignoreCase = true) &&
            pendingConfirmation.targetInvoiceNo.equals(targetInvoiceNo, ignoreCase = true) &&
            nowMs <= pendingConfirmation.expiresAtMs

        return if (isSecondScanWithinWindow) {
            SwitchSafetyDecision.ProceedImmediately
        } else {
            SwitchSafetyDecision.RequireSecondScan(
                PendingInvoiceSwitchConfirmation(
                    currentInvoiceNo = currentOpenSession.invoiceNo,
                    targetInvoiceNo = targetInvoiceNo,
                    firstScannedAtMs = nowMs,
                    expiresAtMs = nowMs + DOUBLE_SCAN_WINDOW_MS
                )
            )
        }
    }

    fun applyItemScan(
        session: ActiveInvoiceSession,
        rawCode: String,
        deltaQty: Int,
        masterItems: List<MasterInvoiceItem>,
        nowIso: String = Instant.now().toString(),
        nowMs: Long = System.currentTimeMillis()
    ): ItemScanOutcome {
        if (session.isLocked) return ItemScanOutcome.SessionLocked
        val normKey = PackagingEngine.normalizeCode(rawCode)
        val existingKey = session.items.keys.firstOrNull {
            PackagingEngine.normalizeCode(it) == normKey
        }

        if (existingKey == null) {
            val belonging = masterItems.firstOrNull {
                PackagingEngine.normalizeCode(it.itemCode) == normKey &&
                    !it.invoiceNo.equals(session.invoiceNo, ignoreCase = true)
            }
            return ItemScanOutcome.RejectedOutsideInvoice(
                rawCode = rawCode,
                activeInvoiceNo = session.invoiceNo,
                belongingInvoiceNo = belonging?.invoiceNo
            )
        }

        val item = session.items.getValue(existingKey)
        val newQty = (item.actualQty + deltaQty.coerceAtLeast(1)).coerceAtLeast(0)
        val newQtyStatus = classifyQtyStatus(actualQty = newQty, requiredQty = item.requiredQty)
        val updatedItem = item.copy(
            actualQty = newQty,
            qtyStatus = newQtyStatus,
            lastScannedAt = nowIso,
            scanHistory = (item.scanHistory + nowIso).takeLast(20)
        )
        val newMap = session.items.toMutableMap().apply { put(existingKey, updatedItem) }
        val updatedSession = session.copy(
            sessionId = session.sessionId.ifBlank { "ses-${UUID.randomUUID().toString().take(12)}" },
            lastActivityAt = nowIso,
            lastCheckpointAtMs = nowMs,
            lastScannedItemCode = existingKey,
            items = newMap
        )

        return ItemScanOutcome.Updated(
            updatedSession = updatedSession,
            updatedItem = updatedItem,
            reachedExact100Invoice = updatedSession.isFullyCompleted
        )
    }

    fun adjustItemQty(
        session: ActiveInvoiceSession,
        key: String,
        delta: Int,
        nowIso: String = Instant.now().toString(),
        nowMs: Long = System.currentTimeMillis()
    ): ActiveInvoiceSession? {
        if (session.isLocked) return null
        val item = session.items[key] ?: return null
        val newQty = (item.actualQty + delta).coerceAtLeast(0)
        val newQtyStatus = if (item.codeStatus == CodeStatus.MISMATCH) {
            QtyStatus.SURPLUS
        } else {
            classifyQtyStatus(actualQty = newQty, requiredQty = item.requiredQty)
        }
        val updatedMap = session.items.toMutableMap().apply {
            put(key, item.copy(actualQty = newQty, qtyStatus = newQtyStatus, lastScannedAt = nowIso))
        }
        return session.copy(
            sessionId = session.sessionId.ifBlank { "ses-${UUID.randomUUID().toString().take(12)}" },
            items = updatedMap,
            lastActivityAt = nowIso,
            lastCheckpointAtMs = nowMs
        )
    }

    fun classifyQtyStatus(actualQty: Int, requiredQty: Int): QtyStatus = when {
        actualQty == requiredQty -> QtyStatus.EXACT
        actualQty > requiredQty -> QtyStatus.SURPLUS
        else -> QtyStatus.SHORTAGE
    }

    /**
     * Builds artifacts for closing an invoice without signature.
     * Prevents cross-item compensation by checking each SKU individually.
     */
    fun buildClosedInvoiceArtifacts(
        session: ActiveInvoiceSession,
        auditorName: String,
        auditorId: String,
        nowIso: String = Instant.now().toString()
    ): Triple<List<AuditDiscrepancy>, CompletedInvoiceRecord, InvoiceAuditHistory> {
        val items = session.items.values.toList()

        // Deduplicate by normalized code & evaluate every SKU independently (no cross-item compensation)
        val discByNormCode = linkedMapOf<String, AuditDiscrepancy>()
        for (item in items) {
            if (item.codeStatus == CodeStatus.MISMATCH || item.qtyStatus != QtyStatus.EXACT) {
                val key = PackagingEngine.normalizeCode(item.itemCode)
                discByNormCode[key] = AuditDiscrepancy(
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
                    auditedAt = nowIso,
                    auditorName = auditorName,
                    auditorId = auditorId
                )
            }
        }
        val discList = discByNormCode.values.toList()

        val totalReqQty = items.sumOf { it.requiredQty }
        val totalScanQty = items.sumOf { it.actualQty }
        val exactCount = items.count { it.qtyStatus == QtyStatus.EXACT && it.codeStatus == CodeStatus.MATCH }

        val completed = CompletedInvoiceRecord(
            invoiceNo = session.invoiceNo,
            orderNo = session.orderNo,
            auditorName = auditorName,
            auditorId = auditorId,
            auditorSignature = null,
            completedAt = nowIso,
            totalItems = items.size,
            totalQty = totalScanQty
        )

        val history = InvoiceAuditHistory(
            orderNo = session.orderNo,
            invoiceNo = session.invoiceNo,
            auditorName = auditorName,
            auditorId = auditorId,
            totalRequiredItems = items.size,
            totalRequiredQty = totalReqQty,
            totalScannedQty = totalScanQty,
            scannedItemsCount = items.count { it.actualQty > 0 },
            exactItemsCount = exactCount,
            discrepancyCount = discList.size,
            status = if (discList.isEmpty()) "EXACT" else "DISCREPANCY",
            completedAt = nowIso
        )

        return Triple(discList, completed, history)
    }

    /**
     * Ensures no duplicate discrepancy rows for the same (invoiceNo, normalizedItemCode).
     */
    fun deduplicateDiscrepancies(discrepancies: List<AuditDiscrepancy>): List<AuditDiscrepancy> {
        val seen = linkedMapOf<String, AuditDiscrepancy>()
        for (d in discrepancies) {
            val key = "${d.invoiceNo.trim().lowercase()}::${PackagingEngine.normalizeCode(d.itemCode)}"
            seen.putIfAbsent(key, d)
        }
        return seen.values.toList()
    }

    /**
     * Computes full-session totals strictly WITHOUT cross-item compensation:
     * accuracy = (totalRequiredQty - (totalShortageQty + totalSurplusQty)) / totalRequiredQty
     */
    fun buildSessionApprovalSummary(
        master: List<MasterInvoiceItem>,
        completed: List<CompletedInvoiceRecord>,
        history: List<InvoiceAuditHistory>,
        rawDiscrepancies: List<AuditDiscrepancy>,
        snapshots: List<DailyAuditSnapshot>,
        activeSession: ActiveInvoiceSession?
    ): SessionApprovalSummary {
        val disc = deduplicateDiscrepancies(rawDiscrepancies)
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
            val shortageQty = invDiscs.filter { it.difference < 0 }.sumOf { abs(it.difference) }
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
        val totalShortageQty = disc.filter { it.difference < 0 }.sumOf { abs(it.difference) }
        val totalSurplusQty = disc.filter { it.difference > 0 }.sumOf { it.difference }
        // No cross-item netting: total gross variance = shortages + surpluses
        val totalGrossVarianceQty = totalShortageQty + totalSurplusQty
        val accuracyPct = if (totalReqQty > 0 && invoiceDetails.isNotEmpty()) {
            (((totalReqQty - totalGrossVarianceQty).coerceAtLeast(0).toDouble() / totalReqQty) * 100.0)
                .roundToInt()
                .coerceIn(0, 100)
        } else 0

        val isAllDone = allMasterInvoices.isNotEmpty() && remainingCount == 0 && activeSession == null
        val latestSnapshot = snapshots.firstOrNull()
        val latestCompletedAt = completed.maxOfOrNull { it.completedAt } ?: ""
        val isApproved = isAllDone &&
            latestSnapshot != null &&
            latestSnapshot.completedInvoices == completed.size &&
            latestSnapshot.closedAt >= latestCompletedAt

        return SessionApprovalSummary(
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
    }
}
