package com.adamchesters.dazedev.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Signed distance from the edge of a rounded rectangle: negative inside, positive outside. */
internal fun edgeDistance(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    radii: FloatArray,
): Float {
    val px = x - width / 2
    val py = y - height / 2
    val r =
        when {
            px < 0 && py < 0 -> radii[0]
            px >= 0 && py < 0 -> radii[1]
            px >= 0 -> radii[2]
            else -> radii[3]
        }
    val qx = abs(px) - width / 2 + r
    val qy = abs(py) - height / 2 + r
    return min(max(qx, qy), 0f) + hypot(max(qx, 0f), max(qy, 0f)) - r
}

class EdgeGlowTest {
    private fun samples(
        outline: ScreenOutline,
        count: Int,
    ) = (0 until count).map { i ->
        FloatArray(4).also { outline.locate(i * outline.length / count + 0.37f, it) }
    }

    @Test
    fun `outline walks the screen edge clockwise with normals pointing in`() {
        val radii = floatArrayOf(40f, 40f, 40f, 40f)
        val outline = ScreenOutline(400f, 800f, radii)
        assertEquals(2 * 320f + 2 * 720f + 80f * PI.toFloat(), outline.length, 0.01f)
        val start = FloatArray(4).also { outline.locate(0f, it) }
        assertNear(floatArrayOf(40f, 0f, 0f, 1f), start)
        val points = samples(outline, 2000)
        for (p in points) {
            assertEquals(0f, edgeDistance(p[0], p[1], 400f, 800f, radii), 0.01f)
            assertEquals(1f, hypot(p[2], p[3]), 0.0001f)
            assertTrue(edgeDistance(p[0] + 5 * p[2], p[1] + 5 * p[3], 400f, 800f, radii) < -4.9f)
        }
        // Clockwise and without jumps: each step is the spacing and the normal turns gently.
        val step = outline.length / 2000
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            assertEquals(step, hypot(b[0] - a[0], b[1] - a[1]), step * 0.02f)
            assertTrue(a[2] * b[2] + a[3] * b[3] > 0.99f)
            // The heading is the normal turned back a quarter; moving along it goes clockwise.
            assertTrue((b[0] - a[0]) * a[3] - (b[1] - a[1]) * a[2] > 0f)
        }
        val wrapped = FloatArray(4).also { outline.locate(outline.length + 10f, it) }
        val early = FloatArray(4).also { outline.locate(10f, it) }
        assertNear(early, wrapped)
    }

    private fun assertNear(
        expected: FloatArray,
        actual: FloatArray,
    ) {
        for (i in expected.indices) assertEquals("value $i", expected[i], actual[i], 0.001f)
    }

    @Test
    fun `each corner keeps its own radius and its arc faces its centre`() {
        val radii = floatArrayOf(10f, 60f, 30f, 0f)
        val outline = ScreenOutline(400f, 300f, radii)
        val quarter = PI.toFloat() / 2
        val top = 400f - 10f - 60f
        val right = 300f - 60f - 30f
        val bottom = 400f - 30f - 0f
        val arcMiddles =
            listOf(
                top + quarter * 60f / 2 to Triple(340f, 60f, 60f),
                top + quarter * 60f + right + quarter * 30f / 2 to Triple(370f, 270f, 30f),
                top + quarter * 60f + right + quarter * 30f + bottom + 0f to Triple(0f, 300f, 0f),
                outline.length - quarter * 10f / 2 to Triple(10f, 10f, 10f),
            )
        val p = FloatArray(4)
        for ((distance, corner) in arcMiddles) {
            val (centreX, centreY, r) = corner
            outline.locate(distance, p)
            assertEquals(r, hypot(p[0] - centreX, p[1] - centreY), 0.01f)
            assertEquals(centreX, p[0] + p[2] * r, 0.01f)
            assertEquals(centreY, p[1] + p[3] * r, 0.01f)
        }
        assertEquals(listOf(25f, 25f, 0f, 25f), ScreenOutline(100f, 50f, floatArrayOf(90f, 30f, -4f, 25f)).radii.toList())
    }

    @Test
    fun `a quiet room counts as silence and speech is lifted`() {
        assertEquals(0f, liftedLevel(0f), 0f)
        assertEquals(0f, liftedLevel(NOISE_GATE), 0f)
        assertEquals(1f, liftedLevel(1f), 0.0001f)
        // Room hum around -54 dB, against the meter's own scale.
        assertEquals(0f, liftedLevel(levelUnit(0.002f)), 0f)
        val quietSpeech = liftedLevel(levelUnit(0.01f))
        assertTrue("quiet speech lifts to $quietSpeech", quietSpeech > 0.3f)
        assertTrue(liftedLevel(0.6f) > liftedLevel(0.4f))
    }

    @Test
    fun `sweep, shimmer and colour helpers`() {
        assertEquals(0f, sweepPosition(10f, 0f, 0f, 0f), 0.0001f)
        assertEquals(0.25f, sweepPosition(0f, 10f, 0f, 0f), 0.0001f)
        assertEquals(0.5f, sweepPosition(-10f, 0f, 0f, 0f), 0.0001f)
        assertEquals(0.75f, sweepPosition(0f, -10f, 0f, 0f), 0.0001f)
        assertEquals(0f, shimmerAt(SHIMMER_START), 0f)
        assertEquals(1f, shimmerAt(SHIMMER_PEAK), 0.0001f)
        assertEquals(0.5f, shimmerAt((SHIMMER_START + SHIMMER_PEAK) / 2), 0.0001f)
        assertEquals(0f, shimmerAt(SHIMMER_END), 0f)
        assertEquals(1f, shimmerAt(SHIMMER_PEAK + 2), 0.0001f)
        assertEquals(1f, shimmerAt(SHIMMER_PEAK - 1), 0.0001f)
        assertEquals(0f, smoothStep(-1f), 0f)
        assertEquals(0.5f, smoothStep(0.5f), 0f)
        assertEquals(1f, smoothStep(3f), 0f)
        assertEquals(0xFF808080.toInt(), mixRgb(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
        assertEquals(0xFF123456.toInt(), mixRgb(0x00123456, 0xFFFFFFFF.toInt(), 0f))
        assertEquals(0x80123456.toInt(), withAlpha(0xFF123456.toInt(), 0.5f))
        assertEquals(0x00123456, withAlpha(0xFF123456.toInt(), -1f))
    }
}
