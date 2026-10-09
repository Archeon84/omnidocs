package com.omnidocs.app.ai

import android.app.ActivityManager
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class DeviceCapabilityManagerTest {

    private lateinit var context: Context
    private lateinit var deviceCapabilityManager: DeviceCapabilityManager

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        deviceCapabilityManager = DeviceCapabilityManager(context)
    }

    @Test
    fun testLowTierDevice_detection() {
        val memInfo = ActivityManager.MemoryInfo().apply {
            totalMem = 3L * 1024 * 1024 * 1024
            availMem = 1L * 1024 * 1024 * 1024
            lowMemory = false
        }

        val profile = deviceCapabilityManager.getMemoryProfile(memInfo)
        assertEquals(DeviceHardwareTier.LOW, profile.tier)
        assertTrue(profile.totalRamGb < 4.0)

        val lightModel = ModelInfo(
            id = "qwen_2_5_0_5b",
            name = "Qwen 2.5 0.5B",
            description = "Light",
            size = "350MB",
            downloadUrl = "url",
            fileName = "qwen.task",
            minRamGb = 3
        )
        val heavyModel = ModelInfo(
            id = "gemma_4_e2b",
            name = "Gemma 4 E2B",
            description = "Heavy",
            size = "1.1GB",
            downloadUrl = "url",
            fileName = "gemma.litertlm",
            minRamGb = 6
        )

        assertTrue(deviceCapabilityManager.isModelSafeForHardware(lightModel, memInfo))
        assertFalse(deviceCapabilityManager.isModelSafeForHardware(heavyModel, memInfo))
        assertNotNull(deviceCapabilityManager.getHardwareAdvisory(heavyModel, memInfo))
        assertNull(deviceCapabilityManager.getHardwareAdvisory(lightModel, memInfo))
    }

    @Test
    fun testHighTierDevice_detection() {
        val memInfo = ActivityManager.MemoryInfo().apply {
            totalMem = 12L * 1024 * 1024 * 1024
            availMem = 6L * 1024 * 1024 * 1024
            lowMemory = false
        }

        val profile = deviceCapabilityManager.getMemoryProfile(memInfo)
        assertEquals(DeviceHardwareTier.HIGH, profile.tier)
        assertEquals(12.0, profile.totalRamGb, 0.1)

        val heavyModel = ModelInfo(
            id = "gemma_4_e2b",
            name = "Gemma 4 E2B",
            description = "Heavy",
            size = "1.1GB",
            downloadUrl = "url",
            fileName = "gemma.litertlm",
            minRamGb = 6
        )
        assertTrue(deviceCapabilityManager.isModelSafeForHardware(heavyModel, memInfo))
        assertNull(deviceCapabilityManager.getHardwareAdvisory(heavyModel, memInfo))
    }
}
