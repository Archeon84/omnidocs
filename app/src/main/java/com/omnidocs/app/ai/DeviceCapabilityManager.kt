package com.omnidocs.app.ai

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DeviceCapabilityManager"

/**
 * Categorization of physical device hardware capability based on total RAM.
 */
enum class DeviceHardwareTier {
    /** < 4 GB RAM: Budget / Android Go devices. Best suited for ultra-light models (<=0.5B). */
    LOW,
    /** 4 GB - 6 GB RAM: Mid-tier devices. Capable of <=2B models with moderate context. */
    MID,
    /** >= 6 GB RAM: High-tier / Flagship devices. Full capability for 2B–4B models. */
    HIGH
}

/**
 * Snapshot profile of current system memory.
 */
data class DeviceMemoryProfile(
    val totalRamBytes: Long,
    val totalRamGb: Double,
    val availableRamBytes: Long,
    val isLowMemory: Boolean,
    val tier: DeviceHardwareTier
)

/**
 * Evaluates device hardware capabilities to prevent Out-Of-Memory (OOM) kills
 * and thermal degradation by Android's Low Memory Killer (LMK).
 */
@Singleton
class DeviceCapabilityManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun getMemoryProfile(customMemInfo: ActivityManager.MemoryInfo? = null): DeviceMemoryProfile {
        return try {
            val memInfo = customMemInfo ?: run {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val info = ActivityManager.MemoryInfo()
                am?.getMemoryInfo(info)
                info
            }
            val totalBytes = memInfo.totalMem
            val totalGb = totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            val tier = when {
                totalGb < 4.0 -> DeviceHardwareTier.LOW
                totalGb < 6.0 -> DeviceHardwareTier.MID
                else -> DeviceHardwareTier.HIGH
            }
            DeviceMemoryProfile(
                totalRamBytes = totalBytes,
                totalRamGb = totalGb,
                availableRamBytes = memInfo.availMem,
                isLowMemory = memInfo.lowMemory,
                tier = tier
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query system memory info; falling back to conservative profile", e)
            DeviceMemoryProfile(
                totalRamBytes = 4L * 1024 * 1024 * 1024,
                totalRamGb = 4.0,
                availableRamBytes = 2L * 1024 * 1024 * 1024,
                isLowMemory = false,
                tier = DeviceHardwareTier.MID
            )
        }
    }

    /**
     * Checks whether a model's memory requirements can safely execute
     * on this hardware without high risk of LMK termination.
     */
    fun isModelSafeForHardware(model: ModelInfo, customMemInfo: ActivityManager.MemoryInfo? = null): Boolean {
        val profile = getMemoryProfile(customMemInfo)
        return profile.totalRamGb >= model.minRamGb
    }

    /**
     * Returns a human-friendly advisory message if hardware is under-provisioned
     * for the selected model.
     */
    fun getHardwareAdvisory(model: ModelInfo, customMemInfo: ActivityManager.MemoryInfo? = null): String? {
        val profile = getMemoryProfile(customMemInfo)
        if (profile.totalRamGb < model.minRamGb) {
            val roundedRam = "%.1f".format(profile.totalRamGb)
            return "⚠️ Device has ${roundedRam}GB RAM. ${model.name} recommends at least ${model.minRamGb}GB RAM to prevent Out-Of-Memory crashes. Consider selecting an Ultra-Light model."
        }
        return null
    }

    /**
     * Returns true if the device reports strictly less than 6.0 GB of physical RAM.
     */
    fun isLowRamDevice(customMemInfo: ActivityManager.MemoryInfo? = null): Boolean {
        return getMemoryProfile(customMemInfo).totalRamGb < LOW_RAM_THRESHOLD_GB
    }

    /**
     * Resolves the hardware-recommended default model ID:
     * - "qwen_2_5_0_5b" (Qwen 2.5 0.5B) when physical RAM < 6.0 GB
     * - "gemma_4_e2b" (Gemma 4 E2B) when physical RAM >= 6.0 GB
     */
    fun getDefaultModelId(customMemInfo: ActivityManager.MemoryInfo? = null): String {
        return if (isLowRamDevice(customMemInfo)) DEFAULT_LOW_RAM_MODEL_ID else DEFAULT_HIGH_RAM_MODEL_ID
    }

    companion object {
        /**
         * Physical RAM threshold in gigabytes. Devices reporting less than 6.0 GB
         * automatically default to lightweight sub-1B models to prevent LMK kills.
         */
        const val LOW_RAM_THRESHOLD_GB = 6.0

        const val DEFAULT_LOW_RAM_MODEL_ID = "qwen_2_5_0_5b"
        const val DEFAULT_HIGH_RAM_MODEL_ID = "gemma_4_e2b"
    }
}
