package com.adamchesters.dazedev.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
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

    /** A 150 Hz voice at 16 kHz, as the microphone gives it. */
    private fun voice(amplitude: Float) = FloatArray(1536) { (amplitude * sin(2 * PI * 150 * it / 16000)).toFloat() }

    @Test
    fun `points sit round the edge and motes reach a short way in`() {
        val motes = phone()
        assertTrue("${motes.points} points", motes.points in 600..MAX_POINTS)
        for (i in 0 until motes.points) {
            assertEquals(0f, edgeDistance(motes.x[i], motes.y[i], 1080f, 2400f, corners(110f)), 0.01f)
        }
        // About 60% of the old glow's reach: 22dp on this phone.
        assertEquals(1080f * 0.054f, motes.reach, 0.01f)
        val tablet = EdgeMotes(2f).apply { layout(2560f, 1600f, corners(0f)) }
        assertEquals(MAX_POINTS, tablet.points)
        assertEquals(26f * 2f, tablet.reach, 0.001f)
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
    fun `a louder voice gives off more and brighter motes`() {
        val counts = mutableListOf<Double>()
        val brightest = mutableListOf<Float>()
        for (level in listOf(0.35f, 0.6f, 0.9f)) {
            val motes = phone()
            val samples = voice(0.3f)
            waveHeights(samples, samples.size, liftedLevel(level), motes.shape, 2.3f)
            motes.run(1f, level)
            val seen = mutableListOf<Int>()
            var bright = 0f
            motes.run(1f, level) {
                seen += it.count
                for (m in 0 until it.count) bright = maxOf(bright, it.alpha[m])
            }
            counts += seen.average()
            brightest += bright
        }
        assertTrue("motes $counts", counts[0] > 10 && counts[1] > 1.3 * counts[0] && counts[2] > 1.3 * counts[1])
        assertTrue("opacity $brightest", brightest[1] > brightest[0] && brightest[2] > brightest[1])
        assertTrue(brightest.all { it <= MOTE_PEAK })
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
        for (radius in listOf(110f, 4f, 0f)) {
            val motes = phone(radius = radius)
            motes.shape.fill(1f)
            var seen = 0
            motes.run(3f, 1f) {
                for (m in 0 until it.count) {
                    val depth = -edgeDistance(it.moteX[m], it.moteY[m], 1080f, 2400f, corners(radius))
                    assertTrue("depth $depth", depth >= -0.01f && depth <= it.reach + 0.01f)
                    assertTrue(it.radius[m] >= RADIUS_MIN_DP * dp && it.radius[m] <= RADIUS_MAX_DP * dp)
                    assertTrue(it.alpha[m] in 0f..MOTE_PEAK)
                }
                seen += it.count
            }
            assertTrue(seen > 0)
        }
    }

    @Test
    fun `each mote fades in and out to nothing`() {
        assertEquals(0f, moteFade(0f), 0f)
        assertEquals(0f, moteFade(1f), 0f)
        assertTrue(moteFade(0.99f) < 0.02f)
        val curve = (0..100).map { moteFade(it / 100f) }
        assertTrue(curve.all { it in 0f..1f })
        assertTrue(curve.max() > 0.8f)
        // Once it has brightened it only ever dims.
        val peak = curve.indexOf(curve.max())
        assertTrue(peak <= 20)
        for (i in peak + 1 until curve.size) assertTrue(curve[i] <= curve[i - 1])
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
                    run(1f, 0.7f, phase = 0.2f)
                }
            }
        val (a, b) = runs
        assertTrue(a.count > 0)
        assertEquals(a.count, b.count)
        assertEquals(a.moteX.take(a.count), b.moteX.take(b.count))
        assertEquals(a.moteY.take(a.count), b.moteY.take(b.count))
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
            val alpha = motes.alpha[0]
            assertTrue(motes.alpha.take(n).all { it == alpha })
            assertTrue(alpha >= last && alpha <= MOTE_PEAK)
            last = alpha
            assertTrue(motes.color.take(n).all { it == primary })
        }
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
