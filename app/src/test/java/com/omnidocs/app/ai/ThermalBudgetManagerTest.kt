package com.omnidocs.app.ai

import org.junit.Assert.*
import org.junit.Test

class ThermalBudgetManagerTest {

    @Test
    fun testComputeBudgetedTokens_normalThermals() {
        val requested = 600
        val budgeted = ThermalBudgetManager.computeBudgetedTokens(
            requestedTokens = requested,
            status = ThermalStatus.NONE,
            isPowerSave = false
        )
        assertEquals(600, budgeted)
    }

    @Test
    fun testComputeBudgetedTokens_lightThermals() {
        val requested = 500
        val budgeted = ThermalBudgetManager.computeBudgetedTokens(
            requestedTokens = requested,
            status = ThermalStatus.LIGHT,
            isPowerSave = false
        )
        // 90% of 500 = 450
        assertEquals(450, budgeted)
    }

    @Test
    fun testComputeBudgetedTokens_moderateThermals() {
        val requested = 800
        val budgeted = ThermalBudgetManager.computeBudgetedTokens(
            requestedTokens = requested,
            status = ThermalStatus.MODERATE,
            isPowerSave = false
        )
        // Capped at 500 tokens
        assertEquals(500, budgeted)
    }

    @Test
    fun testComputeBudgetedTokens_severeThermals() {
        val requested = 800
        val budgeted = ThermalBudgetManager.computeBudgetedTokens(
            requestedTokens = requested,
            status = ThermalStatus.SEVERE,
            isPowerSave = false
        )
        // min(800 * 0.40 = 320, 250) = 250
        assertEquals(250, budgeted)
    }

    @Test
    fun testComputeBudgetedTokens_criticalAndEmergencyHalts() {
        val requested = 500
        val criticalBudget = ThermalBudgetManager.computeBudgetedTokens(
            requestedTokens = requested,
            status = ThermalStatus.CRITICAL,
            isPowerSave = false
        )
        assertEquals(0, criticalBudget)

        val emergencyBudget = ThermalBudgetManager.computeBudgetedTokens(
            requestedTokens = requested,
            status = ThermalStatus.EMERGENCY,
            isPowerSave = false
        )
        assertEquals(0, emergencyBudget)

        val shutdownBudget = ThermalBudgetManager.computeBudgetedTokens(
            requestedTokens = requested,
            status = ThermalStatus.SHUTDOWN,
            isPowerSave = false
        )
        assertEquals(0, shutdownBudget)
    }

    @Test
    fun testComputeBudgetedTokens_powerSaveModeThrottles() {
        val requested = 600
        val budgeted = ThermalBudgetManager.computeBudgetedTokens(
            requestedTokens = requested,
            status = ThermalStatus.NONE,
            isPowerSave = true
        )
        // Power save caps at 300
        assertEquals(300, budgeted)
    }
}
