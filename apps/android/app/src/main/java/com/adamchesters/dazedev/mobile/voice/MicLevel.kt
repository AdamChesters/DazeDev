package com.adamchesters.dazedev.mobile.voice

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Microphone level maths for the listening glow. The desktop app uses the same rules. */
internal const val METER_FLOOR_DB = -60f

/** Fall rate of the smoothed level, in meter heights per second. */
private const val LEVEL_FALL = 1.6f

internal fun rms(
    samples: FloatArray,
    count: Int = samples.size,
): Float {
    if (count <= 0) return 0f
    var sum = 0.0
    for (i in 0 until count) sum += samples[i] * samples[i]
    return sqrt(sum / count).toFloat()
}

/** Maps an amplitude to 0..1 on a decibel scale, so quiet speech still moves the meter. */
internal fun levelUnit(amplitude: Float): Float {
    if (amplitude <= 0f) return 0f
    val db = 20f * log10(amplitude)
    if (db <= METER_FLOOR_DB) return 0f
    return min(1f, (db - METER_FLOOR_DB) / -METER_FLOOR_DB)
}

/** Rises at once and falls steadily, so the display does not flicker between words. */
internal class LevelMeter {
    var level = 0f
        private set
    private var lastNanos = 0L

    fun update(
        amplitude: Float,
        nowNanos: Long,
    ): Float {
        val seconds = if (lastNanos == 0L) 0f else ((nowNanos - lastNanos) / 1e9f).coerceIn(0f, 0.25f)
        lastNanos = nowNanos
        level = max(levelUnit(amplitude), level - LEVEL_FALL * seconds)
        return level
    }

    fun reset() {
        level = 0f
        lastNanos = 0L
    }
}

/**
 * Shapes microphone samples into heights between 0 and 1 for drawing a waveform. The wave keeps
 * its real shape and its height follows [level]. [density] is how many samples each point
 * advances; the samples are laid forwards then backwards so the wave has no step in it.
 */
internal fun waveHeights(
    samples: FloatArray,
    sampleCount: Int,
    level: Float,
    out: FloatArray,
    density: Float,
) {
    val span = sampleCount - 1
    if (span < 1 || level <= 0f) {
        out.fill(0f)
        return
    }
    // Scaled so ordinary speech fills most of the height and only its sharpest peaks are clamped.
    // The floor stops silence being magnified.
    val gain = level / (2.2f * max(rms(samples, sampleCount), 10f.pow(METER_FLOOR_DB / 20f)))
    val count = out.size
    val passes = max(2, 2 * ceil(count * density / (2f * span)).toInt())
    val used = min(span.toFloat(), count * density / passes)
    for (i in 0 until count) {
        val travelled = i.toFloat() / count * passes * used
        val pass = floor(travelled / used).toInt()
        val within = travelled - pass * used
        val position = if (pass % 2 == 1) used - within else within
        val index = min(span, floor(position).toInt())
        val mix = position - index
        val sample = samples[index] * (1 - mix) + samples[min(index + 1, span)] * mix
        out[i] = min(1f, abs(sample * gain))
    }
}

/**
 * Holds the most recent microphone samples for the waveform. The capture thread writes and the
 * screen reads. At most the last 128 ms stays in memory until the next recording starts; it is
 * never stored or sent anywhere.
 */
internal class MicTap(
    size: Int = 2048,
) {
    private val ring = FloatArray(size)
    private var next = 0
    private var filled = 0

    @Synchronized
    fun write(
        samples: FloatArray,
        count: Int = samples.size,
    ) {
        val from = max(0, count - ring.size)
        for (i in from until count) {
            ring[next] = samples[i]
            next = (next + 1) % ring.size
        }
        filled = min(ring.size, filled + count - from)
    }

    /** Copies the newest samples, oldest first, into [out] and returns how many there are. */
    @Synchronized
    fun snapshot(out: FloatArray): Int {
        val count = min(filled, out.size)
        var index = (next - count + ring.size) % ring.size
        for (i in 0 until count) {
            out[i] = ring[index]
            index = (index + 1) % ring.size
        }
        return count
    }

    @Synchronized
    fun clear() {
        next = 0
        filled = 0
    }
}
