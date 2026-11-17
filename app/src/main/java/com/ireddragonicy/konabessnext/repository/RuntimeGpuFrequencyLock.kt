package com.ireddragonicy.konabessnext.repository

/** A pair of kernel controls that can constrain the GPU to a frequency range. */
internal data class GpuFrequencyLockTarget(
    val minPath: String,
    val maxPath: String,
    val hzPerUnit: Long,
    val governorPath: String? = null,
) {
    fun rawValueForHz(frequencyHz: Long): Long? {
        if (frequencyHz <= 0L || hzPerUnit <= 0L || frequencyHz % hzPerUnit != 0L) return null
        return frequencyHz / hzPerUnit
    }
}

/**
 * Resolves vendor-specific Adreno lock controls and builds range transitions
 * that never temporarily request min > max.
 */
internal object RuntimeGpuFrequencyLock {
    private const val KGSL_ROOT = "/sys/class/kgsl/kgsl-3d0"

    /**
     * Some kernels put the controls directly in devfreq, while others add a
     * device-named child directory. A directory only counts when both controls
     * exist; several HyperOS kernels expose an otherwise empty devfreq shell.
     */
    val devfreqBasePatterns: List<String> = listOf(
        "$KGSL_ROOT/devfreq",
        "${RuntimeGpuFrequencyProbe.CLASS_DEVFREQ_GPU}",
        "/sys/devices/platform/soc/soc:qcom,kgsl-3d0/devfreq",
        "/sys/devices/platform/soc/*.qcom,kgsl-3d0/devfreq",
        "/sys/devices/platform/soc/*.qcom,kgsl-3d0/devfreq/*",
        "/sys/devices/platform/soc@0/*.qcom,kgsl-3d0/devfreq",
        "/sys/devices/platform/soc@0/*.qcom,kgsl-3d0/devfreq/*",
        "/sys/devices/platform/*.qcom,kgsl-3d0/devfreq",
        "/sys/devices/platform/*.qcom,kgsl-3d0/devfreq/*",
    )

    private val directTargets: List<GpuFrequencyLockTarget> = listOf(
        GpuFrequencyLockTarget(
            minPath = "$KGSL_ROOT/min_gpuclk",
            maxPath = "$KGSL_ROOT/max_gpuclk",
            hzPerUnit = 1L,
        ),
        GpuFrequencyLockTarget(
            minPath = "$KGSL_ROOT/min_clock_mhz",
            maxPath = "$KGSL_ROOT/max_clock_mhz",
            hzPerUnit = 1_000_000L,
        ),
        // KGSL also exports its MHz controls through a separate sysfs interface.
        GpuFrequencyLockTarget(
            minPath = "/sys/kernel/gpu/gpu_min_clock",
            maxPath = "/sys/kernel/gpu/gpu_max_clock",
            hzPerUnit = 1_000_000L,
        ),
    )

    suspend fun resolve(shellExecutor: ShellExecutor): GpuFrequencyLockTarget? {
        for (pattern in devfreqBasePatterns) {
            val command = "for d in $pattern; do " +
                "[ -e \"${'$'}d/min_freq\" ] && [ -e \"${'$'}d/max_freq\" ] && " +
                "( : >> \"${'$'}d/min_freq\" && : >> \"${'$'}d/max_freq\" ) 2>/dev/null && " +
                "{ echo \"${'$'}d\"; break; }; done"
            val basePath = runCatching { shellExecutor.execForOutput(command) }
                .getOrNull()
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: continue
            val governorPath = "$basePath/governor"
                .takeIf { shellExecutor.execAndCheck("[ -e '$it' ]") }
            return GpuFrequencyLockTarget(
                minPath = "$basePath/min_freq",
                maxPath = "$basePath/max_freq",
                hzPerUnit = 1L,
                governorPath = governorPath,
            )
        }

        // Open each control for writing without sending any data. A root
        // access check (-w) can succeed even if sysfs refuses the actual open.
        return directTargets.firstOrNull { target ->
            shellExecutor.execAndCheck(
                "[ -e '${target.minPath}' ] && [ -e '${target.maxPath}' ] && " +
                    "( : >> '${target.minPath}' && : >> '${target.maxPath}' ) 2>/dev/null"
            )
        }
    }

    /**
     * Build a safe transition from the current range to the requested range.
     * Expand either boundary first, then tighten it, so every intermediate
     * state satisfies min <= max.
     */
    fun buildTransitionCommands(
        target: GpuFrequencyLockTarget,
        currentMin: Long,
        currentMax: Long,
        requestedMin: Long,
        requestedMax: Long,
    ): List<String> {
        if (currentMin <= 0L || currentMax <= 0L || currentMin > currentMax) return emptyList()
        if (requestedMin <= 0L || requestedMax <= 0L || requestedMin > requestedMax) return emptyList()

        var activeMin = currentMin
        var activeMax = currentMax
        val commands = mutableListOf<String>()

        if (requestedMax > activeMax) {
            commands += writeCommand(target.maxPath, requestedMax)
            activeMax = requestedMax
        }
        if (requestedMin < activeMin) {
            commands += writeCommand(target.minPath, requestedMin)
            activeMin = requestedMin
        }
        if (requestedMin != activeMin) {
            commands += writeCommand(target.minPath, requestedMin)
            activeMin = requestedMin
        }
        if (requestedMax != activeMax) {
            commands += writeCommand(target.maxPath, requestedMax)
        }

        return commands
    }

    private fun writeCommand(path: String, value: Long): String = "echo $value > '$path'"
}
