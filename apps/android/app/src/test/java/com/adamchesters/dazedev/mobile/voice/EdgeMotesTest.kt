package com.adamchesters.dazedev.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random

class EdgeMotesTest {
    private val primary = 0xFF39BDD7.toInt()
    private val tertiary = 0xFFE8BA70.toInt()
    private val dp = 2.625f

    private fun corners(radius: Float) = floatArrayOf(radius, radius, radius, radius)

    private fun phone(
        seed: Int = 1,
        radius: Float = 110f,
    ) = EdgeMotes(dp, Random(seed)).apply { layout(1080f, 2400f, corners(radius)) }

    /** Runs [seconds] of frames at 60 a second, calling [check] after each one. */
    private fun EdgeMotes.run(
        seconds: Float,
        level: Float,
        phase: Float = 0f,
        check: (EdgeMotes) -> Unit = {},
    ) {
        repeat((seconds * 60).roundToInt()) {
            advance(1 / 60f, level, phase, primary, tertiary)
            check(this)
        }
    }

    /** How far in mote [m] is on the phone with 110 px corners, as a share of the way from its birth depth to the reach. */
    private fun EdgeMotes.share(m: Int): Float {
        val depth = -edgeDistance(moteX[m], moteY[m], 1080f, 2400f, corners(110f))
        return (depth - BIRTH_DP * dp) / (reach - BIRTH_DP * dp)
    }

    /** The point halfway along the top edge, where a mote's depth is simply its y. */
    private fun EdgeMotes.topMiddle() = (0 until points).minBy { abs(x[it] - 540f) + y[it] }

    /** How many times [values] turn from rising to falling or back. */
    private fun turns(values: List<Float>): Int {
        var turns = 0
        var rising: Boolean? = null
        for (k in 1 until values.size) {
            if (values[k] == values[k - 1]) continue
            val up = values[k] > values[k - 1]
            if (rising != null && up != rising) turns++
            rising = up
        }
        return turns
    }

    @Test
    fun `points sit round the edge and motes reach a short way in`() {
        val motes = phone()
        assertTrue("${motes.points} points", motes.points in 600..MAX_POINTS)
        for (i in 0 until motes.points) {
            assertEquals(0f, edgeDistance(motes.x[i], motes.y[i], 1080f, 2400f, corners(110f)), 0.01f)
        }
        // About 40dp on this phone.
        assertEquals(1080f * 0.097f, motes.reach, 0.01f)
        val tablet = EdgeMotes(2f).apply { layout(2560f, 1600f, corners(0f)) }
        assertEquals(MAX_POINTS, tablet.points)
        assertEquals(REACH_MAX_DP * 2f, tablet.reach, 0.001f)
        val small = EdgeMotes(1f).apply { layout(200f, 300f, corners(0f)) }
        assertEquals(REACH_MIN_DP, small.reach, 0.001f)
        val empty = EdgeMotes(2f).apply { layout(0f, 900f, corners(0f)) }
        assertEquals(0, empty.points)
        empty.advance(0.1f, 1f, 0f, primary, tertiary)
        assertEquals(0, empty.count)
    }

    @Test
    fun `no motes are born in silence or in the hum of a quiet room`() {
        for (level in listOf(0f, NOISE_GATE * 0.9f)) {
            val motes = phone()
            motes.shape.fill(1f)
            motes.run(2f, level) { assertEquals(0, it.count) }
        }
    }

    @Test
    fun `births follow the square of the level`() {
        assertEquals(0f, surge(0f), 0f)
        assertEquals(3f, surge(1f), 0f)
        assertEquals(1f, surge(0.58f), 0.01f)
        assertEquals(0.75f, surge(0.5f), 0.0001f)
        assertEquals(0f, surge(-1f), 0f)
        assertEquals(3f, surge(4f), 0f)
    }

    @Test
    fun `a louder voice gives off far more and brighter motes`() {
        val counts = mutableListOf<Double>()
        val brightest = mutableListOf<Float>()
        val levels = listOf(0.45f, 0.65f, 0.9f)
        for (level in levels) {
            val motes = phone()
            // A low wave everywhere, so the motes never fill every place.
            motes.shape.fill(0.3f)
            motes.run(1f, level)
            val seen = mutableListOf<Int>()
            var bright = 0f
            motes.run(2f, level) {
                seen += it.count
                for (m in 0 until it.count) bright = maxOf(bright, it.alpha[m])
            }
            // While the level holds: tries a second, times the share that succeed, times the mean life.
            val expected = TRIES_PER_POINT * surge(liftedLevel(level)) * motes.points * 0.3f * (LIFE_MIN + LIFE_MAX) / 2
            assertEquals("level $level", expected.toDouble(), seen.average(), 0.15 * expected)
            counts += seen.average()
            brightest += bright
        }
        // Twice the lifted level gives about four times the motes.
        val square = (liftedLevel(levels[2]) / liftedLevel(levels[0])).let { it * it }
        assertEquals(square.toDouble(), counts[2] / counts[0], 0.2 * square)
        assertTrue("motes $counts", counts[1] > 1.5 * counts[0] && counts[2] > 1.5 * counts[1])
        assertTrue("opacity $brightest", brightest[1] > brightest[0] && brightest[2] > brightest[1])
        assertTrue(brightest.all { it <= MOTE_PEAK })
    }

    @Test
    fun `each rise in the voice sets off a burst and a fall sets off none`() {
        val motes = phone()
        motes.shape.fill(1f)
        motes.advance(0.001f, 0.9f, 0f, primary, tertiary)
        val burst = motes.count
        val expected = BURST_PER_POINT * liftedLevel(0.9f) * motes.points
        assertEquals(expected, burst.toFloat(), 0.02f * expected)
        // Holding the level adds only the steady trickle.
        motes.advance(0.001f, 0.9f, 0f, primary, tertiary)
        assertTrue("${motes.count - burst} more", motes.count - burst < 15)
        // A smaller rise sets off a smaller burst, in proportion.
        val partial = phone()
        partial.advance(0.001f, 0.6f, 0f, primary, tertiary)
        partial.shape.fill(1f)
        partial.advance(0.001f, 0.9f, 0f, primary, tertiary)
        val smaller = BURST_PER_POINT * (liftedLevel(0.9f) - liftedLevel(0.6f)) * partial.points
        assertEquals(smaller, partial.count.toFloat(), 0.05f * smaller)
        // A fall sets off nothing.
        val falling = phone()
        falling.advance(0.001f, 0.9f, 0f, primary, tertiary)
        falling.shape.fill(1f)
        falling.advance(0.001f, 0.4f, 0f, primary, tertiary)
        assertTrue("${falling.count} after a fall", falling.count < 3)
    }

    @Test
    fun `the crowd thins quickly once the voice drops`() {
        val held = phone(seed = 5)
        val dropped = phone(seed = 5)
        for (motes in listOf(held, dropped)) {
            motes.shape.fill(0.3f)
            motes.run(1f, 0.9f)
        }
        assertEquals(held.count, dropped.count)
        // No wave, so nothing new is born in either: only the ageing differs.
        held.shape.fill(0f)
        dropped.shape.fill(0f)
        held.run(0.13f, 0.9f)
        dropped.run(0.13f, 0f)
        assertTrue("held ${held.count}", held.count > 200)
        assertTrue("dropped ${dropped.count}, held ${held.count}", dropped.count < 0.25f * held.count)
    }

    @Test
    fun `motes gather where the wave is tall`() {
        val motes = phone()
        for (i in 0 until motes.points) motes.shape[i] = if (motes.x[i] < 540f) 0.9f else 0.1f
        var left = 0
        var right = 0
        motes.run(3f, 0.8f) {
            for (m in 0 until it.count) if (it.moteX[m] < 540f) left++ else right++
        }
        assertTrue("left $left, right $right", right > 0 && left > 5 * right)
    }

    @Test
    fun `every mote stays on screen within the reach and is small`() {
        // The phone's own corners, the fallback for phones that do not report theirs, and tight ones.
        for (radius in listOf(110f, 28f * dp, 4f, 0f)) {
            val motes = phone(radius = radius)
            motes.shape.fill(1f)
            // Round corners keep every mote at least as far in as on a straight side.
            val least = if (radius >= BIRTH_DP * dp) BIRTH_DP * dp else 0f
            var seen = 0
            motes.run(3f, 1f) {
                for (m in 0 until it.count) {
                    val depth = -edgeDistance(it.moteX[m], it.moteY[m], 1080f, 2400f, corners(radius))
                    assertTrue("depth $depth at radius $radius", depth >= least - 0.01f && depth <= it.reach + 0.01f)
                    val r = it.radius[m] / dp
                    val swelled = RADIUS_MAX_DP * (SWELL_QUIET + SWELL_LOUD)
                    assertTrue("radius $r", r >= RADIUS_MIN_DP * SWELL_QUIET - 0.001f && r <= swelled + 0.001f)
                    assertTrue(it.alpha[m] in 0f..MOTE_PEAK)
                }
                seen += it.count
            }
            assertTrue(seen > 0)
        }
    }

    @Test
    fun `motes slide along the edge either way`() {
        val motes = phone()
        // On a straight edge the slide is all in x.
        val i = motes.topMiddle()
        assertEquals(0f, motes.y[i], 0.01f)
        motes.shape[i] = 1f
        val most = MAX_SLIDE_DP * dp
        var left = 0f
        var right = 0f
        motes.run(10f, 0.9f) {
            for (m in 0 until it.count) {
                val along = it.moteX[m] - motes.x[i]
                assertTrue("along $along", abs(along) <= most + 0.01f)
                assertTrue(it.moteY[m] >= BIRTH_DP * dp - 0.01f && it.moteY[m] <= it.reach + 0.01f)
                left = minOf(left, along)
                right = maxOf(right, along)
            }
        }
        assertTrue("from $left to $right", left < -most / 2 && right > most / 2)
    }

    @Test
    fun `most motes hop out and back, and the rest shoot in and stay`() {
        val motes = phone(seed = 11)
        val i = motes.topMiddle()
        // Hear the voice first, so there is no burst; then follow one mote at a time from one point.
        motes.run(0.1f, 0.9f)
        var hopped = 0
        var stayed = 0
        var frames = 0
        while (hopped + stayed < 300 && frames < 200_000) {
            motes.shape[i] = 1f
            while (motes.count == 0) {
                motes.advance(1 / 240f, 0.9f, 0f, primary, tertiary)
                frames++
            }
            // No wave once it is out: nothing else is born and nothing lifts it.
            motes.shape[i] = 0f
            val single = motes.count == 1
            val depths = mutableListOf<Float>()
            var along = 0f
            while (motes.count > 0) {
                depths += motes.moteY[0]
                along = motes.moteX[0] - motes.x[i]
                motes.advance(1 / 240f, 0.9f, 0f, primary, tertiary)
                frames++
            }
            if (!single) continue
            // The wave was still under it in the frame it was born, so its course starts a frame later.
            val course = depths.drop(1)
            val birth = BIRTH_DP * dp
            val furthest = course.max()
            val peak = course.indexOf(furthest).toFloat() / course.size
            if (course.last() - birth < 0.3f * (furthest - birth)) {
                // Out and back to the edge, highest halfway through its life.
                hopped++
                assertTrue("peak at $peak", peak in 0.4f..0.6f)
                assertTrue("along $along", abs(along) <= MAX_SLIDE_DP * dp + 0.01f)
            } else {
                // In fast, then slower, and never back.
                stayed++
                assertTrue("shot in and came back", course.zipWithNext().all { (a, b) -> b >= a - 0.001f })
                assertTrue("along $along", abs(along) <= SHOOT_SLIDE * MAX_SLIDE_DP * dp + 0.01f)
            }
        }
        assertEquals(300, hopped + stayed)
        val share = hopped / 300f
        assertTrue("$hopped of 300 hopped", share in (HOP_SHARE - 0.1f)..(HOP_SHARE + 0.1f))
    }

    @Test
    fun `the wave under a mote lifts it further in`() {
        fun meanShare(wave: Float): Double {
            val motes = phone(seed = 13)
            motes.shape.fill(1f)
            motes.advance(0.001f, 0.9f, 0f, primary, tertiary)
            val born = motes.count
            // Only the wave differs now; the motes already out keep their places in the arrays.
            motes.shape.fill(wave)
            motes.advance(0.06f, 0.9f, 0f, primary, tertiary)
            return (0 until born).map { motes.share(it).toDouble() }.average()
        }
        val calm = meanShare(0f)
        val lifted = meanShare(1f)
        assertTrue("calm $calm, lifted $lifted", lifted > 1.4 * calm)
    }

    @Test
    fun `motes go further in for a tall wave and a loud voice`() {
        fun shares(
            wave: Float,
            level: Float,
        ): List<Float> {
            val motes = phone()
            motes.shape.fill(wave)
            val shares = mutableListOf<Float>()
            motes.run(2f, level) { for (m in 0 until it.count) shares += it.share(m) }
            return shares
        }
        assertTrue(shares(0.04f, 0.9f).max() < 0.45f)
        assertTrue(shares(0.9f, 0.9f).max() > 0.9f)
        val quiet = shares(0.3f, 0.4f).average()
        val loud = shares(0.3f, 0.9f).average()
        assertTrue("quiet $quiet, loud $loud", loud > 1.08 * quiet)
    }

    @Test
    fun `every mote swells with the voice`() {
        val motes = phone()
        motes.shape.fill(1f)
        motes.advance(0.001f, 0.9f, 0f, primary, tertiary)
        val n = motes.count
        val loud = motes.radius.copyOf(n)
        motes.advance(0.001f, 0.5f, 0f, primary, tertiary)
        val shrink = (SWELL_QUIET + SWELL_LOUD * liftedLevel(0.5f)) / (SWELL_QUIET + SWELL_LOUD * liftedLevel(0.9f))
        for (m in 0 until n) assertEquals(loud[m] * shrink, motes.radius[m], 0.001f)
    }

    @Test
    fun `each mote fades in and out to nothing`() {
        for (fade in listOf(::moteFade, ::hopFade)) {
            assertEquals(0f, fade(0f), 0f)
            assertEquals(0f, fade(1f), 0f)
            assertTrue(fade(0.99f) < 0.04f)
            val curve = (0..100).map { fade(it / 100f) }
            assertTrue(curve.all { it in 0f..1f })
            assertTrue(curve.max() > 0.8f)
            // Once it has brightened it only ever dims.
            val peak = curve.indexOf(curve.max())
            assertTrue(peak <= 20)
            for (i in peak + 1 until curve.size) assertTrue(curve[i] <= curve[i - 1])
        }
        // A hopping mote stays at its brightest until it starts to land.
        for (k in 15..72) assertEquals(1f, hopFade(k / 100f), 0.0001f)
        assertEquals(0.5f, hopFade(1 - (1 - LANDING) / 2), 0.001f)
        assertTrue(hopFade(0.5f) > moteFade(0.5f) * 1.5f)
    }

    @Test
    fun `each mote flickers as it fades`() {
        val wave = (0..1000).map { flicker(it / 1000f, 4f, 0f) }
        assertTrue(wave.all { it in (1 - FLICKER_DEPTH - 0.0001f)..1.0001f })
        assertEquals(1 - FLICKER_DEPTH, wave.min(), 0.001f)
        assertEquals(1f, wave.max(), 0.001f)
        // Four flickers in a life: up and down four times.
        assertEquals(8, turns(wave))
        assertTrue(flicker(0f, 4f, 1f) != flicker(0f, 4f, 2f))

        val motes = phone()
        // Hear the voice first, so there is no burst, then let a single point give off one mote.
        motes.run(0.1f, 0.8f)
        val i = motes.points / 3
        motes.shape[i] = 1f
        var frames = 0
        while (motes.count == 0 && frames++ < 6000) motes.advance(1 / 240f, 0.8f, 0f, primary, tertiary)
        assertEquals(1, motes.count)
        motes.shape[i] = 0f
        val seen = mutableListOf<Float>()
        while (motes.count == 1) {
            seen += motes.alpha[0]
            motes.advance(1 / 240f, 0.8f, 0f, primary, tertiary)
        }
        assertEquals(0, motes.count)
        assertEquals(0f, seen.first(), 0f)
        assertTrue(seen.max() > 0.2f)
        // Brighter and dimmer several times over, not one smooth rise and fall.
        assertTrue("${turns(seen)} turns", turns(seen) >= 4)
        // While the voice holds, it lives its own life and no longer.
        val lived = (seen.size - 1) / 240f
        assertTrue("lived $lived", lived >= LIFE_MIN - 0.01f && lived <= LIFE_MAX)
    }

    @Test
    fun `in silence the motes fade away and their places are used again`() {
        val motes = phone()
        motes.shape.fill(0.4f)
        motes.run(1f, 0.8f)
        val busy = motes.count
        assertTrue("$busy motes", busy > 50)
        motes.run(LIFE_MAX + 0.05f, 0f)
        assertEquals(0, motes.count)
        // Many lifetimes later the number alive is steady, so finished motes free their places.
        motes.run(1f, 0.8f)
        val again = motes.count
        motes.run(10f, 0.8f)
        assertTrue("$busy, $again, ${motes.count}", abs(again - busy) < 0.3f * busy && abs(motes.count - busy) < 0.3f * busy)
    }

    @Test
    fun `there are never more motes than the fixed number`() {
        val tablet = EdgeMotes(2f, Random(4)).apply { layout(2560f, 1600f, corners(40f)) }
        tablet.shape.fill(1f)
        var most = 0
        // A burst from silence and a loud voice held: both try for more than there is room for.
        tablet.run(3f, 1f) {
            assertTrue(it.count <= MAX_MOTES)
            most = maxOf(most, it.count)
        }
        assertEquals(MAX_MOTES, most)
    }

    @Test
    fun `motes born under the travelling highlight take the tertiary colour`() {
        val motes = phone()
        // With the phase at 0.3 the highlight peaks at 0.8 on the sweep, and is gone at 0.3.
        val peak = (0 until motes.points).minBy { abs(motes.sweep[it] - 0.8f) }
        val opposite = (0 until motes.points).minBy { abs(motes.sweep[it] - 0.3f) }
        for ((point, expected) in listOf(peak to tertiary, opposite to primary)) {
            motes.clear()
            motes.shape.fill(0f)
            motes.shape[point] = 1f
            val colours = mutableSetOf<Int>()
            motes.run(5f, 0.8f, phase = 0.3f) {
                for (m in 0 until it.count) colours += it.color[m]
            }
            assertTrue(colours.isNotEmpty())
            for (colour in colours) {
                for (shift in listOf(0, 8, 16)) {
                    assertEquals((expected shr shift and 0xFF).toFloat(), (colour shr shift and 0xFF).toFloat(), 2f)
                }
            }
        }
    }

    @Test
    fun `the same seed gives the same motes`() {
        val runs =
            (0..1).map {
                phone(seed = 9).apply {
                    shape.fill(0.5f)
                    // Up, louder and down, so bursts and the quick fade are repeated too.
                    run(0.5f, 0.6f, phase = 0.2f)
                    run(0.3f, 0.9f, phase = 0.2f)
                    shape.fill(0.2f)
                    run(0.2f, 0.4f, phase = 0.2f)
                }
            }
        val (a, b) = runs
        assertTrue(a.count > 0)
        assertEquals(a.count, b.count)
        assertEquals(a.moteX.take(a.count), b.moteX.take(b.count))
        assertEquals(a.moteY.take(a.count), b.moteY.take(b.count))
        assertEquals(a.radius.take(a.count), b.radius.take(b.count))
        assertEquals(a.color.take(a.count), b.color.take(b.count))
        assertEquals(a.alpha.take(a.count), b.alpha.take(b.count))
    }

    @Test
    fun `with animations removed the dots stay put and brighten with the voice`() {
        val motes = phone()
        motes.shape.fill(1f)
        motes.run(0.5f, 0.8f)
        motes.hold(0f, primary)
        val n = motes.count
        assertTrue("$n dots", n > 50)
        val x = motes.moteX.copyOf(n)
        val y = motes.moteY.copyOf(n)
        assertTrue(motes.alpha.take(n).all { it == 0f })
        var last = 0f
        for (level in listOf(0.4f, 0.6f, 0.9f, 0.9f)) {
            motes.hold(level, primary)
            assertEquals(n, motes.count)
            assertEquals(x.toList(), motes.moteX.take(n))
            assertEquals(y.toList(), motes.moteY.take(n))
            assertTrue(motes.radius.take(n).all { it == DOT_DP * dp })
            val alpha = motes.alpha[0]
            assertTrue(motes.alpha.take(n).all { it == alpha })
            assertTrue(alpha >= last && alpha <= MOTE_PEAK)
            last = alpha
            assertTrue(motes.color.take(n).all { it == primary })
        }
        // The same level again gives the same brightness: still dots never flicker.
        motes.hold(0.9f, primary)
        assertTrue(motes.alpha.take(n).all { it == last })
        assertTrue(last > 0.4f * MOTE_PEAK)
        // Evenly spaced just inside the edge, about 25dp apart.
        for (k in 0 until n) {
            assertEquals((BIRTH_DP + DOT_DP) * dp, -edgeDistance(x[k], y[k], 1080f, 2400f, corners(110f)), 0.05f)
        }
        val gaps = (0 until n).map { hypot(x[(it + 1) % n] - x[it], y[(it + 1) % n] - y[it]) / dp }
        assertTrue("gaps ${gaps.min()} to ${gaps.max()}dp", gaps.min() > 20f && gaps.max() < 30f)
        // Back to moving motes, the dots go at once.
        motes.advance(1 / 60f, 0f, 0f, primary, tertiary)
        assertEquals(0, motes.count)
    }
}
