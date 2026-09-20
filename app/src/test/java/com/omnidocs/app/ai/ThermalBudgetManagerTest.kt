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
        val requested = 1500
        val budgeted = ThermalBudgetManager.computeBudgetedTokens(
            requestedTokens = requested,
            status = ThermalStatus.MODERATE,
            isPowerSave = false
        )
        // Capped at 900 tokens (ample for grounded answers, preventing mid-sentence truncation)
        assertEquals(900, budgeted)

        val requestedLow = 700
        val budgetedLow = ThermalBudgetManager.computeBudgetedTokens(
            requestedTokens = requestedLow,
            status = ThermalStatus.MODERATE,
            isPowerSave = false
        )
        assertEquals(700, budgetedLow)
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

    @Test
    fun testComputeBudgetedTokens_lowBatteryDischargingThrottles() {
        // Discharging at 12% battery -> capped at 350
        val lowBatteryBudget = ThermalBudgetManager.computeBudgetedTokens(
            requestedTokens = 800,
            status = ThermalStatus.NONE,
            isPowerSave = false,
            batteryLevel = 12,
            isCharging = false
        )
        assertEquals(350, lowBatteryBudget)

        // Discharging at 4% critical battery -> halted (0) to save device
        val criticalBatteryBudget = ThermalBudgetManager.computeBudgetedTokens(
            requestedTokens = 800,
            status = ThermalStatus.NONE,
            isPowerSave = false,
            batteryLevel = 4,
            isCharging = false
        )
        assertEquals(0, criticalBatteryBudget)

        // Plugged into charger at 12% battery -> normal budget (800)
        val chargingBudget = ThermalBudgetManager.computeBudgetedTokens(
            requestedTokens = 800,
            status = ThermalStatus.NONE,
            isPowerSave = false,
            batteryLevel = 12,
            isCharging = true
        )
        assertEquals(800, chargingBudget)
    }
}
