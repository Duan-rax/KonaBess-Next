package com.ireddragonicy.konabessnext.repository

import com.ireddragonicy.konabessnext.model.gpu.FailureReason
import com.ireddragonicy.konabessnext.model.gpu.GpuStabilityUiState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
    fun `resolver ignores empty devfreq shells and falls back to KGSL MHz controls`() = runBlocking {
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

    @Test
    fun `resolver skips read-only controls and selects the KGSL compatibility interface`() = runBlocking {
        val shell = object : ShellExecutor {
            override val isRootMode = true

            override suspend fun execForOutput(vararg commands: String): List<String> = emptyList()

            override suspend fun execAndCheck(vararg commands: String): Boolean {
                val command = commands.single()
                if ("min_clock_mhz" in command && ": >>" !in command) return true
                return ": >> '/sys/kernel/gpu/gpu_min_clock'" in command &&
                    ": >> '/sys/kernel/gpu/gpu_max_clock'" in command
            }
        }

        assertEquals(
            GpuFrequencyLockTarget(
                minPath = "/sys/kernel/gpu/gpu_min_clock",
                maxPath = "/sys/kernel/gpu/gpu_max_clock",
                hzPerUnit = 1_000_000L,
            ),
            RuntimeGpuFrequencyLock.resolve(shell),
        )
    }

    @Test
    fun `a partial lock is rolled back after a transient inconsistent read`() = runBlocking {
        val shell = PartialLockShell(allowRestore = true)
        val events = runPartialLockTest(shell)

        assertEquals(160L, shell.min)
        assertEquals(1_200L, shell.max)
        assertEquals(1, shell.restoreWrites)
        assertEquals(2, events.size)
        assertEquals(GpuStabilityRepository.TestEvent.Failed(FailureReason.WriteFreqRejected), events.last())
    }

    @Test
    fun `a persistently rejected restore has its own failure reason`() = runBlocking {
        val shell = PartialLockShell(allowRestore = false)
        val events = runPartialLockTest(shell)

        assertEquals(191L, shell.min)
        assertEquals(3, shell.restoreWrites)
        val point = events.first() as GpuStabilityRepository.TestEvent.PointFinished
        assertEquals(FailureReason.WriteFreqRejected, point.result.failureReason)
        assertEquals(GpuStabilityRepository.TestEvent.Failed(FailureReason.RestoreLimitsFailed), events.last())
    }

    private suspend fun runPartialLockTest(shell: ShellExecutor): List<GpuStabilityRepository.TestEvent> =
        GpuStabilityRepository(shell).runStabilityTest(
            GpuStabilityUiState(activeFrequenciesHz = listOf(191_000_000L), durationPerPointSec = 0),
        ).toList()

    private class PartialLockShell(private val allowRestore: Boolean) : ShellExecutor {
        override val isRootMode = true
        var min = 160L
            private set
        val max = 1_200L
        var restoreWrites = 0
            private set
        private var inconsistentRead = false

        override suspend fun execForOutput(vararg commands: String): List<String> {
            val command = commands.single()
            if (!command.startsWith("cat") || "min_clock_mhz" !in command || "max_clock_mhz" !in command) {
                return emptyList()
            }
            if (inconsistentRead) {
                inconsistentRead = false
                return listOf("826", "500")
            }
            return listOf(min.toString(), max.toString())
        }

        override suspend fun execAndCheck(vararg commands: String): Boolean {
            val command = commands.single()
            if (command.startsWith("[")) return "min_clock_mhz" in command
            for (write in command.split(" && ")) {
                if ("/max_clock_mhz" in write) {
                    inconsistentRead = true
                    return false
                }
                val value = write.split(' ')[1].toLong()
                if (value == 160L) {
                    restoreWrites++
                    if (!allowRestore) return false
                }
                min = value
            }
            return true
        }
    }
}
