package com.localmediatools.core

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import com.localmediatools.i18n.N_
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trn
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Global "Export workload" setting (10–100 %). It is not a CPU-percentage limiter; it shapes how
 * aggressively exports use the phone:
 *  - how many files are processed in parallel,
 *  - the priority of worker threads,
 *  - short pauses between processing steps (duty cycling) to keep the phone cool and responsive,
 *  - how much free memory one export may claim,
 *  - the operating rate requested from hardware video codecs.
 */
object Workload {
    const val MIN = 10
    const val MAX = 100

    enum class Preset(private val labelEn: String, val percent: Int, private val summaryEn: String) {
        EFFICIENT(N_("Efficient"), 25, N_("Gentle on battery and heat; exports take longer.")),
        BALANCED(N_("Balanced"), 60, N_("Good speed while the phone stays responsive.")),
        FAST(N_("Fast"), 100, N_("Uses the device fully; best when plugged in."));

        val label: String get() = tr(labelEn)
        val summary: String get() = tr(summaryEn)
    }

    private const val PREFS = "settings"
    private const val KEY = "workload_percent"
    private val state = MutableStateFlow(Preset.BALANCED.percent)
    val percent: StateFlow<Int> get() = state

    fun init(ctx: Context) {
        state.value = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, Preset.BALANCED.percent).coerceIn(MIN, MAX)
    }

    fun set(ctx: Context, value: Int) {
        val v = value.coerceIn(MIN, MAX)
        state.value = v
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, v).apply()
    }

    fun profile(): WorkloadProfile = WorkloadProfile(state.value)

    fun presetFor(percent: Int): Preset? = Preset.entries.firstOrNull { it.percent == percent }

    fun describe(percent: Int): String = presetFor(percent)?.label ?: when {
        percent < 40 -> tr("Custom · light")
        percent < 80 -> tr("Custom · moderate")
        else -> tr("Custom · high")
    }
}

class WorkloadProfile(val percent: Int) {
    /** 0..1 position within the adjustable range. */
    val level: Double = ((percent - Workload.MIN).toDouble() / (Workload.MAX - Workload.MIN)).coerceIn(0.0, 1.0)

    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /** Files processed concurrently by image tools (always leaves one core for the UI). */
    val parallelism: Int = (1 + ((cores - 2).coerceAtLeast(0) * level.pow(1.4))).roundToInt().coerceIn(1, maxOf(1, cores - 1))

    /** Linux nice value for worker threads: 19 (lowest) .. 0 (normal). */
    val threadPriority: Int = (19 - 19 * level).roundToInt().coerceIn(Process.THREAD_PRIORITY_DEFAULT, Process.THREAD_PRIORITY_LOWEST)

    /** Fraction of wall time spent working; the rest is spent pausing between steps. */
    val dutyCycle: Double = 0.45 + 0.55 * level

    /** Share of currently free RAM that an export may use for pixel buffers. */
    val memoryShare: Double = 0.30 + 0.20 * level

    /** Hint for MediaCodec KEY_OPERATING_RATE (frames per second); null lets the codec decide. */
    val codecOperatingRate: Int? = when {
        level >= 0.95 -> Short.MAX_VALUE.toInt() // "as fast as possible"
        level >= 0.5 -> 120
        else -> null
    }

    val label: String get() = Workload.describe(percent)

    fun explain(): String {
        val par = trn(parallelism, "{0} file at a time", "up to {0} files at once")
        val pause = if (dutyCycle >= 0.99) tr("no pauses") else tr("short pauses (~{0}% of the time)", ((1 - dutyCycle) * 100).roundToInt())
        return tr("Images: {0} · {1} · {2}", par, if (threadPriority >= 10) tr("background priority") else tr("normal priority"), pause)
    }
}

/** Memory available for decoded pixels, based on the current free RAM and the workload. */
object MemoryBudget {
    private const val MB = 1024L * 1024L

    fun bitmapBytes(ctx: Context, workload: WorkloadProfile, sharedBy: Int = 1): Long {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val headroom = (info.availMem - info.threshold).coerceAtLeast(0)
        // Never plan for more than a quarter of the device's total RAM in a single export.
        val cap = minOf(info.totalMem / 4, 1536 * MB)
        val budget = (headroom * workload.memoryShare).toLong().coerceAtMost(cap)
        return (budget / sharedBy.coerceAtLeast(1)).coerceAtLeast(48 * MB)
    }

    fun totalRam(ctx: Context): Long {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }

    fun isLowRamDevice(ctx: Context): Boolean =
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
}
