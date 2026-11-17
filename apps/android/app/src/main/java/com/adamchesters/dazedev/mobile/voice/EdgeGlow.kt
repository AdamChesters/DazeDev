package com.adamchesters.dazedev.mobile.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// Geometry for the glow drawn round the whole screen while listening. Plain maths with no
// Android types, so it runs in JVM tests; the screen code hands the arrays to the canvas.

/** Points sit about this many dp apart round the screen edge. */
private const val POINT_SPACING_DP = 3.5f
private const val MIN_POINTS = 64

/** Enough for a tablet, and keeps every vertex number within a ShortArray index. */
internal const val MAX_POINTS = 900

/** Rows of vertices from the screen edge inward. Each neighbouring pair of rows is one band. */
internal const val GLOW_RINGS = 6

/** The row that follows the wave, where the glow ends in a soft bright rim. */
internal const val WAVE_RING = 4

/** Where the travelling tertiary highlight sits on the sweep round the screen: start, peak, end. */
internal const val SHIMMER_START = 0.35f
internal const val SHIMMER_PEAK = 0.5f
internal const val SHIMMER_END = 0.65f

/** The glow may reach this share of a corner's radius into the corner, so it never folds over. */
private const val CORNER_ROOM = 0.9f

/**
 * Along a side, the glow may reach this much deeper per unit of distance from the corner. Below 1,
 * the bands of two sides meeting at a corner can never cross.
 */
private const val SIDE_ROOM = 0.8f

/** The wave fades in over this many reaches past each corner's middle. */
private const val CORNER_FADE = 1.5f

/** The glow under the core line runs this deep before it starts to fade, in dp. */
private const val EDGE_DP = 2.5f

/** Opacity of the glow at the edge in silence, and how much a loud voice adds. */
private const val GLOW_QUIET = 0.12f
private const val GLOW_LOUD = 0.88f

/** Opacity of the rim along the wave at full level. */
private const val RIM_LOUD = 0.9f

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

    /**
     * The deepest the glow may reach at [distance] without meeting the glow from the side round
     * the corner: most of the radius on a corner, growing along each side away from it.
     */
    fun room(distance: Float): Float {
        find(distance)
        val k = segment
        val limit =
            if (k % 2 == 1) {
                CORNER_ROOM * radii[arcCorner(k)]
            } else {
                min(
                    CORNER_ROOM * radii[k / 2] + SIDE_ROOM * offset,
                    CORNER_ROOM * radii[(k / 2 + 1) % 4] + SIDE_ROOM * (lengths[k] - offset),
                )
            }
        return min(limit, min(width, height) / 2)
    }

    /**
     * How much of the wave shows at [distance]: none at the middle of each corner, rising smoothly
     * to all of it [CORNER_FADE] reaches beyond, so the bands of two sides do not crowd a corner.
     */
    fun cornerFade(
        distance: Float,
        reach: Float,
    ): Float {
        find(distance)
        val k = segment
        if (k % 2 == 1) {
            val half = lengths[k] / 2
            return ramp(abs(offset - half), half, reach)
        }
        val before = lengths[(k + 7) % 8] / 2
        val after = lengths[k + 1] / 2
        return min(ramp(offset + before, before, reach), ramp(lengths[k] - offset + after, after, reach))
    }

    private fun ramp(
        fromMiddle: Float,
        halfArc: Float,
        reach: Float,
    ): Float {
        val span = halfArc + CORNER_FADE * reach
        return if (span <= 0f) 1f else smoothStep(fromMiddle / span)
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

/**
 * The glow as rows of vertices running round the screen, from just past the edge to just past the
 * wave. Each row has its own depth and opacity, and the phone blends colours across the triangles
 * between rows, so the glow fades inward smoothly without a blur. [layout] runs when the size
 * changes; [build] runs every frame and allocates nothing.
 */
internal class EdgeGlowMesh(
    private val dp: Float,
) {
    var points = 0
        private set

    // Fixed per point while the size stays the same: position, inward normal, the deepest the glow
    // may go, how much wave shows near corners, and the place on the sweep round the centre.
    var x = FloatArray(0)
        private set
    var y = FloatArray(0)
        private set
    var nx = FloatArray(0)
        private set
    var ny = FloatArray(0)
        private set
    var room = FloatArray(0)
        private set
    var fade = FloatArray(0)
        private set
    var sweep = FloatArray(0)
        private set

    /** Height of the wave at each point, 0 to 1. The caller writes it before each [build]. */
    var shape = FloatArray(0)
        private set

    /** How far the tallest wave reaches in from the edge, in pixels. */
    var reach = 0f
        private set

    /** Two floats per vertex, row by row: every point of row 0, then row 1, and so on. */
    var vertices = FloatArray(0)
        private set

    /** One colour per vertex. Sized like [vertices], since some Android versions check it against the float count. */
    var colors = IntArray(0)
        private set

    var indices = ShortArray(0)
        private set

    val vertexFloats get() = points * GLOW_RINGS * 2
    val indexCount get() = points * (GLOW_RINGS - 1) * 6

    private val depths = FloatArray(GLOW_RINGS)
    private val alphas = FloatArray(GLOW_RINGS)
    private val probe = FloatArray(4)

    fun layout(
        width: Float,
        height: Float,
        radii: FloatArray,
    ) {
        if (width <= 0f || height <= 0f) {
            allocate(0)
            return
        }
        val outline = ScreenOutline(width, height, radii)
        reach = (min(width, height) * 0.09f).coerceIn(24f * dp, 44f * dp)
        val count = (outline.length / (POINT_SPACING_DP * dp)).roundToInt().coerceIn(MIN_POINTS, MAX_POINTS)
        if (count != points) allocate(count)
        for (i in 0 until count) {
            val at = i * outline.length / count
            outline.locate(at, probe)
            x[i] = probe[0]
            y[i] = probe[1]
            nx[i] = probe[2]
            ny[i] = probe[3]
            room[i] = outline.room(at)
            fade[i] = outline.cornerFade(at, reach)
            sweep[i] = sweepPosition(x[i], y[i], width / 2, height / 2)
        }
    }

    /**
     * Places every vertex for this frame. [level] is the smoothed microphone level, 0 to 1; depth
     * and brightness follow it. The highlight sits at [phase] round the sweep and blends [shimmer]
     * of the way to [tertiary]. [opacity] fades the whole glow in and out. Colours are packed ARGB.
     */
    fun build(
        level: Float,
        phase: Float,
        shimmer: Float,
        opacity: Float,
        primary: Int,
        tertiary: Int,
    ) {
        val n = points
        val lift = liftedLevel(level)
        val glow = (GLOW_QUIET + GLOW_LOUD * lift) * opacity
        alphas[0] = glow
        alphas[1] = glow
        alphas[2] = glow * 0.6f
        alphas[3] = glow * 0.3f
        alphas[4] = max(glow * 0.3f, RIM_LOUD * lift * opacity)
        alphas[5] = 0f
        val edge = EDGE_DP * dp
        val rim = (1.5f + lift) * dp
        // An even glow that deepens with the level; the wave rides on top of it.
        val base = edge + (3f + 16f * lift) * dp
        for (i in 0 until n) {
            val limit = room[i]
            val wave = min(base + reach * shape[i] * fade[i], limit - rim)
            depths[0] = -dp
            depths[1] = edge
            depths[3] = wave - rim
            depths[2] = edge + (depths[3] - edge) * 0.3f
            depths[4] = wave
            depths[5] = wave + rim
            val rgb = mixRgb(primary, tertiary, shimmer * shimmerAt(sweep[i] - phase))
            var deepest = depths[0]
            for (ring in 0 until GLOW_RINGS) {
                // Rows never cross: each sits at least as deep as the one before, within the room.
                val depth = min(max(depths[ring], deepest), limit)
                deepest = depth
                val v = ring * n + i
                vertices[2 * v] = x[i] + nx[i] * depth
                vertices[2 * v + 1] = y[i] + ny[i] * depth
                colors[v] = withAlpha(rgb, alphas[ring])
            }
        }
    }

    private fun allocate(count: Int) {
        points = count
        x = FloatArray(count)
        y = FloatArray(count)
        nx = FloatArray(count)
        ny = FloatArray(count)
        room = FloatArray(count)
        fade = FloatArray(count)
        sweep = FloatArray(count)
        shape = FloatArray(count)
        vertices = FloatArray(count * GLOW_RINGS * 2)
        colors = IntArray(count * GLOW_RINGS * 2)
        indices = ShortArray(count * (GLOW_RINGS - 1) * 6)
        // Two triangles join each point to the next one round, between every pair of rows.
        var k = 0
        for (ring in 0 until GLOW_RINGS - 1) {
            for (i in 0 until count) {
                val next = (i + 1) % count
                val a = (ring * count + i).toShort()
                val b = (ring * count + next).toShort()
                val c = ((ring + 1) * count + next).toShort()
                val d = ((ring + 1) * count + i).toShort()
                indices[k] = a
                indices[k + 1] = b
                indices[k + 2] = c
                indices[k + 3] = a
                indices[k + 4] = c
                indices[k + 5] = d
                k += 6
            }
        }
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
 * How strongly the glow answers a meter level, 0 to 1. Levels under [NOISE_GATE] are the hum of a
 * quiet room, so they leave the thin silent line; above it the 0.7 power lifts quiet speech, so any
 * voice at all visibly moves the glow.
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
