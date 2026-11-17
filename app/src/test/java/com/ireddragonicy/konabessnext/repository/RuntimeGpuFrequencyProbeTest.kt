package com.ireddragonicy.konabessnext.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeGpuFrequencyProbeTest {
    @Test
    fun `new kgsl Hz node is probed first and remains in Hz`() {
        val candidate = RuntimeGpuFrequencyProbe.candidates.first()

        assertEquals(
            "cat /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies",
            candidate.command,
        )
        assertEquals(
            listOf(1_200_000_000L, 902_000_000L, 160_000_000L),
            RuntimeGpuFrequencyProbe.parse(
                listOf("1200000000 902000000 160000000"),
                candidate,
            ),
        )
    }

    @Test
    fun `MHz fallback is converted to Hz`() {
        val candidate = RuntimeGpuFrequencyProbe.candidates.single {
            it.command.endsWith("/freq_table_mhz")
        }

        assertEquals(
            listOf(1_200_000_000L, 902_000_000L, 160_000_000L),
            RuntimeGpuFrequencyProbe.parse(
                listOf("1200 902 160"),
                candidate,
            ),
        )
    }

    @Test
    fun `parser accepts comma hex and whitespace while dropping invalid values`() {
        val candidate = RuntimeGpuFrequencyProbe.Candidate("test")

        val parsed = RuntimeGpuFrequencyProbe.parse(
            listOf(" 0x3B9ACA00, 500000000 invalid 0 -1 500000000 "),
            candidate,
        )

        assertEquals(listOf(1_000_000_000L, 500_000_000L), parsed)
        assertTrue(parsed.all { it > 0L })
    }
}
