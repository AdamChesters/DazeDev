package com.adamchesters.dazedev.mobile.voice

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// Small glowing motes born on the screen edge while listening: more where the wave is tall, far
// more the louder the voice, a burst on each rise, and few soon after it drops. Most hop off the
// edge and land a little way along it; the rest shoot in and fade. Each one rides the wave under it
// and swells with the voice. Plain Kotlin with no Android types, so it runs in JVM tests; the
// screen code draws whatever is left in the arrays.

/** Points sit about this many dp apart round the screen edge. */
private const val POINT_SPACING_DP = 3.5f
private const val MIN_POINTS = 64

/** Enough for a tablet. Past it the points spread out, so the work each frame stays small. */
internal const val MAX_POINTS = 900

/**
 * Where [surge] is 1, each point tries this often a second to give off a mote, and succeeds as
 * often as its wave is tall.
 */
internal const val TRIES_PER_POINT = 3.71f

/** Extra tries for each point when the level jumps from silence to full, so each rise sets off a burst. */
internal const val BURST_PER_POINT = 1.29f

/** How much faster a mote ages for each unit the level has fallen since it was born. */
internal const val QUIET_HASTE = 3f

/**
 * The most motes alive at once. Their arrays are made once, at this size. Each mote is two circles
 * to draw, so a very loud voice stops adding motes here.
 */
internal const val MAX_MOTES = 1200

/** A mote lives between these many seconds while the voice holds. */
internal const val LIFE_MIN = 0.26f
internal const val LIFE_MAX = 0.64f

/** Radius of a mote in dp, its soft edge included, before it swells with the voice. */
internal const val RADIUS_MIN_DP = 1.5f
internal const val RADIUS_MAX_DP = 3f

/** Every mote's radius is this share of its own in silence, and [SWELL_LOUD] more at full level. */
internal const val SWELL_QUIET = 0.8f
internal const val SWELL_LOUD = 0.5f

/** Opacity of the brightest mote of a loud voice. Motes vary a little, and a quiet voice gives under half as much. */
internal const val MOTE_PEAK = 0.8f

/** A mote brightens over this share of its life, then fades to nothing by its end. */
private const val MOTE_RISE = 0.15f

/** The share of motes that hop out and back along the edge. The rest shoot in and fade. */
internal const val HOP_SHARE = 0.6f

/** A hopping mote stays bright until this far through its life, then fades as it lands. */
internal const val LANDING = 0.72f

/** Motes start this far in, in dp: on the dim line rather than under the screen's own edge. */
internal const val BIRTH_DP = 2f

/** How far in a mote may go: this share of the screen's short side, kept between the two dp sizes below. */
private const val REACH_SHARE = 0.097f
internal const val REACH_MIN_DP = 25f
internal const val REACH_MAX_DP = 47f

/** The furthest a hopping mote slides along the edge in its life, in dp either way. */
internal const val MAX_SLIDE_DP = 26f

/** A mote that shoots in slides only this share as far. */
internal const val SHOOT_SLIDE = 0.4f

/** Wave heights from 1 / [TALL] up count as fully tall, for how far a mote goes and how much the wave lifts it. */
internal const val TALL = 2.5f

/** How strongly a mote rides the wave under it, at least and at most, as a share of the reach. */
private const val RIDE_MIN = 0.25f
private const val RIDE_MAX = 0.75f

/** Each mote flickers between these many times in its life, each time dimming by up to [FLICKER_DEPTH]. */
internal const val FLICKERS_MIN = 2.5f
internal const val FLICKERS_MAX = 6.5f
internal const val FLICKER_DEPTH = 0.5f

/** The longest step one frame may take, in seconds, so a late frame does not make motes jump. */
private const val MAX_STEP = 0.1f

/** With animations removed, a dot sits about every this many points round the edge. */
private const val DOT_EVERY = 7

/** Radius of each still dot, in dp. */
internal const val DOT_DP = 2f

private const val TURN = 2 * PI.toFloat()

/**
 * Motes round the screen edge. [layout] places the points they are born at when the size changes.
 * [advance] runs every frame: it ages the motes, drops the finished ones and gives off new ones,
 * more where [shape] is tall and far more the louder the voice, then leaves each mote's centre,
 * radius, colour and opacity in the arrays for drawing. [hold] is the still version for "Remove
 * animations". Nothing allocates after [layout], and [random] is passed in so tests can repeat a
 * run exactly.
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

    /**
     * Height of the wave at each point, 0 to 1. The caller writes it before each [advance]: it sets
     * where motes are born and lifts the motes already out.
     */
    var shape = FloatArray(0)
        private set

    /** How far in from the edge a mote may go, in pixels. */
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

    // Each mote's course: the point it was born at, whether it hops, how far it goes in (a share of
    // the reach) and along the edge, how strongly it rides the wave, its age and life, the level it
    // was born at, its own radius, its brightness and its flicker.
    private val from = IntArray(MAX_MOTES)
    private val hops = BooleanArray(MAX_MOTES)
    private val travel = FloatArray(MAX_MOTES)
    private val drift = FloatArray(MAX_MOTES)
    private val ride = FloatArray(MAX_MOTES)
    private val age = FloatArray(MAX_MOTES)
    private val life = FloatArray(MAX_MOTES)
    private val born = FloatArray(MAX_MOTES)
    private val size = FloatArray(MAX_MOTES)
    private val brightness = FloatArray(MAX_MOTES)
    private val flickers = FloatArray(MAX_MOTES)
    private val flickerStart = FloatArray(MAX_MOTES)
    private val probe = FloatArray(4)
    private var outline = ScreenOutline(0f, 0f, FloatArray(4))

    /** Pixels along the edge from one point to the next. */
    private var spacing = 0f
    private var tries = 0f

    /** The lifted level of the last frame, so a rise can set off a burst. */
    private var heard = 0f
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
        outline = ScreenOutline(width, height, radii)
        reach = (min(width, height) * REACH_SHARE).coerceIn(REACH_MIN_DP * dp, REACH_MAX_DP * dp)
        val n = (outline.length / (POINT_SPACING_DP * dp)).roundToInt().coerceIn(MIN_POINTS, MAX_POINTS)
        if (n != points) allocate(n)
        spacing = outline.length / n
        for (i in 0 until n) {
            outline.locate(i * spacing, probe)
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
        heard = 0f
        holding = false
    }

    /**
     * Moves the motes on by [seconds]. [level] is the microphone level, 0 to 1, and should follow
     * the voice closely: below the noise gate no mote is born, and a louder voice makes far more,
     * brighter and bigger ones. A new mote takes its colour from where it is born, blended to
     * [tertiary] where the travelling highlight at [phase] is. Colours are packed ARGB.
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
        val lift = liftedLevel(level)
        // A mote born in a loud moment goes quickly once the voice drops, so the crowd thins with it.
        var m = 0
        while (m < count) {
            age[m] += step * (1 + QUIET_HASTE * max(0f, born[m] - lift))
            if (age[m] >= life[m]) recycle(m) else m++
        }
        val rise = max(0f, lift - heard)
        heard = lift
        val n = points
        if (lift <= 0f || n == 0) {
            tries = 0f
        } else {
            // Tries go to points at random, so a full set of motes favours no side of the screen.
            tries += (TRIES_PER_POINT * surge(lift) * step + BURST_PER_POINT * rise) * n
            while (tries >= 1f) {
                if (count == MAX_MOTES) {
                    // Full: the tries left over would only pile up.
                    tries = 0f
                    break
                }
                tries -= 1f
                val i = random.nextInt(n)
                if (random.nextFloat() < shape[i]) spawn(i, lift, phase, primary, tertiary)
            }
        }
        // Every mote swells with the voice, so the whole crowd beats with it.
        val swell = SWELL_QUIET + SWELL_LOUD * lift
        for (k in 0 until count) place(k, swell)
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
        val hopping = random.nextFloat() < HOP_SHARE
        from[m] = i
        hops[m] = hopping
        age[m] = 0f
        born[m] = lift
        life[m] = LIFE_MIN + (LIFE_MAX - LIFE_MIN) * random.nextFloat()
        // Further for the tall part of the wave and for a loud voice, with a wide spread.
        travel[m] = (0.3f + 0.7f * min(1f, shape[i] * TALL)) * (0.35f + 0.65f * random.nextFloat()) * (0.55f + 0.45f * lift)
        ride[m] = RIDE_MIN + (RIDE_MAX - RIDE_MIN) * random.nextFloat()
        drift[m] = (2 * random.nextFloat() - 1) * MAX_SLIDE_DP * dp * (if (hopping) 1f else SHOOT_SLIDE)
        size[m] = (RADIUS_MIN_DP + (RADIUS_MAX_DP - RADIUS_MIN_DP) * random.nextFloat()) * dp
        brightness[m] = MOTE_PEAK * (0.55f + 0.45f * random.nextFloat()) * (0.45f + 0.55f * lift)
        flickers[m] = FLICKERS_MIN + (FLICKERS_MAX - FLICKERS_MIN) * random.nextFloat()
        flickerStart[m] = TURN * random.nextFloat()
        color[m] = mixRgb(primary, tertiary, shimmerAt(sweep[i] - phase))
    }

    /**
     * In from the point it was born at while it slides along the edge. A hopping mote goes out and
     * comes back; the others start fast and slow. The wave under it lifts both further in, never past
     * the reach. It follows the edge round the corners, so it stays as far in there as anywhere else.
     */
    private fun place(
        m: Int,
        swell: Float,
    ) {
        val t = age[m] / life[m]
        val left = 1 - t
        val arc = sin(TURN / 2 * t)
        val hopping = hops[m]
        val path = if (hopping) arc else 1 - left * left * left
        val lifted = ride[m] * min(1f, shape[from[m]] * TALL) * (if (hopping) arc else left)
        val depth = BIRTH_DP * dp + (reach - BIRTH_DP * dp) * min(1f, travel[m] * path + lifted)
        outline.locate(from[m] * spacing + drift[m] * t, probe)
        moteX[m] = probe[0] + probe[2] * depth
        moteY[m] = probe[1] + probe[3] * depth
        radius[m] = size[m] * swell
        alpha[m] = brightness[m] * (if (hopping) hopFade(t) else moteFade(t)) * flicker(t, flickers[m], flickerStart[m])
    }

    /** The last mote takes the finished one's place, so the live motes stay at the front. */
    private fun recycle(m: Int) {
        val last = --count
        from[m] = from[last]
        hops[m] = hops[last]
        travel[m] = travel[last]
        drift[m] = drift[last]
        ride[m] = ride[last]
        age[m] = age[last]
        life[m] = life[last]
        born[m] = born[last]
        size[m] = size[last]
        brightness[m] = brightness[last]
        flickers[m] = flickers[last]
        flickerStart[m] = flickerStart[last]
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

/** How strongly a lifted level, 0 to 1, drives births: the square, so loud speech stands out. */
internal fun surge(level: Float): Float {
    val bounded = level.coerceIn(0f, 1f)
    return 3 * bounded * bounded
}

/** How bright a mote that shoots in is at [t] of its life, 0 to 1: up quickly from nothing, then down to nothing. */
internal fun moteFade(t: Float): Float = smoothStep(t / MOTE_RISE) * (1 - t).coerceIn(0f, 1f)

/** How bright a hopping mote is at [t] of its life, 0 to 1: up quickly, bright while it is out, then down as it lands. */
internal fun hopFade(t: Float): Float = smoothStep(t / MOTE_RISE) * ((1 - t) / (1 - LANDING)).coerceIn(0f, 1f)

/**
 * A mote's own flicker at [t] of its life: between 1 and 1 - [FLICKER_DEPTH], [times] times over
 * its life, starting [start] radians into the first.
 */
internal fun flicker(
    t: Float,
    times: Float,
    start: Float,
): Float = 1 - FLICKER_DEPTH * (0.5f + 0.5f * sin(start + t * times * TURN))
