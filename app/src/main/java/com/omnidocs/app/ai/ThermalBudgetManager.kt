package com.omnidocs.app.ai

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

private const val TAG = "ThermalBudgetManager"

enum class ThermalStatus {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN
}

/**
 * Monitors device thermal status and battery power save mode to dynamically
 * budget on-device LLM generation tokens and prevent thermal throttling or overheating.
 */
@Singleton
class ThermalBudgetManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val _currentThermalStatus = MutableStateFlow(ThermalStatus.NONE)
    val currentThermalStatus: StateFlow<ThermalStatus> = _currentThermalStatus.asStateFlow()

    private val _isPowerSaveMode = MutableStateFlow(false)
    val isPowerSaveMode: StateFlow<Boolean> = _isPowerSaveMode.asStateFlow()

    init {
        initThermalMonitoring()
    }

    private fun initThermalMonitoring() {
        if (powerManager == null) return

        try {
            _isPowerSaveMode.value = powerManager.isPowerSaveMode

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val currentStatusInt = powerManager.currentThermalStatus
                _currentThermalStatus.value = mapThermalStatus(currentStatusInt)

                powerManager.addThermalStatusListener { status ->
                    val mapped = mapThermalStatus(status)
                    Log.d(TAG, "Thermal status changed to: $mapped (code: $status)")
                    _currentThermalStatus.value = mapped
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not initialize thermal status listener", e)
        }
    }

    /**
     * Map Android OS thermal status code to enum.
     */
    fun mapThermalStatus(statusCode: Int): ThermalStatus {
        return when (statusCode) {
            0 -> ThermalStatus.NONE
            1 -> ThermalStatus.LIGHT
            2 -> ThermalStatus.MODERATE
            3 -> ThermalStatus.SEVERE
            4 -> ThermalStatus.CRITICAL
            5 -> ThermalStatus.EMERGENCY
            6 -> ThermalStatus.SHUTDOWN
            else -> ThermalStatus.NONE
        }
    }

    /**
     * Determine if it is safe to execute on-device LLM inference.
     */
    fun isSafeToInfer(): Boolean {
        val status = _currentThermalStatus.value
        return status != ThermalStatus.CRITICAL &&
               status != ThermalStatus.EMERGENCY &&
               status != ThermalStatus.SHUTDOWN
    }

    /**
     * Calculate adjusted max token count based on current thermal state and power mode.
     */
    fun getBudgetedMaxTokens(requestedMaxTokens: Int): Int {
        return computeBudgetedTokens(
            requestedTokens = requestedMaxTokens,
            status = _currentThermalStatus.value,
            isPowerSave = _isPowerSaveMode.value
        )
    }

    companion object {
        /**
         * Pure calculation helper for token budgeting.
         */
        fun computeBudgetedTokens(
            requestedTokens: Int,
            status: ThermalStatus,
            isPowerSave: Boolean
        ): Int {
            var budget = requestedTokens

            // Adjust for thermal status
            budget = when (status) {
                ThermalStatus.NONE -> budget
                ThermalStatus.LIGHT -> (budget * 0.90f).toInt()
                ThermalStatus.MODERATE -> min(budget, 500)
                ThermalStatus.SEVERE -> min((budget * 0.40f).toInt(), 250)
                ThermalStatus.CRITICAL,
                ThermalStatus.EMERGENCY,
                ThermalStatus.SHUTDOWN -> 0 // Halt inference
            }

            // Further throttle if in OS Battery Saver mode
            if (isPowerSave && budget > 0) {
                budget = min(budget, 300)
            }

            return max(0, budget)
        }
    }
}
