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
}
