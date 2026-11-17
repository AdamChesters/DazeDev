package com.adamchesters.dazedev.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class MicLevelTest {
    private fun sine(
        amplitude: Float,
        length: Int = 1536,
        cycles: Int = 12,
    ) = FloatArray(length) { (amplitude * sin(2 * PI * cycles * it / length)).toFloat() }

    @Test
    fun `level is measured on a decibel scale between the floor and full scale`() {
        assertEquals(0.5f / sqrt(2f), rms(sine(0.5f)), 0.001f)
        assertEquals(0f, rms(FloatArray(8), 0), 0f)
        assertEquals(0f, levelUnit(0f), 0f)
        assertEquals(0f, levelUnit(0.0005f), 0f)
        assertEquals(0.5f, levelUnit(0.0316f), 0.01f)
        assertEquals(1f, levelUnit(1f), 0f)
        assertEquals(1f, levelUnit(4f), 0f)
    }

    @Test
    fun `meter rises at once and falls steadily to zero`() {
        val meter = LevelMeter()
        val loud = meter.update(0.1f, 1_000_000_000L)
        assertEquals(levelUnit(0.1f), loud, 0.0001f)
        val falling = meter.update(0f, 1_100_000_000L)
        assertTrue(falling < loud && falling > 0f)
        assertEquals(levelUnit(0.3f), meter.update(0.3f, 1_116_000_000L), 0.0001f)
        repeat(8) { meter.update(0f, 2_000_000_000L + it * 200_000_000L) }
        assertEquals(0f, meter.level, 0f)
    }

    @Test
    fun `wave is flat in silence and without samples`() {
        val out = FloatArray(64) { 9f }
        waveHeights(FloatArray(1536), 1536, 0.8f, out, 2.3f)
        assertTrue(out.all { it == 0f })
        waveHeights(sine(0.3f), 1536, 0f, out.apply { fill(9f) }, 2.3f)
        assertTrue(out.all { it == 0f })
        waveHeights(sine(0.3f), 0, 0.8f, out.apply { fill(9f) }, 2.3f)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun `wave height follows the level so quiet speech is still visible`() {
        val quiet = FloatArray(160).also { waveHeights(sine(0.01f), 1536, 0.6f, it, 2.3f) }.max()
        val loud = FloatArray(160).also { waveHeights(sine(0.5f), 1536, 0.6f, it, 2.3f) }.max()
        val taller = FloatArray(160).also { waveHeights(sine(0.01f), 1536, 0.9f, it, 2.3f) }.max()
        assertEquals(loud, quiet, 0.01f)
        assertTrue(quiet > 0.2f)
        assertTrue(taller > quiet)
    }

    @Test
    fun `wave stays within bounds and has no step in it`() {
        val spiky = sine(0.05f).also { it[100] = 1f }
        val out = FloatArray(160)
        waveHeights(spiky, 1536, 1f, out, 2.3f)
        assertTrue(out.all { it in 0f..1f })
        val ramp = FloatArray(1536) { it / 1535f * 0.2f + 0.05f }
        for (points in listOf(40, 160, 900, 3000)) {
            val shape = FloatArray(points)
            waveHeights(ramp, 1536, 0.8f, shape, 2.3f)
            val step = (1 until points).maxOf { abs(shape[it] - shape[it - 1]) }
            assertTrue("step $step at $points points", step < 0.05f)
        }
    }

    @Test
    fun `noise below the meter floor is not magnified`() {
        val out = FloatArray(128)
        waveHeights(sine(0.00001f), 1536, 0.5f, out, 2.3f)
        assertTrue(out.max() < 0.01f)
    }

    @Test
    fun `tap keeps only the newest samples in order`() {
        val tap = MicTap(size = 8)
        val out = FloatArray(8)
        assertEquals(0, tap.snapshot(out))
        tap.write(floatArrayOf(1f, 2f, 3f))
        assertEquals(3, tap.snapshot(out))
        assertEquals(listOf(1f, 2f, 3f), out.take(3))
        tap.write(FloatArray(7) { it + 4f })
        assertEquals(8, tap.snapshot(out))
        assertEquals(listOf(3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f), out.toList())
        tap.write(FloatArray(20) { it + 100f })
        assertEquals(8, tap.snapshot(out))
        assertEquals(List(8) { it + 112f }, out.toList())
        val short = FloatArray(3)
        assertEquals(3, tap.snapshot(short))
        assertEquals(listOf(117f, 118f, 119f), short.toList())
        tap.clear()
        assertEquals(0, tap.snapshot(out))
    }
}
