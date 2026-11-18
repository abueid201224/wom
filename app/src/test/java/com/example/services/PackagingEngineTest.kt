package com.example.services

import com.example.data.local.SeedData
import com.example.model.GroupDifficultyLevel
import com.example.model.PackagingGroupRule
import com.example.model.WorkerExperienceLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackagingEngineTest {

    @Test
    fun normalizeCode_stripsLeadingZerosAndPreservesSingleZero() {
        assertEquals("114110055", PackagingEngine.normalizeCode("  000114110055  "))
        assertEquals("0", PackagingEngine.normalizeCode("0000"))
        assertEquals("", PackagingEngine.normalizeCode("   "))
    }

    @Test
    fun matchBarcodeToPackagingRule_matchesNormalAndReversedRangesAndIgnoresInactive() {
        val rules = SeedData.defaultPackagingRules
        val matchedKitchen = PackagingEngine.matchBarcodeToPackagingRule("114110055", rules)
        assertNotNull(matchedKitchen)
        assertEquals("rule-1141", matchedKitchen?.id)

        val reversedRule = PackagingGroupRule(
            id = "rev-rule",
            name = "Reversed",
            startBarcode = "999999999",
            endBarcode = "999000000",
            cartonFactor = 10.0,
            packFactor = 2.0,
            unitName = "PCS",
            isActive = true
        )
        val inactiveRule = reversedRule.copy(id = "inactive-rule", isActive = false)
        assertNull(PackagingEngine.matchBarcodeToPackagingRule("999500000", listOf(inactiveRule)))
        assertEquals("rev-rule", PackagingEngine.matchBarcodeToPackagingRule("999500000", listOf(reversedRule))?.id)
    }

    @Test
    fun calculatePackagingBreakdown_handlesBigDecimalFractionsAndZeroGuards() {
        val plasticRule = SeedData.defaultPackagingRules.first { it.id == "rule-3100" } // carton=24, pack=6
        val b1 = PackagingEngine.calculatePackagingBreakdown(31, plasticRule)
        assertEquals(1, b1.cartons)
        assertEquals(1, b1.packs)
        assertEquals(1, b1.pieces)
        assertEquals(0.0, b1.remainingFraction, 0.0001)

        val spoonRule = SeedData.defaultPackagingRules.first { it.id == "rule-1142" } // carton=6.0 DZ, pack=0.5 DZ
        val b2 = PackagingEngine.calculatePackagingBreakdown(6.5, spoonRule)
        assertEquals(1, b2.cartons)
        assertEquals(1, b2.packs)
        assertEquals(0, b2.pieces)
        assertEquals(0.0, b2.remainingFraction, 0.0001)

        val b3 = PackagingEngine.calculatePackagingBreakdown(6.75, spoonRule)
        assertEquals(1, b3.cartons)
        assertEquals(1, b3.packs)
        assertEquals(0, b3.pieces)
        assertEquals(0.25, b3.remainingFraction, 0.0001)

        // Division-by-zero / negative / NaN / Infinity safety
        val zeroRule = PackagingGroupRule(
            id = "bad-rule",
            name = "Zero Factors",
            startBarcode = "1",
            endBarcode = "9",
            cartonFactor = 0.0,
            packFactor = -3.0,
            unitName = "PCS"
        )
        val safeBreakdown = PackagingEngine.calculatePackagingBreakdown(30, zeroRule)
        assertEquals(1, safeBreakdown.cartons) // falls back to 24.0
        assertEquals(1, safeBreakdown.packs)   // falls back to 6.0
        assertEquals(0, safeBreakdown.pieces)

        assertEquals(PackagingBreakdown(0, 0, 0, 0.0), PackagingEngine.calculatePackagingBreakdown(-5.0, plasticRule))
        assertEquals(PackagingBreakdown(0, 0, 0, 0.0), PackagingEngine.calculatePackagingBreakdown(Double.NaN, plasticRule))
    }

    @Test
    fun calculateQtyFromColumns_preservesExactDecimalAndProtectsInvalidFactors() {
        val exact = PackagingEngine.calculateExactQtyFromColumns(
            cartons = 1,
            packs = 1,
            pieces = 0,
            cartonFactor = 6.0,
            packFactor = 0.5
        )
        assertEquals(6.5, exact, 0.0001)

        val rounded = PackagingEngine.calculateQtyFromColumns(
            cartons = 2,
            packs = 1,
            pieces = 3,
            cartonFactor = 24.0,
            packFactor = 6.0
        )
        assertEquals(57, rounded)

        // Zero/negative factor protection falls back to defaults (24, 6)
        val guarded = PackagingEngine.calculateExactQtyFromColumns(
            cartons = 1,
            packs = 1,
            pieces = 2,
            cartonFactor = 0.0,
            packFactor = Double.NEGATIVE_INFINITY
        )
        assertEquals(32.0, guarded, 0.0001)
    }

    @Test
    fun createPickingWaveFromMasterItems_aggregatesQuantitiesAndAssignsDifficultyWorkers() {
        val wave = PackagingEngine.createPickingWaveFromMasterItems(
            waveTitle = "Wave Morning",
            createdBy = "AUD-101",
            selectedInvoiceNumbers = setOf("20450101", "20450103"),
            masterItems = SeedData.defaultDemoMasterItems,
            rules = SeedData.defaultPackagingRules,
            workers = SeedData.defaultWorkers
        )
        assertNotNull(wave)
        wave!!
        assertEquals(2, wave.totalInvoicesCount)
        assertEquals(listOf("20450101", "20450103"), wave.invoiceNumbers)

        // Item 114110055 appears in both invoices: 4 + 2 = 6
        val allItems = wave.groups.flatMap { it.items }
        val knifeItem = allItems.firstOrNull { it.itemCode == "114110055" }
        assertNotNull(knifeItem)
        assertEquals(6, knifeItem?.totalRequiredQty)
        assertEquals(2, knifeItem?.invoiceSources?.size)

        // Verify worker experience matches group difficulty
        val expertGroup = wave.groups.first { it.difficulty == GroupDifficultyLevel.HIGH_EXPERT }
        assertEquals(WorkerExperienceLevel.EXPERT, expertGroup.assignedWorkerLevel)

        val noviceGroup = wave.groups.first { it.difficulty == GroupDifficultyLevel.LOW_NOVICE }
        assertEquals(WorkerExperienceLevel.NOVICE, noviceGroup.assignedWorkerLevel)

        assertTrue(wave.totalQuantity > 0)
        assertNull(
            PackagingEngine.createPickingWaveFromMasterItems(
                waveTitle = "Empty",
                createdBy = "AUD-101",
                selectedInvoiceNumbers = setOf("99999999"),
                masterItems = SeedData.defaultDemoMasterItems,
                rules = SeedData.defaultPackagingRules,
                workers = SeedData.defaultWorkers
            )
        )
    }
}
