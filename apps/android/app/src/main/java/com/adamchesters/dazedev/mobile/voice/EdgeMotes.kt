package com.adamchesters.dazedev.mobile.voice

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

// Small glowing motes born on the screen edge while listening, more of them where the voice is.
// Plain Kotlin with no Android types, so it runs in JVM tests; the screen code draws whatever is
// left in the arrays.

/** Points sit about this many dp apart round the screen edge. */
private const val POINT_SPACING_DP = 3.5f
private const val MIN_POINTS = 64

/** Enough for a tablet. Past it the points spread out, so the work each frame stays small. */
internal const val MAX_POINTS = 900

/** On average each point tries this often a second to give off a mote, and succeeds as often as its wave is tall. */
private const val TRIES_PER_POINT = 1f

/** The most motes alive at once. Their arrays are made once, at this size. */
internal const val MAX_MOTES = 512

/** A mote lives between these many seconds. */
internal const val LIFE_MIN = 0.5f
internal const val LIFE_MAX = 0.8f

/** Radius of a mote in dp, its soft edge included. */
internal const val RADIUS_MIN_DP = 1.5f
internal const val RADIUS_MAX_DP = 3f

/** Opacity of a mote at its brightest from a loud voice. A quiet voice gives half as much. */
internal const val MOTE_PEAK = 0.8f

/** A mote brightens over this share of its life, then fades to nothing by its end. */
private const val MOTE_RISE = 0.15f

/** Motes start this far in, in dp: on the dim line rather than under the screen's own edge. */
internal const val BIRTH_DP = 2f

/** The longest step one frame may take, in seconds, so a late frame does not make motes jump. */
private const val MAX_STEP = 0.1f

/** With animations removed, a dot sits about every this many points round the edge. */
private const val DOT_EVERY = 7

/** Radius of each still dot, in dp. */
internal const val DOT_DP = 2f

/**
 * Motes round the screen edge. [layout] places the points they are born at when the size changes.
 * [advance] runs every frame: it ages the motes, drops the finished ones and gives off new ones,
 * more where [shape] is tall, then leaves each mote's centre, radius, colour and opacity in the
 * arrays for drawing. [hold] is the still version for "Remove animations". Nothing allocates after
 * [layout], and [random] is passed in so tests can repeat a run exactly.
 */
internal class EdgeMotes(
    private val dp: Float,
    private val random: Random = Random.Default,
) {
    var points = 0
        private set

    // Fixed per point while the size stays the same: position, inward normal and the place on the
    // sweep round the centre.
    var x = FloatArray(0)
        private set
    var y = FloatArray(0)
        private set
    var nx = FloatArray(0)
        private set
    var ny = FloatArray(0)
        private set
    var sweep = FloatArray(0)
        private set

    /** Height of the wave at each point, 0 to 1. The caller writes it before each [advance]. */
    var shape = FloatArray(0)
        private set

    /** How far in from the edge a mote may drift, in pixels. */
    var reach = 0f
        private set

    /** How many motes there are to draw. */
    var count = 0
        private set

    // Ready to draw, per mote: centre, radius, colour (its alpha is ignored) and opacity, 0 to 1.
    val moteX = FloatArray(MAX_MOTES)
    val moteY = FloatArray(MAX_MOTES)
    val radius = FloatArray(MAX_MOTES)
    val color = IntArray(MAX_MOTES)
    val alpha = FloatArray(MAX_MOTES)

    // Each mote's course: the point it was born at, how far it drifts, its age, life and brightness.
    private val from = IntArray(MAX_MOTES)
    private val drift = FloatArray(MAX_MOTES)
    private val age = FloatArray(MAX_MOTES)
    private val life = FloatArray(MAX_MOTES)
    private val brightness = FloatArray(MAX_MOTES)
    private val probe = FloatArray(4)
    private var tries = 0f
    private var holding = false

    fun layout(
        width: Float,
        height: Float,
        radii: FloatArray,
    ) {
        // Motes belong to the old points, so a new size starts with none.
        clear()
        if (width <= 0f || height <= 0f) {
            allocate(0)
            return
        }
        val outline = ScreenOutline(width, height, radii)
        reach = (min(width, height) * 0.054f).coerceIn(14f * dp, 26f * dp)
        val n = (outline.length / (POINT_SPACING_DP * dp)).roundToInt().coerceIn(MIN_POINTS, MAX_POINTS)
        if (n != points) allocate(n)
        for (i in 0 until n) {
            outline.locate(i * outline.length / n, probe)
            x[i] = probe[0]
            y[i] = probe[1]
            nx[i] = probe[2]
            ny[i] = probe[3]
            sweep[i] = sweepPosition(x[i], y[i], width / 2, height / 2)
        }
    }

    fun clear() {
        count = 0
        tries = 0f
        holding = false
    }

    /**
     * Moves the motes on by [seconds]. [level] is the smoothed microphone level, 0 to 1: below the
     * noise gate no mote is born, and a louder voice makes brighter ones. A new mote takes its colour
     * from where it is born, blended to [tertiary] where the travelling highlight at [phase] is.
     * Colours are packed ARGB.
     */
    fun advance(
        seconds: Float,
        level: Float,
        phase: Float,
        primary: Int,
        tertiary: Int,
    ) {
        if (holding) clear()
        val step = seconds.coerceIn(0f, MAX_STEP)
        var m = 0
        while (m < count) {
            age[m] += step
            if (age[m] >= life[m]) recycle(m) else m++
        }
        val lift = liftedLevel(level)
        val n = points
        if (lift <= 0f || n == 0) {
            tries = 0f
        } else {
            // Tries go to points at random, so a full set of motes favours no side of the screen.
            tries += TRIES_PER_POINT * n * step
            while (tries >= 1f) {
                tries -= 1f
                val i = random.nextInt(n)
                if (count < MAX_MOTES && random.nextFloat() < shape[i]) spawn(i, lift, phase, primary, tertiary)
            }
        }
        for (k in 0 until count) place(k)
    }

    /**
     * The still version: evenly spaced dots in [primary] whose brightness follows [level]. They are
     * always in the same places, so nothing ever moves.
     */
    fun hold(
        level: Float,
        primary: Int,
    ) {
        holding = true
        tries = 0f
        val bright = MOTE_PEAK * liftedLevel(level)
        // In by its own radius, so the screen's rounded corners never clip a dot.
        val depth = (BIRTH_DP + DOT_DP) * dp
        val dots = min(points / DOT_EVERY, MAX_MOTES)
        for (k in 0 until dots) {
            val i = k * points / dots
            moteX[k] = x[i] + nx[i] * depth
            moteY[k] = y[i] + ny[i] * depth
            radius[k] = DOT_DP * dp
            color[k] = primary
            alpha[k] = bright
        }
        count = dots
    }

    private fun spawn(
        i: Int,
        lift: Float,
        phase: Float,
        primary: Int,
        tertiary: Int,
    ) {
        val m = count++
        from[m] = i
        age[m] = 0f
        life[m] = LIFE_MIN + (LIFE_MAX - LIFE_MIN) * random.nextFloat()
        drift[m] = (reach - BIRTH_DP * dp) * (0.5f + 0.5f * random.nextFloat())
        radius[m] = (RADIUS_MIN_DP + (RADIUS_MAX_DP - RADIUS_MIN_DP) * random.nextFloat()) * dp
        brightness[m] = MOTE_PEAK * (0.5f + 0.5f * lift)
        color[m] = mixRgb(primary, tertiary, shimmerAt(sweep[i] - phase))
    }

    /** Straight in from the point it was born at, quick at first and slowing as it fades. */
    private fun place(m: Int) {
        val i = from[m]
        val t = age[m] / life[m]
        val depth = BIRTH_DP * dp + drift[m] * (1 - (1 - t) * (1 - t))
        moteX[m] = x[i] + nx[i] * depth
        moteY[m] = y[i] + ny[i] * depth
        alpha[m] = brightness[m] * moteFade(t)
    }

    /** The last mote takes the finished one's place, so the live motes stay at the front. */
    private fun recycle(m: Int) {
        val last = --count
        from[m] = from[last]
        drift[m] = drift[last]
        age[m] = age[last]
        life[m] = life[last]
        brightness[m] = brightness[last]
        radius[m] = radius[last]
        color[m] = color[last]
    }

    private fun allocate(count: Int) {
        points = count
        x = FloatArray(count)
        y = FloatArray(count)
        nx = FloatArray(count)
        ny = FloatArray(count)
        sweep = FloatArray(count)
        shape = FloatArray(count)
    }
}

/** How bright a mote is at [t] of its life, 0 to 1: up quickly from nothing, then down to nothing. */
internal fun moteFade(t: Float): Float = smoothStep(t / MOTE_RISE) * (1 - t).coerceIn(0f, 1f)
