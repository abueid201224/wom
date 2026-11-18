package com.example.services

import com.example.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

data class PackagingBreakdown(
    val cartons: Int,
    val packs: Int,
    val pieces: Int,
    val remainingFraction: Double = 0.0
)

object PackagingEngine {

    private const val DEFAULT_CARTON_FACTOR = 24.0
    private const val DEFAULT_PACK_FACTOR = 6.0

    fun normalizeCode(raw: String): String {
        val trimmed = raw.trim()
        val noZeros = trimmed.trimStart('0')
        return if (noZeros.isEmpty() && trimmed.isNotEmpty()) "0" else noZeros
    }

    fun matchBarcodeToPackagingRule(
        itemCode: String,
        rules: List<PackagingGroupRule>
    ): PackagingGroupRule? {
        val clean = itemCode.trim()
        if (clean.isEmpty()) return null
        val activeRules = rules.filter { it.isActive }
        val numericTarget = clean.toLongOrNull()

        if (numericTarget != null) {
            val rangeMatch = activeRules.firstOrNull { rule ->
                val a = rule.startBarcode.trim().toLongOrNull()
                val b = rule.endBarcode.trim().toLongOrNull()
                if (a != null && b != null) {
                    val low = minOf(a, b)
                    val high = maxOf(a, b)
                    numericTarget in low..high
                } else false
            }
            if (rangeMatch != null) return rangeMatch
        }

        return activeRules.firstOrNull { rule ->
            val start = rule.startBarcode.trim()
            val end = rule.endBarcode.trim()
            if (clean.length == start.length && start.length == end.length) {
                val low = minOf(start, end)
                val high = maxOf(start, end)
                clean >= low && clean <= high
            } else {
                val prefixLen = minOf(4, start.length, clean.length)
                prefixLen >= 3 && clean.startsWith(start.take(prefixLen))
            }
        }
    }

    private fun sanitizeFactor(factor: Double?, fallback: Double): BigDecimal {
        val safe = if (factor != null && factor.isFinite() && factor > 0.0) factor else fallback
        return BigDecimal.valueOf(safe).stripTrailingZeros()
    }

    fun calculatePackagingBreakdown(
        totalQty: Int,
        rule: PackagingGroupRule?
    ): PackagingBreakdown = calculatePackagingBreakdown(totalQty.toDouble(), rule)

    fun calculatePackagingBreakdown(
        totalQty: Double,
        rule: PackagingGroupRule?
    ): PackagingBreakdown {
        if (!totalQty.isFinite() || totalQty <= 0.0) {
            return PackagingBreakdown(0, 0, 0, 0.0)
        }

        val qtyBd = BigDecimal.valueOf(totalQty)
        val cFactor = sanitizeFactor(rule?.cartonFactor, DEFAULT_CARTON_FACTOR)
        val pFactor = sanitizeFactor(rule?.packFactor, DEFAULT_PACK_FACTOR)

        // Strict Division-by-Zero protection via sanitizeFactor (> 0)
        val cartonDiv = qtyBd.divideAndRemainder(cFactor)
        val cartons = cartonDiv[0].toInt().coerceAtLeast(0)
        val remAfterCartons = cartonDiv[1]

        val (packs, remAfterPacks) = if (pFactor < cFactor) {
            val packDiv = remAfterCartons.divideAndRemainder(pFactor)
            packDiv[0].toInt().coerceAtLeast(0) to packDiv[1]
        } else {
            0 to remAfterCartons
        }

        val wholePieces = remAfterPacks.setScale(0, RoundingMode.DOWN).toInt().coerceAtLeast(0)
        val fraction = remAfterPacks.subtract(BigDecimal(wholePieces))
            .max(BigDecimal.ZERO)
            .setScale(4, RoundingMode.HALF_UP)
            .toDouble()

        return PackagingBreakdown(
            cartons = cartons,
            packs = packs,
            pieces = wholePieces,
            remainingFraction = fraction
        )
    }

    fun calculateExactQtyFromColumns(
        cartons: Int,
        packs: Int,
        pieces: Int,
        cartonFactor: Double,
        packFactor: Double
    ): Double {
        val safeCartons = cartons.coerceAtLeast(0)
        val safePacks = packs.coerceAtLeast(0)
        val safePieces = pieces.coerceAtLeast(0)
        val cFactor = sanitizeFactor(cartonFactor, DEFAULT_CARTON_FACTOR)
        val pFactor = sanitizeFactor(packFactor, DEFAULT_PACK_FACTOR)

        val total = cFactor.multiply(BigDecimal(safeCartons))
            .add(pFactor.multiply(BigDecimal(safePacks)))
            .add(BigDecimal(safePieces))

        return total.setScale(4, RoundingMode.HALF_UP).toDouble()
    }

    fun calculateQtyFromColumns(
        cartons: Int,
        packs: Int,
        pieces: Int,
        cartonFactor: Double,
        packFactor: Double
    ): Int {
        return calculateExactQtyFromColumns(cartons, packs, pieces, cartonFactor, packFactor)
            .roundToInt()
            .coerceAtLeast(0)
    }

    fun detectDifficulty(rule: PackagingGroupRule?): GroupDifficultyLevel {
        return rule?.difficultyLevel ?: GroupDifficultyLevel.MEDIUM_INTERMEDIATE
    }

    private fun workerMatchesDifficulty(
        worker: WarehouseWorker,
        difficulty: GroupDifficultyLevel
    ): Boolean {
        return when (difficulty) {
            GroupDifficultyLevel.HIGH_EXPERT -> worker.experienceLevel == WorkerExperienceLevel.EXPERT
            GroupDifficultyLevel.MEDIUM_INTERMEDIATE -> worker.experienceLevel == WorkerExperienceLevel.INTERMEDIATE
            GroupDifficultyLevel.LOW_NOVICE -> worker.experienceLevel == WorkerExperienceLevel.NOVICE
        }
    }

    fun createPickingWaveFromMasterItems(
        waveTitle: String,
        createdBy: String,
        selectedInvoiceNumbers: Set<String>,
        masterItems: List<MasterInvoiceItem>,
        rules: List<PackagingGroupRule>,
        workers: List<WarehouseWorker>
    ): BatchPickingWave? {
        val filtered = masterItems.filter { it.invoiceNo in selectedInvoiceNumbers }
        if (filtered.isEmpty()) return null

        val activeWorkers = workers.filter { it.isActive }
        val groupedByItem = filtered.groupBy { normalizeCode(it.itemCode) }

        val aggregatedItems = groupedByItem.map { (_, entries) ->
            val first = entries.first()
            val matchedRule = matchBarcodeToPackagingRule(first.itemCode, rules)
            val totalReq = entries.sumOf { it.requiredQty.coerceAtLeast(0) }
            val breakdown = calculatePackagingBreakdown(totalReq, matchedRule)
            val sources = entries.groupBy { it.invoiceNo }.map { (inv, invRows) ->
                InvoiceItemSource(
                    invoiceNo = inv,
                    orderNo = invRows.firstOrNull()?.orderNo,
                    qty = invRows.sumOf { it.requiredQty.coerceAtLeast(0) }
                )
            }
            AggregatedPickingItem(
                id = "pick-${UUID.randomUUID().toString().take(8)}",
                itemCode = first.itemCode,
                itemName = first.itemName,
                unit = first.unit,
                systemUnit = matchedRule?.systemUnit ?: first.unit,
                baseUnit = matchedRule?.baseUnit ?: "PCS",
                groupId = matchedRule?.id ?: "group-general",
                groupName = matchedRule?.name ?: "أصناف عامة غير مصنفة",
                totalRequiredQty = totalReq,
                pickedQty = 0,
                cartonFactor = (matchedRule?.cartonFactor?.takeIf { it.isFinite() && it > 0.0 } ?: DEFAULT_CARTON_FACTOR)
                    .roundToInt().coerceAtLeast(1),
                packFactor = (matchedRule?.packFactor?.takeIf { it.isFinite() && it > 0.0 } ?: DEFAULT_PACK_FACTOR)
                    .roundToInt().coerceAtLeast(1),
                cartonsCount = breakdown.cartons,
                packsCount = breakdown.packs,
                piecesCount = breakdown.pieces,
                boxesPerCarton = matchedRule?.boxesPerCarton,
                cartonEquation = matchedRule?.cartonEquation,
                conversionEquation = matchedRule?.conversionEquation,
                packSizeDesc = matchedRule?.packSizeDesc,
                cartonSizeDesc = matchedRule?.cartonSizeSystemUnit,
                invoiceSources = sources,
                status = "PENDING"
            )
        }

        val itemsByGroup = aggregatedItems.groupBy { it.groupId }
        val roundRobinIndex = mutableMapOf<GroupDifficultyLevel, Int>()

        val groups = itemsByGroup.map { (groupId, items) ->
            val rule = rules.find { it.id == groupId }
            val difficulty = detectDifficulty(rule)
            val candidates = activeWorkers.filter { workerMatchesDifficulty(it, difficulty) }
                .ifEmpty { activeWorkers }
            val assignedWorker = if (candidates.isNotEmpty()) {
                val idx = roundRobinIndex.getOrDefault(difficulty, 0)
                roundRobinIndex[difficulty] = idx + 1
                candidates[idx % candidates.size]
            } else null

            val distinctInvoices = items.flatMap { it.invoiceSources.map { src -> src.invoiceNo } }.distinct()

            PickingProductGroup(
                groupId = groupId,
                groupName = rule?.name ?: items.first().groupName,
                difficulty = difficulty,
                assignedWorkerId = assignedWorker?.id,
                assignedWorkerName = assignedWorker?.name,
                assignedWorkerLevel = assignedWorker?.experienceLevel,
                cartonEquation = rule?.cartonEquation,
                conversionEquation = rule?.conversionEquation,
                systemUnit = rule?.systemUnit,
                baseUnit = rule?.baseUnit,
                items = items,
                totalQty = items.sumOf { it.totalRequiredQty },
                totalCartons = items.sumOf { it.cartonsCount },
                totalPacks = items.sumOf { it.packsCount },
                totalPieces = items.sumOf { it.piecesCount },
                invoicesCount = distinctInvoices.size,
                status = "PENDING"
            )
        }

        val nowIso = Instant.now().toString()
        val suffix = (System.currentTimeMillis() % 10000).toString().padStart(4, '0')
        return BatchPickingWave(
            id = "wave-${UUID.randomUUID().toString().take(8)}",
            waveNo = "WAVE-$suffix",
            title = waveTitle.ifBlank { "موجة انتقاء وتجهيز #$suffix" },
            createdAt = nowIso,
            createdBy = createdBy,
            totalInvoicesCount = selectedInvoiceNumbers.size,
            invoiceNumbers = selectedInvoiceNumbers.toList().sorted(),
            totalItemsCount = aggregatedItems.size,
            totalQuantity = aggregatedItems.sumOf { it.totalRequiredQty },
            totalCartons = groups.sumOf { it.totalCartons },
            totalPacks = groups.sumOf { it.totalPacks },
            totalPieces = groups.sumOf { it.totalPieces },
            groups = groups,
            status = "ASSIGNED",
            phase1Completed = false,
            preparedInvoices = emptyList()
        )
    }
}
