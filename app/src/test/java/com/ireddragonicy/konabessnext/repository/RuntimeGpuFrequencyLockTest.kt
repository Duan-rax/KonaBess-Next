package com.ireddragonicy.konabessnext.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeGpuFrequencyLockTest {
    private val hzTarget = GpuFrequencyLockTarget(
        minPath = "/gpu/min_freq",
        maxPath = "/gpu/max_freq",
        hzPerUnit = 1L,
    )

    @Test
    fun `MHz controls convert exact Hz frequencies`() {
        val target = GpuFrequencyLockTarget(
            minPath = "/gpu/min_clock_mhz",
            maxPath = "/gpu/max_clock_mhz",
            hzPerUnit = 1_000_000L,
        )

        assertEquals(160L, target.rawValueForHz(160_000_000L))
        assertEquals(1_200L, target.rawValueForHz(1_200_000_000L))
        assertNull(target.rawValueForHz(160_500_000L))
    }

    @Test
    fun `raising a pinned frequency expands max before min`() {
        assertEquals(
            listOf(
                "echo 191000000 > '/gpu/max_freq'",
                "echo 191000000 > '/gpu/min_freq'",
            ),
            RuntimeGpuFrequencyLock.buildTransitionCommands(
                target = hzTarget,
                currentMin = 160_000_000L,
                currentMax = 160_000_000L,
                requestedMin = 191_000_000L,
                requestedMax = 191_000_000L,
            ),
        )
    }

    @Test
    fun `lowering a pinned frequency expands min before max`() {
        assertEquals(
            listOf(
                "echo 902000000 > '/gpu/min_freq'",
                "echo 902000000 > '/gpu/max_freq'",
            ),
            RuntimeGpuFrequencyLock.buildTransitionCommands(
                target = hzTarget,
                currentMin = 1_200_000_000L,
                currentMax = 1_200_000_000L,
                requestedMin = 902_000_000L,
                requestedMax = 902_000_000L,
            ),
        )
    }

    @Test
    fun `restoring a wider range expands both boundaries safely`() {
        assertEquals(
            listOf(
                "echo 1200 > '/gpu/max_freq'",
                "echo 160 > '/gpu/min_freq'",
            ),
            RuntimeGpuFrequencyLock.buildTransitionCommands(
                target = hzTarget.copy(hzPerUnit = 1_000_000L),
                currentMin = 902L,
                currentMax = 902L,
                requestedMin = 160L,
                requestedMax = 1_200L,
            ),
        )
    }

    @Test
    fun `resolver ignores empty devfreq shells and falls back to KGSL MHz controls`() = runTest {
        val shell = object : ShellExecutor {
            override val isRootMode: Boolean = true

            override suspend fun execForOutput(vararg commands: String): List<String> = emptyList()

            override suspend fun execAndCheck(vararg commands: String): Boolean {
                return commands.singleOrNull()?.contains("min_clock_mhz") == true
            }
        }

        assertEquals(
            GpuFrequencyLockTarget(
                minPath = "/sys/class/kgsl/kgsl-3d0/min_clock_mhz",
                maxPath = "/sys/class/kgsl/kgsl-3d0/max_clock_mhz",
                hzPerUnit = 1_000_000L,
            ),
            RuntimeGpuFrequencyLock.resolve(shell),
        )
    }
}
