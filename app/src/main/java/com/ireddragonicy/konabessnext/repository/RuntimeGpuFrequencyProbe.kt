package com.ireddragonicy.konabessnext.repository

/**
 * Kernel nodes that may expose the active Adreno frequency table.
 *
 * The explicitly named MHz nodes and legacy kHz tables are converted to Hz
 * before values can be used to match a DTS bin or lock a test frequency.
 */
internal object RuntimeGpuFrequencyProbe {
    internal data class Candidate(
        val command: String,
        val multiplierToHz: Long = 1L,
        val legacyKhz: Boolean = false,
    )

    val platformKgslRoots = listOf(
        "/sys/devices/platform/soc/*.qcom,kgsl-3d0/kgsl/kgsl-3d0",
        "/sys/devices/platform/soc@0/*.qcom,kgsl-3d0/kgsl/kgsl-3d0",
        "/sys/devices/platform/*.qcom,kgsl-3d0/kgsl/kgsl-3d0",
    )
    // Match this GPU's devfreq device, excluding GPU bus and bandwidth monitors.
    const val CLASS_DEVFREQ_GPU = "/sys/class/devfreq/*.qcom,kgsl-3d0"

    val candidates: List<Candidate> = listOf(
        Candidate("cat /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies"),
        Candidate("cat /sys/class/kgsl/kgsl-3d0/frequencies", legacyKhz = true),
        Candidate("cat /sys/class/kgsl/kgsl-3d0/available_frequencies", legacyKhz = true),
        Candidate("cat /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies", legacyKhz = true),
        Candidate("cat $CLASS_DEVFREQ_GPU/available_frequencies", legacyKhz = true),
    ) + platformKgslRoots.flatMap { root ->
        listOf(
            Candidate("cat $root/gpu_available_frequencies"),
            Candidate("cat $root/frequencies", legacyKhz = true),
            Candidate("cat $root/available_frequencies", legacyKhz = true),
        )
    } + listOf(Candidate("cat /sys/class/kgsl/kgsl-3d0/freq_table_mhz", 1_000_000L)) +
        platformKgslRoots.map { Candidate("cat $it/freq_table_mhz", 1_000_000L) } +
        Candidate("cat /sys/kernel/gpu/gpu_freq_table", 1_000_000L)

    fun parse(lines: List<String>, candidate: Candidate): List<Long> {
        if (lines.isEmpty()) return emptyList()

        val values = lines.asSequence()
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
            .distinct()
            .toList()

        val multiplier = if (candidate.legacyKhz && (values.maxOrNull() ?: 0L) in 10_000L until 10_000_000L) {
            1_000L
        } else {
            candidate.multiplierToHz
        }
        return values.mapNotNull { value ->
            runCatching { Math.multiplyExact(value, multiplier) }.getOrNull()
        }
    }
}
