package com.ireddragonicy.konabessnext.repository

/**
 * Kernel nodes that may expose the active Adreno frequency table.
 *
 * Most nodes report Hz (or the legacy kHz values handled by callers), while
 * [freq_table_mhz] is explicitly documented by its name and must be converted
 * before it can be used to match a DTS bin or lock a stability-test frequency.
 */
internal object RuntimeGpuFrequencyProbe {
    internal data class Candidate(
        val command: String,
        val multiplierToHz: Long = 1L,
    )

    val candidates: List<Candidate> = listOf(
        Candidate("cat /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies"),
        Candidate("cat /sys/class/kgsl/kgsl-3d0/frequencies"),
        Candidate("cat /sys/class/kgsl/kgsl-3d0/available_frequencies"),
        Candidate("cat /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies"),
        Candidate("cat /sys/devices/platform/soc/*.qcom,kgsl-3d0/kgsl/kgsl-3d0/frequencies"),
        Candidate("cat /sys/devices/platform/soc/*.qcom,kgsl-3d0/kgsl/kgsl-3d0/available_frequencies"),
        Candidate("cat /sys/devices/platform/soc@0/*.qcom,kgsl-3d0/kgsl/kgsl-3d0/frequencies"),
        Candidate("cat /sys/devices/platform/*.qcom,kgsl-3d0/kgsl/kgsl-3d0/frequencies"),
        Candidate(
            command = "cat /sys/class/kgsl/kgsl-3d0/freq_table_mhz",
            multiplierToHz = 1_000_000L,
        ),
    )

    fun parse(lines: List<String>, candidate: Candidate): List<Long> {
        if (lines.isEmpty()) return emptyList()

        return lines.asSequence()
            .flatMap { line ->
                line.replace(",", " ")
                    .split(Regex("\\s+"))
                    .asSequence()
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { token ->
                val value = if (token.startsWith("0x", ignoreCase = true)) {
                    token.substring(2).toLongOrNull(16)
                } else {
                    token.toLongOrNull()
                }
                value?.takeIf { it > 0L }
            }
            .mapNotNull { value ->
                runCatching { Math.multiplyExact(value, candidate.multiplierToHz) }.getOrNull()
            }
            .distinct()
            .toList()
    }
}
