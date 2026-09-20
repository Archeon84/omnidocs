package com.omnidocs.app.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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

    private val _batteryLevel = MutableStateFlow(100)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isCharging = MutableStateFlow(true)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

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

            // Power-save mode was previously read once at init and never updated.
            // Listen for changes so throttling tracks the real OS state.
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) return
                    val inPowerSave = powerManager.isPowerSaveMode
                    Log.d(TAG, "Power save mode changed to: $inPowerSave")
                    _isPowerSaveMode.value = inPowerSave
                }
            }
            val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }

            // Battery state monitoring for power governor
            val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(null, batteryFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(null, batteryFilter)
            }
            batteryIntent?.let { updateBatteryState(it) }

            val batteryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                        intent?.let { updateBatteryState(it) }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(batteryReceiver, batteryFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(batteryReceiver, batteryFilter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not initialize thermal status listener", e)
        }
    }

    private fun updateBatteryState(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
            _batteryLevel.value = (level * 100) / scale
        }
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        _isCharging.value = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
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
        val criticalBattery = _batteryLevel.value <= 5 && !_isCharging.value
        return status != ThermalStatus.CRITICAL &&
               status != ThermalStatus.EMERGENCY &&
               status != ThermalStatus.SHUTDOWN &&
               !criticalBattery
    }

    /**
     * Recommends optimal CPU inference threads based on current thermal and battery state:
     * - 2 threads when thermal status is MODERATE+ or battery is low discharging.
     * - 4 threads when thermals are cool and battery is healthy.
     */
    fun getRecommendedInferenceThreads(): Int {
        val status = _currentThermalStatus.value
        val lowBatteryDischarging = _batteryLevel.value <= 15 && !_isCharging.value
        return if (status.ordinal >= ThermalStatus.MODERATE.ordinal || _isPowerSaveMode.value || lowBatteryDischarging) {
            2
        } else {
            4
        }
    }

    /**
     * Calculate adjusted max token count based on current thermal state and power mode.
     */
    fun getBudgetedMaxTokens(requestedMaxTokens: Int): Int {
        return computeBudgetedTokens(
            requestedTokens = requestedMaxTokens,
            status = _currentThermalStatus.value,
            isPowerSave = _isPowerSaveMode.value,
            batteryLevel = _batteryLevel.value,
            isCharging = _isCharging.value
        )
    }

    companion object {
        /**
         * Pure calculation helper for token budgeting.
         */
        fun computeBudgetedTokens(
            requestedTokens: Int,
            status: ThermalStatus,
            isPowerSave: Boolean,
            batteryLevel: Int = 100,
            isCharging: Boolean = true
        ): Int {
            var budget = requestedTokens

            // Adjust for thermal status
            budget = when (status) {
                ThermalStatus.NONE -> budget
                ThermalStatus.LIGHT -> (budget * 0.90f).toInt()
                ThermalStatus.MODERATE -> min(budget, 900)
                ThermalStatus.SEVERE -> min((budget * 0.40f).toInt(), 250)
                ThermalStatus.CRITICAL,
                ThermalStatus.EMERGENCY,
                ThermalStatus.SHUTDOWN -> 0 // Halt inference
            }

            // Further throttle if in OS Battery Saver mode
            if (isPowerSave && budget > 0) {
                budget = min(budget, 300)
            }

            // Power Governor: Critical battery protection when discharging
            if (!isCharging && budget > 0) {
                if (batteryLevel <= 5) {
                    budget = 0 // Halt inference to prevent sudden device shutdown
                } else if (batteryLevel <= 15) {
                    budget = min(budget, 350)
                }
            }

            return max(0, budget)
        }
    }
}
