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

    @Test
    fun testLowRamThreshold_boundary() {
        val bytesPerGb = 1024L * 1024L * 1024L

        // 3 GB device -> low RAM, defaults to Qwen 2.5 0.5B
        val mem3Gb = ActivityManager.MemoryInfo().apply { totalMem = 3L * bytesPerGb }
        assertTrue(deviceCapabilityManager.isLowRamDevice(mem3Gb))
        assertEquals(DeviceCapabilityManager.DEFAULT_LOW_RAM_MODEL_ID, deviceCapabilityManager.getDefaultModelId(mem3Gb))

        // 4 GB device -> low RAM, defaults to Qwen 2.5 0.5B
        val mem4Gb = ActivityManager.MemoryInfo().apply { totalMem = 4L * bytesPerGb }
        assertTrue(deviceCapabilityManager.isLowRamDevice(mem4Gb))
        assertEquals(DeviceCapabilityManager.DEFAULT_LOW_RAM_MODEL_ID, deviceCapabilityManager.getDefaultModelId(mem4Gb))

        // 5.9 GB device (typical 6GB reported after OS reservation) -> < 6.0 GB -> low RAM
        val mem5_9Gb = ActivityManager.MemoryInfo().apply { totalMem = (5.9 * bytesPerGb).toLong() }
        assertTrue(deviceCapabilityManager.isLowRamDevice(mem5_9Gb))
        assertEquals(DeviceCapabilityManager.DEFAULT_LOW_RAM_MODEL_ID, deviceCapabilityManager.getDefaultModelId(mem5_9Gb))

        // 6.0 GB device -> NOT low RAM, defaults to Gemma 4 E2B
        val mem6Gb = ActivityManager.MemoryInfo().apply { totalMem = 6L * bytesPerGb }
        assertFalse(deviceCapabilityManager.isLowRamDevice(mem6Gb))
        assertEquals(DeviceCapabilityManager.DEFAULT_HIGH_RAM_MODEL_ID, deviceCapabilityManager.getDefaultModelId(mem6Gb))

        // 8 GB device -> NOT low RAM, defaults to Gemma 4 E2B
        val mem8Gb = ActivityManager.MemoryInfo().apply { totalMem = 8L * bytesPerGb }
        assertFalse(deviceCapabilityManager.isLowRamDevice(mem8Gb))
        assertEquals(DeviceCapabilityManager.DEFAULT_HIGH_RAM_MODEL_ID, deviceCapabilityManager.getDefaultModelId(mem8Gb))
    }
}
