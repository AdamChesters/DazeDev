package com.adamchesters.dazedev.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class EdgeGlowTest {
    private val primary = 0xFF39BDD7.toInt()
    private val tertiary = 0xFFE8BA70.toInt()

    /** Signed distance from the edge of a rounded rectangle: negative inside, positive outside. */
    private fun edgeDistance(
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
    fun `wave fades out at the middle of every corner and is whole along the sides`() {
        val outline = ScreenOutline(400f, 800f, floatArrayOf(40f, 40f, 40f, 40f))
        val arc = PI.toFloat() / 2 * 40f
        val top = 320f
        val side = 720f
        val reach = 28f
        assertEquals(0f, outline.cornerFade(top + arc / 2, reach), 0.0001f)
        assertEquals(0f, outline.cornerFade(outline.length - arc / 2, reach), 0.0001f)
        assertEquals(1f, outline.cornerFade(top / 2, reach), 0f)
        assertEquals(1f, outline.cornerFade(top + arc + side / 2, reach), 0f)
        // Rising steadily from the top right corner's middle to the middle of the right side.
        var last = -1f
        var d = top + arc / 2
        while (d < top + arc + side / 2) {
            val fade = outline.cornerFade(d, reach)
            assertTrue(fade in 0f..1f && fade >= last)
            last = fade
            d += 1f
        }
    }

    private fun crosses(
        a: FloatArray,
        b: FloatArray,
        c: FloatArray,
        d: FloatArray,
    ): Boolean {
        fun side(
            p: FloatArray,
            q: FloatArray,
            r: FloatArray,
        ) = (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0])
        val d1 = side(c, d, a)
        val d2 = side(c, d, b)
        val d3 = side(a, b, c)
        val d4 = side(a, b, d)
        return d1 * d2 < 0 && d3 * d4 < 0
    }

    @Test
    fun `glow from neighbouring sides never overlaps at a corner`() {
        for (radii in listOf(floatArrayOf(40f, 40f, 40f, 40f), floatArrayOf(0f, 20f, 80f, 8f), floatArrayOf(110f, 110f, 90f, 90f))) {
            val outline = ScreenOutline(1080f, 2400f, radii)
            val points = samples(outline, 1500)
            val segments =
                points.mapIndexed { i, p ->
                    val room = outline.room(i * outline.length / 1500 + 0.37f)
                    p to floatArrayOf(p[0] + p[2] * room, p[1] + p[3] * room)
                }
            // Only points near corners can meet; the long sides are far apart.
            val nearCorners = segments.filter { (p, _) -> min(p[0], 1080f - p[0]) < 400f && min(p[1], 2400f - p[1]) < 400f }
            for (i in nearCorners.indices) {
                for (j in i + 1 until nearCorners.size) {
                    val (a, b) = nearCorners[i]
                    val (c, d) = nearCorners[j]
                    assertFalse("bands cross with radii ${radii.toList()}", crosses(a, b, c, d))
                }
            }
            // Every band also ends inside the screen.
            for ((_, end) in segments) {
                assertTrue(edgeDistance(end[0], end[1], 1080f, 2400f, outline.radii) <= 0.01f)
            }
        }
    }

    private fun phone(
        radius: Float = 110f,
        dp: Float = 2.625f,
    ) = EdgeGlowMesh(dp).apply { layout(1080f, 2400f, floatArrayOf(radius, radius, radius, radius)) }

    private fun depth(
        mesh: EdgeGlowMesh,
        ring: Int,
        i: Int,
    ): Float {
        val v = ring * mesh.points + i
        return (mesh.vertices[2 * v] - mesh.x[i]) * mesh.nx[i] + (mesh.vertices[2 * v + 1] - mesh.y[i]) * mesh.ny[i]
    }

    private fun alpha(color: Int) = (color ushr 24) / 255f

    @Test
    fun `mesh has a sensible size and every index points at a vertex`() {
        val mesh = phone()
        assertTrue("${mesh.points} points", mesh.points in 600..MAX_POINTS)
        assertEquals(mesh.points * GLOW_RINGS * 2, mesh.vertexFloats)
        assertTrue(mesh.vertices.size >= mesh.vertexFloats && mesh.colors.size >= mesh.vertexFloats)
        assertEquals(mesh.points * (GLOW_RINGS - 1) * 6, mesh.indexCount)
        assertTrue(mesh.indices.take(mesh.indexCount).all { it.toInt() in 0 until mesh.points * GLOW_RINGS })
        val tablet = EdgeGlowMesh(2f).apply { layout(2560f, 1600f, floatArrayOf(0f, 0f, 0f, 0f)) }
        assertEquals(MAX_POINTS, tablet.points)
        assertEquals(0, EdgeGlowMesh(2f).apply { layout(0f, 900f, floatArrayOf(0f, 0f, 0f, 0f)) }.points)
    }

    @Test
    fun `rows stay in order, start past the edge and never pass the room`() {
        for (radius in listOf(110f, 4f)) {
            val mesh = phone(radius)
            mesh.shape.fill(1f)
            mesh.build(1f, 0.2f, 1f, 1f, primary, tertiary)
            for (i in 0 until mesh.points) {
                assertTrue(depth(mesh, 0, i) < 0f)
                for (ring in 1 until GLOW_RINGS) {
                    assertTrue(depth(mesh, ring, i) >= depth(mesh, ring - 1, i) - 0.001f)
                    assertTrue(depth(mesh, ring, i) <= mesh.room[i] + 0.001f)
                }
            }
            // The innermost row is always fully clear, so the glow has no hard inner edge.
            val innermost = (GLOW_RINGS - 1) * mesh.points
            assertTrue((innermost until innermost + mesh.points).all { alpha(mesh.colors[it]) == 0f })
        }
    }

    @Test
    fun `silence is a thin dim line and a voice makes the glow deeper, brighter and rippled`() {
        val dp = 2.625f
        val mesh = phone(dp = dp)
        val n = mesh.points
        mesh.build(0f, 0f, 1f, 1f, primary, tertiary)
        val quietDepth = (0 until n).maxOf { depth(mesh, GLOW_RINGS - 1, it) }
        val quietAlpha = mesh.colors.take(n * GLOW_RINGS).maxOf(::alpha)
        assertTrue("silence reaches ${quietDepth / dp}dp", quietDepth < 10 * dp)
        assertTrue("silence opacity $quietAlpha", quietAlpha < 0.15f)
        // A wave of alternate peaks and troughs round the edge.
        for (i in 0 until n) mesh.shape[i] = if (i / 20 % 2 == 0) 0.9f else 0.1f
        mesh.build(0.8f, 0f, 1f, 1f, primary, tertiary)
        val loud = (0 until n).map { depth(mesh, WAVE_RING, it) }
        val loudAlpha = mesh.colors.take(n * GLOW_RINGS).maxOf(::alpha)
        assertTrue(loud.average() > 3 * quietDepth)
        assertTrue(loudAlpha > 0.4f)
        val middle = (0 until n).filter { mesh.fade[it] == 1f }
        assertTrue(middle.isNotEmpty())
        val ripple = middle.maxOf { loud[it] } - middle.minOf { loud[it] }
        assertTrue("ripple ${ripple / dp}dp", ripple > 0.6f * mesh.reach)
        // Without a wave the glow is even all round; near the corners the wave adds almost nothing.
        mesh.shape.fill(0f)
        mesh.build(0.8f, 0f, 1f, 1f, primary, tertiary)
        val even = (0 until n).map { depth(mesh, WAVE_RING, it) }
        assertEquals(even.min(), even.max(), 0.01f)
        val corners = (0 until n).filter { mesh.fade[it] < 0.1f }
        assertTrue(corners.isNotEmpty())
        for (i in corners) assertTrue(loud[i] - even[i] <= 0.1f * 0.9f * mesh.reach + 0.01f)
    }

    @Test
    fun `a quiet room leaves the silent line and speech lifts the glow`() {
        assertEquals(0f, liftedLevel(0f), 0f)
        assertEquals(0f, liftedLevel(NOISE_GATE), 0f)
        assertEquals(1f, liftedLevel(1f), 0.0001f)
        // Room hum around -54 dB, against the meter's own scale.
        assertEquals(0f, liftedLevel(levelUnit(0.002f)), 0f)
        val quietSpeech = liftedLevel(levelUnit(0.01f))
        assertTrue("quiet speech lifts to $quietSpeech", quietSpeech > 0.3f)
        assertTrue(liftedLevel(0.6f) > liftedLevel(0.4f))
        val silent = phone().apply { build(0f, 0f, 1f, 1f, primary, tertiary) }
        val hum = phone().apply { build(NOISE_GATE * 0.9f, 0f, 1f, 1f, primary, tertiary) }
        assertEquals(silent.vertices.toList(), hum.vertices.toList())
        assertEquals(silent.colors.toList(), hum.colors.toList())
    }

    @Test
    fun `fading out takes every vertex to fully clear`() {
        val mesh = phone()
        mesh.shape.fill(0.7f)
        mesh.build(0.9f, 0.4f, 1f, 0f, primary, tertiary)
        assertTrue(mesh.colors.take(mesh.points * GLOW_RINGS).all { alpha(it) == 0f })
    }

    @Test
    fun `highlight travels round in the tertiary colour and stays primary when still`() {
        val mesh = phone()
        val n = mesh.points
        mesh.build(0.5f, 0.3f, 0f, 1f, primary, tertiary)
        assertTrue(mesh.colors.take(n * GLOW_RINGS).all { it and 0xFFFFFF == primary and 0xFFFFFF })
        mesh.build(0.5f, 0.3f, 1f, 1f, primary, tertiary)
        // The highlight peaks half a turn on from the phase.
        val peak = (0 until n).minBy { abs(mesh.sweep[it] - 0.8f) }
        for (shift in listOf(0, 8, 16)) {
            assertEquals((tertiary shr shift and 0xFF).toFloat(), (mesh.colors[peak] shr shift and 0xFF).toFloat(), 2f)
        }
        val opposite = (0 until n).minBy { abs(mesh.sweep[it] - 0.3f) }
        assertEquals(primary and 0xFFFFFF, mesh.colors[opposite] and 0xFFFFFF)
        mesh.build(0.5f, 0.55f, 1f, 1f, primary, tertiary)
        assertEquals(primary and 0xFFFFFF, mesh.colors[peak] and 0xFFFFFF)
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
