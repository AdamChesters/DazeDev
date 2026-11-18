package com.adamchesters.dazedev.mobile.voice

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// The screen edge, colours and level rules for the motes drawn round the whole screen while
// listening. Plain maths with no Android types, so it runs in JVM tests.

/** Where the travelling tertiary highlight sits on the sweep round the screen: start, peak, end. */
internal const val SHIMMER_START = 0.35f
internal const val SHIMMER_PEAK = 0.5f
internal const val SHIMMER_END = 0.65f

/** Meter levels below this, about -51 dB, count as a quiet room rather than a voice. */
internal const val NOISE_GATE = 0.15f

/**
 * The screen edge: a rectangle with its own radius at each corner, given in the order top left,
 * top right, bottom right, bottom left. It is walked clockwise from the left end of the top side,
 * alternating sides (even segments) and corner arcs (odd segments).
 */
internal class ScreenOutline(
    val width: Float,
    val height: Float,
    radii: FloatArray,
) {
    val radii = FloatArray(4) { radii[it].coerceIn(0f, min(width, height) / 2) }
    private val lengths = FloatArray(8)
    val length: Float
    private var segment = 0
    private var offset = 0f

    init {
        for (k in 0 until 8) {
            lengths[k] =
                if (k % 2 == 1) {
                    (PI.toFloat() / 2) * this.radii[arcCorner(k)]
                } else {
                    (if (k % 4 == 0) width else height) - this.radii[k / 2] - this.radii[(k / 2 + 1) % 4]
                }
        }
        length = lengths.sum()
    }

    /** Writes the point [distance] along the edge and its inward unit normal: x, y, nx, ny. */
    fun locate(
        distance: Float,
        out: FloatArray,
    ) {
        find(distance)
        val k = segment
        if (k % 2 == 0) {
            val side = k / 2
            val startX =
                when (side) {
                    0 -> radii[0]
                    1 -> width
                    2 -> width - radii[2]
                    else -> 0f
                }
            val startY =
                when (side) {
                    0 -> 0f
                    1 -> radii[1]
                    2 -> height
                    else -> height - radii[3]
                }
            val dx = SIDE_X[side]
            val dy = SIDE_Y[side]
            out[0] = startX + dx * offset
            out[1] = startY + dy * offset
            // The heading turned a quarter clockwise points into the screen.
            out[2] = -dy
            out[3] = dx
        } else {
            val corner = arcCorner(k)
            val r = radii[corner]
            val centreX = if (corner == 1 || corner == 2) width - r else r
            val centreY = if (corner == 2 || corner == 3) height - r else r
            val heading = (k - 1) * PI.toFloat() / 4 + if (r > 0f) offset / r else 0f
            val s = sin(heading)
            val c = cos(heading)
            out[0] = centreX + r * s
            out[1] = centreY - r * c
            out[2] = -s
            out[3] = c
        }
    }

    private fun find(distance: Float) {
        var d = if (length > 0f) distance - floor(distance / length) * length else 0f
        for (k in 0 until 8) {
            if (d <= lengths[k] || k == 7) {
                segment = k
                offset = d.coerceIn(0f, lengths[k])
                return
            }
            d -= lengths[k]
        }
    }

    private companion object {
        val SIDE_X = floatArrayOf(1f, 0f, -1f, 0f)
        val SIDE_Y = floatArrayOf(0f, 1f, 0f, -1f)

        /** Arc 1 is the top right corner, 3 bottom right, 5 bottom left and 7 top left. */
        fun arcCorner(k: Int) = (k + 1) / 2 % 4
    }
}

/** Where a point sits on a sweep round the centre: 0 at three o'clock, rising clockwise to 1. */
internal fun sweepPosition(
    x: Float,
    y: Float,
    centreX: Float,
    centreY: Float,
): Float {
    val turn = atan2(y - centreY, x - centreX) / (2 * PI.toFloat())
    return if (turn < 0f) turn + 1f else turn
}

/** How strongly the travelling highlight shows at [position] on the sweep, 0 to 1. */
internal fun shimmerAt(position: Float): Float {
    val t = position - floor(position)
    return when {
        t <= SHIMMER_START || t >= SHIMMER_END -> 0f
        t <= SHIMMER_PEAK -> (t - SHIMMER_START) / (SHIMMER_PEAK - SHIMMER_START)
        else -> (SHIMMER_END - t) / (SHIMMER_END - SHIMMER_PEAK)
    }
}

/**
 * How strongly the motes answer a meter level, 0 to 1. Levels under [NOISE_GATE] are the hum of a
 * quiet room, so they leave only the thin silent line; above it the 0.7 power lifts quiet speech,
 * so any voice at all visibly gives off motes.
 */
internal fun liftedLevel(level: Float): Float = ((level - NOISE_GATE) / (1 - NOISE_GATE)).coerceIn(0f, 1f).pow(0.7f)

internal fun smoothStep(t: Float): Float {
    val u = t.coerceIn(0f, 1f)
    return u * u * (3 - 2 * u)
}

/** Blends two RGB colours; the alpha of each is ignored and the result is opaque. */
internal fun mixRgb(
    from: Int,
    to: Int,
    amount: Float,
): Int {
    if (amount <= 0f) return from or OPAQUE
    return OPAQUE or mixChannel(from, to, amount, 16) or mixChannel(from, to, amount, 8) or mixChannel(from, to, amount, 0)
}

private fun mixChannel(
    from: Int,
    to: Int,
    amount: Float,
    shift: Int,
): Int {
    val a = (from shr shift) and 0xFF
    val b = (to shr shift) and 0xFF
    return (a + (b - a) * amount + 0.5f).toInt().coerceIn(0, 255) shl shift
}

internal fun withAlpha(
    rgb: Int,
    alpha: Float,
): Int = ((alpha * 255 + 0.5f).toInt().coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

private const val OPAQUE = -0x1000000
