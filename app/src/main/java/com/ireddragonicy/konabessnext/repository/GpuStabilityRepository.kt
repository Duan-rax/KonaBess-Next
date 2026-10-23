package com.ireddragonicy.konabessnext.repository

import android.util.Log
import com.ireddragonicy.konabessnext.core.model.AppError
import com.ireddragonicy.konabessnext.core.model.DomainResult
import com.ireddragonicy.konabessnext.model.gpu.FailureReason
import com.ireddragonicy.konabessnext.model.gpu.FreqPointResult
import com.ireddragonicy.konabessnext.model.gpu.GpuSample
import com.ireddragonicy.konabessnext.model.gpu.GpuStabilityUiState
import com.ireddragonicy.konabessnext.model.gpu.StabilityStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the runtime side of the GPU stability test:
 *  - reads the kernel-reported GPU frequency list (used as the candidate set);
 *  - pins the GPU to a single clock using the available devfreq or KGSL controls;
 *  - samples cur_freq / busy / temperature at ~1 Hz while the renderer is active;
 *  - restores the original governor/min/max on completion, abort or failure.
 *
 * Two modes are supported:
 *  - `runStabilityTest(state)` — root-required per-frequency sweep with locking.
 *  - `runFreeStressTest(state)` — no locking, no per-point cadence; samples
 *    cur_freq / busy / temp at ~1 Hz until the caller invokes [cancelTest].
 *
 * Both modes share the same underlying sample loop and [TestEvent] surface so
 * the UI layer can drive either mode through the same `events` flow.
 */
@Singleton
class GpuStabilityRepository @Inject constructor(
    private val shellExecutor: ShellExecutor,
) {

    companion object {
        private const val TAG = "GpuStabilityRepo"

        /** Sysfs paths probed for the active Adreno frequency table. */
        val PROBE_COMMANDS: List<String> = RuntimeGpuFrequencyProbe.candidates.map { it.command }

        /** Kept public for diagnostics and compatibility with existing callers. */
        val GOV_NODE_CANDIDATES: List<String> = RuntimeGpuFrequencyLock.devfreqBasePatterns

        /** Cat paths used to read GPU samples. We try a list of candidate paths
         *  because different kernels expose them in slightly different places. */
        val CUR_FREQ_PATHS: List<String> = listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/kgsl/kgsl-3d0/clock_mhz",
            "/sys/class/kgsl/kgsl-3d0/clock",
            "/sys/class/kgsl/kgsl-3d0/devfreq/curr_freq",
            "${RuntimeGpuFrequencyProbe.CLASS_DEVFREQ_GPU}/cur_freq",
        ) + RuntimeGpuFrequencyProbe.platformKgslRoots.flatMap { root ->
            listOf("$root/gpuclk", "$root/clock_mhz")
        }
        val BUSY_PATHS: List<String> = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/busy",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy",
            "/sys/class/kgsl/kgsl-3d0/devfreq/busy",
            "/sys/devices/platform/soc/soc:qcom,kgsl-3d0/devfreq/busy",
            "/sys/devices/platform/soc/*.qcom,kgsl-3d0/devfreq/busy",
            "${RuntimeGpuFrequencyProbe.CLASS_DEVFREQ_GPU}/busy",
        ) + RuntimeGpuFrequencyProbe.platformKgslRoots.map { "$it/gpu_busy_percentage" }
        val TEMP_FALLBACK_PATH = "/sys/class/kgsl/kgsl-3d0/temp"
        val THERMAL_ZONE_CMDS: List<String> = listOf(
            "cat /sys/class/thermal/thermal_zone0/temp",
            "cat /sys/class/thermal/thermal_zone1/temp",
            "cat /sys/class/thermal/thermal_zone2/temp",
        )

        /** Consecutive seconds the GPU has to remain under the threshold before declaring a throttle failure. */
        const val DEFAULT_THROTTLE_WINDOW_SEC = 3

        /** Consecutive seconds with gpu_busy == 0 before declaring that the stress failed to engage. */
        const val DEFAULT_NO_LOAD_WINDOW_SEC = 5
    }

    /** Snapshot of the values we overwrote, used to restore on stop. */
    private data class OriginalLimits(
        val target: GpuFrequencyLockTarget,
        val governor: String?,
        val limits: Pair<Long, Long>,
    )

    private val _events = MutableSharedFlow<TestEvent>(extraBufferCapacity = 16)
    private val _running = MutableStateFlow(false)
    private var lastSeenRenderError: Int? = null

    @Volatile private var resolvedCurFreqPath: String? = null
    @Volatile private var resolvedBusyPath: String? = null
    @Volatile private var resolvedTempCommand: String? = null

    val events: Flow<TestEvent> = _events.asSharedFlow()
    val isRunning: Boolean get() = _running.value

    /**
     * Read the active GPU frequency list. Reuses the same probe fallback chain as
     * [DeviceRepository.getRunTimeGpuFrequencies].
     */
    suspend fun loadActiveFrequencies(): DomainResult<List<Long>> = withContext(Dispatchers.IO) {
        if (!shellExecutor.isRootMode) {
            return@withContext DomainResult.Failure(AppError.RootAccessError())
        }
        for (candidate in RuntimeGpuFrequencyProbe.candidates) {
            val output = try {
                shellExecutor.execForOutput(candidate.command)
            } catch (_: SecurityException) {
                continue
            } catch (_: Exception) {
                continue
            }
            val parsed = RuntimeGpuFrequencyProbe.parse(output, candidate)
            if (parsed.isNotEmpty()) {
                return@withContext DomainResult.Success(parsed.sorted())
            }
        }
        DomainResult.Failure(AppError.IoError("Could not determine runtime GPU frequencies."))
    }

    /**
     * Set a render-error code from the GL thread. Used by [GpuStressRenderer] to flag
     * GPU crashes / context loss events. Sampled in the next iteration of [sampleLoop].
     */
    fun reportRenderError(code: Int) {
        lastSeenRenderError = code
    }

    fun clearRenderError() {
        lastSeenRenderError = null
    }

    /**
     * Run the full stability sweep sequentially across [activeFrequenciesHz]. Each
     * frequency is locked for [GpuStabilityUiState.durationPerPointSec] seconds.
     *
     * Requires root mode. Callers without root should use [runFreeStressTest].
     *
     * Events are emitted through the returned [Flow]; [events] mirrors the same
     * stream for any second observer (kept for backwards compatibility / debugging).
     * The flow completes when the sweep ends (success, failure or abort).
     */
    suspend fun runStabilityTest(state: GpuStabilityUiState): Flow<TestEvent> = flow<TestEvent> {
        suspend fun publish(event: TestEvent) {
            // Best-effort mirror on the side-channel SharedFlow for any second
            // observer (e.g. logging). Errors there don't affect the main flow.
            runCatching { _events.emit(event) }
            this@flow.emit(event)
        }
        if (!shellExecutor.isRootMode) {
            publish(TestEvent.Failed(FailureReason.RootRequired))
            return@flow
        }
        val candidates = state.activeFrequenciesHz
        if (candidates.isEmpty()) {
            publish(TestEvent.Failed(FailureReason.NoFrequencies))
            return@flow
        }
        val lockTarget = RuntimeGpuFrequencyLock.resolve(shellExecutor) ?: run {
            publish(TestEvent.Failed(FailureReason.DevfreqNodeMissing))
            return@flow
        }
        val original = readOriginalLimits(lockTarget) ?: run {
            publish(TestEvent.Failed(FailureReason.WriteFreqRejected))
            return@flow
        }
        _running.value = true
        var failure: FailureReason? = null
        val stopped: Boolean
        val restored: Boolean
        try {
            for (target in candidates) {
                if (!_running.value) break
                val pointResult = testFrequency(
                    lockTarget = original.target,
                    targetFreqHz = target,
                    durationSec = state.durationPerPointSec,
                    throttleRatioPct = state.throttlingRatioPct,
                    onSample = { sample -> publish(TestEvent.SampleCollected(sample)) },
                )
                publish(TestEvent.PointFinished(pointResult))
                if (!pointResult.passed) {
                    failure = pointResult.failureReason ?: FailureReason.Unknown
                    break
                }
            }
            stopped = !_running.value
        } finally {
            restored = withContext(NonCancellable) { restoreLimits(original) }
            _running.value = false
        }
        publish(
            when {
                !restored -> TestEvent.Failed(FailureReason.WriteFreqRejected)
                stopped -> TestEvent.Aborted
                failure != null -> TestEvent.Failed(failure)
                else -> TestEvent.AllCompleted
            },
        )
    }.flowOn(Dispatchers.IO)

    /**
     * Free-run stress test. Does **not** require root: it never writes to sysfs,
     * never iterates a candidate list, never locks the GPU to a single frequency.
     *
     * The flow runs until [cancelTest] is invoked (or the calling coroutine is
     * cancelled). It emits [TestEvent.SampleCollected] at ~1 Hz and finally
     * [TestEvent.Aborted] when stopped. No [TestEvent.PointFinished] /
     * [TestEvent.AllCompleted] / [TestEvent.Failed] events are emitted (the only
     * exception is a fatal renderer error, which surfaces through the same
     * channel). The caller (typically the ViewModel) is responsible for
     * transitioning state to Idle when the flow completes normally.
     */
    suspend fun runFreeStressTest(state: GpuStabilityUiState): Flow<TestEvent> = flow<TestEvent> {
        suspend fun publish(event: TestEvent) {
            runCatching { _events.emit(event) }
            this@flow.emit(event)
        }
        _running.value = true
        try {
            while (_running.value) {
                val renderErr = lastSeenRenderError
                if (renderErr != null && renderErr != 0) {
                    publish(TestEvent.Failed(
                        FailureReason.RendererGlError("0x${Integer.toHexString(renderErr)}")
                    ))
                    break
                }
                delay(1_000L)
            }
            if (_running.value) {
                // Loop exited because of a renderer failure, not a user cancel.
                // Already published Failed above.
            } else {
                publish(TestEvent.Aborted)
            }
        } catch (ce: CancellationException) {
            publish(TestEvent.Aborted)
            throw ce
        } finally {
            _running.value = false
            clearRenderError()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Cancel an in-progress sweep. The current per-frequency sample loop will exit on
     * the next iteration, the sweep [Flow] terminates, and the original governor /
     * min / max values are restored.
     */
    suspend fun cancelTest() = withContext(Dispatchers.IO) {
        _running.value = false
    }

    /**
     * Try to lock the GPU at [targetFreqHz]. Returns true on success. Used by callers
     * (and tests) that want to drive a single frequency manually.
     */
    internal suspend fun pinFrequency(
        lockTarget: GpuFrequencyLockTarget,
        targetFreqHz: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!shellExecutor.isRootMode) return@withContext false
        val targetValue = lockTarget.rawValueForHz(targetFreqHz) ?: return@withContext false
        val current = readRawLimits(lockTarget) ?: return@withContext false

        // Min == max is sufficient to pin devfreq. Switching governor is a
        // best-effort optimization because several vendor kernels expose a
        // read-only or restricted governor while still allowing range writes.
        lockTarget.governorPath?.let {
            runCatching { shellExecutor.execAndCheck("echo performance > '$it'") }
        }
        val commands = RuntimeGpuFrequencyLock.buildTransitionCommands(
            target = lockTarget,
            currentMin = current.first,
            currentMax = current.second,
            requestedMin = targetValue,
            requestedMax = targetValue,
        )
        if (commands.isEmpty() && current != (targetValue to targetValue)) return@withContext false
        if (commands.isNotEmpty() && !shellExecutor.execAndCheck(commands.joinToString(" && "))) {
            return@withContext false
        }

        readRawLimits(lockTarget) == (targetValue to targetValue)
    }

    /**
     * Best-effort restore of the limits captured in [original]. Always called in a
     * `NonCancellable` context, even when the sweep is cancelled, so we don't leave
     * the GPU pinned to a single frequency.
     */
    private suspend fun restoreLimits(original: OriginalLimits): Boolean {
        if (!shellExecutor.isRootMode) return false
        try {
            val (requestedMin, requestedMax) = original.limits
            val current = readRawLimits(original.target)
            if (current != null) {
                val commands = RuntimeGpuFrequencyLock.buildTransitionCommands(
                    target = original.target,
                    currentMin = current.first,
                    currentMax = current.second,
                    requestedMin = requestedMin,
                    requestedMax = requestedMax,
                )
                if (commands.isNotEmpty()) shellExecutor.execAndCheck(commands.joinToString(" && "))
            }
            val governorPath = original.target.governorPath
            val governor = original.governor
            val governorRestored = if (governorPath != null && governor != null) {
                if (readGovernor(governorPath) != governor) {
                    shellExecutor.execAndCheck("echo '$governor' > '$governorPath'")
                }
                readGovernor(governorPath) == governor
            } else {
                true
            }
            return governorRestored && readRawLimits(original.target) == original.limits
        } catch (e: Exception) {
            Log.w(TAG, "restoreLimits failed: ${e.message}")
            return false
        }
    }

    private suspend fun testFrequency(
        lockTarget: GpuFrequencyLockTarget,
        targetFreqHz: Long,
        durationSec: Int,
        throttleRatioPct: Int,
        onSample: suspend (GpuSample) -> Unit = {},
    ): FreqPointResult {
        val pinned = pinFrequency(lockTarget, targetFreqHz)
        if (!pinned) {
            return FreqPointResult(
                targetFreqHz = targetFreqHz,
                passed = false,
                samples = emptyList(),
                failureReason = FailureReason.WriteFreqRejected,
                durationSec = 0,
            )
        }
        // Errors observed during the previous point should not bleed into this one.
        // (We clear at the *end* of the point instead, so the caller can pre-set
        // an error code in tests without having to coordinate with timing.)
        val samples = mutableListOf<GpuSample>()
        var lastSeenErrorInPoint: Int? = null
        var throttleStreak = 0
        var noLoadStreak = 0
        val startMs = System.currentTimeMillis()
        var failureReason: FailureReason? = null
        val tickMs = startMs + 1000L
        for (sec in 0 until durationSec) {
            if (!_running.value) {
                failureReason = FailureReason.AbortedByUser
                break
            }
            val now = System.currentTimeMillis()
            val sleepMs = (tickMs + sec * 1000L) - now
            if (sleepMs > 0) delay(sleepMs)
            val sample = readSample(targetFreqHz)
            samples += sample
            // Stream this sample up to the caller (the ViewModel appends it to
            // currentSamples so the chart updates in real time).
            onSample(sample)
            val renderErr = lastSeenRenderError
            if (renderErr != null && renderErr != 0) {
                lastSeenErrorInPoint = renderErr
                failureReason = FailureReason.RendererGlError("0x${Integer.toHexString(renderErr)}")
                break
            }
            val ratio = if (targetFreqHz > 0L) sample.curFreqHz.toDouble() / targetFreqHz else 0.0
            if (sample.curFreqHz > 0 && ratio * 100 < throttleRatioPct) {
                throttleStreak++
                if (throttleStreak >= DEFAULT_THROTTLE_WINDOW_SEC) {
                    failureReason = FailureReason.ThrottledBelow(throttleRatioPct)
                    break
                }
            } else {
                throttleStreak = 0
            }
            if (sample.gpuBusyPct == 0) {
                noLoadStreak++
                if (noLoadStreak >= DEFAULT_NO_LOAD_WINDOW_SEC) {
                    failureReason = FailureReason.NoLoad
                    break
                }
            } else {
                noLoadStreak = 0
            }
        }
        val passed = failureReason == null
        // Clear at the end of the point so the next one starts fresh; this also
        // unblocks any caller that pre-set the error before this point began.
        clearRenderError()
        return FreqPointResult(
            targetFreqHz = targetFreqHz,
            passed = passed,
            samples = samples,
            failureReason = failureReason,
            durationSec = ((System.currentTimeMillis() - startMs) / 1000L).toInt(),
        )
    }

    private suspend fun readSample(targetFreqHz: Long): GpuSample {
        val curFreqPath = resolvedCurFreqPath ?: resolveFirstWorkingPath(CUR_FREQ_PATHS, asLong = true)
            ?.also { resolvedCurFreqPath = it }
        val busyPath = resolvedBusyPath ?: resolveFirstWorkingPath(BUSY_PATHS, asLong = false)
            ?.also { resolvedBusyPath = it }
        val tempCmd = resolvedTempCommand ?: pickTemperatureCommand()?.also { resolvedTempCommand = it }
        // Keep the three fields aligned when a node is unreadable or absent.
        val raw = shellExecutor.execForOutput(
            listOf(curFreqPath?.let { "cat '$it'" }, busyPath?.let { "cat '$it'" }, tempCmd)
                .joinToString("; ") { command ->
                    if (command == null) "echo unavailable" else "$command 2>/dev/null || echo unavailable"
                },
        )

        val curFreq = parseCurrentGpuFrequencyHz(raw.getOrNull(0), curFreqPath)
        val busy = parsePercentInt(raw.getOrNull(1)?.trim())
        val tempRaw = raw.getOrNull(2)?.trim()?.toLongOrNull()
        val temp = if (tempRaw != null && tempRaw > 0L) {
            if (tempRaw > 1000L) tempRaw / 1000f else tempRaw.toFloat()
        } else null

        return GpuSample(
            timestampMs = System.currentTimeMillis(),
            targetFreqHz = targetFreqHz,
            curFreqHz = curFreq,
            gpuBusyPct = busy,
            temperatureC = temp,
            throttled = curFreq in 1..(targetFreqHz - 1),
            renderErrorCode = lastSeenRenderError?.takeIf { it != 0 },
        )
    }

    private suspend fun resolveFirstWorkingPath(paths: List<String>, asLong: Boolean): String? {
        for (pattern in paths) {
            val candidates = if (pattern.contains('*')) {
                shellExecutor.execForOutput(
                    "for f in $pattern; do [ -r \"${'$'}f\" ] && printf '%s\\n' \"${'$'}f\"; done"
                )
            } else {
                listOf(pattern)
            }
            for (path in candidates) {
                val raw = shellExecutor.execForOutput("cat '$path' 2>/dev/null").firstOrNull()
                val readable = if (asLong) parseCurrentGpuFrequencyHz(raw, path) > 0L
                    else parsePercentInt(raw) != null
                if (readable) return path
            }
        }
        return null
    }

    /** Pick the first working temperature path. Kept separate from [resolveFirstWorkingPath]
     *  because TEMP_FALLBACK_PATH is a fixed path and THERMAL_ZONE_CMDS are shell commands. */
    private suspend fun pickTemperatureCommand(): String? {
        for (cmd in listOf("cat '$TEMP_FALLBACK_PATH'") + THERMAL_ZONE_CMDS) {
            val value = shellExecutor.execForOutput("$cmd 2>/dev/null").firstOrNull()?.trim()?.toLongOrNull()
            if (value != null && value > 0L) return cmd
        }
        return null
    }

    private suspend fun readRawLimits(target: GpuFrequencyLockTarget): Pair<Long, Long>? {
        val minValue = shellExecutor.execForOutput("cat '${target.minPath}'")
            .firstOrNull()?.trim()?.toLongOrNull()
        val maxValue = shellExecutor.execForOutput("cat '${target.maxPath}'")
            .firstOrNull()?.trim()?.toLongOrNull()
        return if (minValue != null && maxValue != null && minValue > 0L && minValue <= maxValue) {
            minValue to maxValue
        } else {
            null
        }
    }

    private suspend fun readGovernor(path: String): String? =
        shellExecutor.execForOutput("cat '$path'").firstOrNull()?.trim()
            ?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]+")) }

    private suspend fun readOriginalLimits(target: GpuFrequencyLockTarget): OriginalLimits? {
        val limits = readRawLimits(target) ?: return null
        val governor = target.governorPath?.let { readGovernor(it) }
        return OriginalLimits(
            target = if (governor == null) target.copy(governorPath = null) else target,
            governor = governor,
            limits = limits,
        )
    }

    /**
     * Events emitted by [runStabilityTest]. The UI / ViewModel turns these into [StabilityStatus] transitions.
     */
    sealed class TestEvent {
        data class PointFinished(val result: FreqPointResult) : TestEvent()
        data class SampleCollected(val sample: GpuSample) : TestEvent()
        data class Failed(val reason: FailureReason) : TestEvent()
        data object AllCompleted : TestEvent()
        data object Aborted : TestEvent()
    }
}

internal fun parseCurrentGpuFrequencyHz(raw: String?, path: String?): Long {
    val value = raw?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: return 0L
    return when {
        path?.endsWith("/clock_mhz") == true ->
            runCatching { Math.multiplyExact(value, 1_000_000L) }.getOrDefault(0L)
        path?.endsWith("/gpuclk") != true && value in 10_000L until 10_000_000L -> value * 1_000L
        else -> value
    }
}

/**
 * Parse an integer in the 0..100 range from a sysfs line, tolerating
 * vendor-specific decoration. Examples that all parse to 99:
 *  - "99"
 *  - "99 %"
 *  - " 99%"
 *  - "99%"
 *  - "99.5"  (decimal point stripped, integer part used)
 *  - "99.5%" (decimal + percent sign)
 * Returns null for any input that doesn't contain a leading 0..100 integer.
 */
internal fun parsePercentInt(raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    val firstToken = raw.trim().split(Regex("\\s+")).firstOrNull() ?: return null
    val digits = firstToken.trimEnd('%').trim()
    // Strip a trailing decimal fraction so "99.5" / "99.5%" map to 99.
    val intPart = digits.substringBefore('.')
    return intPart.toIntOrNull()?.takeIf { it in 0..100 }
}
