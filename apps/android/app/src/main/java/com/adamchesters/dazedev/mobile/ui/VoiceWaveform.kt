package com.adamchesters.dazedev.mobile.ui

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.adamchesters.dazedev.mobile.voice.LevelMeter
import com.adamchesters.dazedev.mobile.voice.rms
import com.adamchesters.dazedev.mobile.voice.waveHeights
import kotlin.math.max
import kotlin.math.pow

/** Samples per point: a 150 Hz voice makes a wave about 45 points long. */
private const val WAVE_DENSITY = 2.3f
private const val WAVE_POINTS = 160

/**
 * The live microphone waveform shown while listening. It swells with the voice and lies flat in
 * silence, so it is obvious whether the microphone hears anything. [read] copies the newest
 * samples into the array it is given and returns how many there are.
 */
@Composable
internal fun VoiceWaveform(
    read: (FloatArray) -> Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // "Remove animations" in the phone's accessibility settings: show a steady bar, not a wave.
    val still =
        remember(context) {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }
    val samples = remember { FloatArray(2048) }
    val target = remember { FloatArray(WAVE_POINTS) }
    val heights = remember { FloatArray(WAVE_POINTS) }
    val meter = remember { LevelMeter() }
    var frame by remember { mutableLongStateOf(0L) }
    val latestRead by rememberUpdatedState(read)
    LaunchedEffect(still) {
        var paintedAt = 0L
        while (true) {
            withFrameNanos { now ->
                val count = latestRead(samples)
                val level = meter.update(rms(samples, count), now)
                if (!still) {
                    // The 0.7 power lifts quiet speech, so any voice at all moves the wave.
                    waveHeights(samples, count, level.pow(0.7f), target, WAVE_DENSITY)
                    for (i in heights.indices) heights[i] += (target[i] - heights[i]) * 0.7f
                    frame = now
                } else if (now - paintedAt > 200_000_000L) {
                    heights.fill(level)
                    paintedAt = now
                    frame = now
                }
            }
        }
    }
    val color = MaterialTheme.colorScheme.primary
    Canvas(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clearAndSetSemantics { contentDescription = "Microphone input level" },
    ) {
        // Reading the frame counter redraws the canvas each time the wave changes.
        frame.hashCode()
        val middle = size.height / 2
        val reach = middle - 2.dp.toPx()
        val step = size.width / (WAVE_POINTS - 1)
        val band = Path()
        for (i in 0 until WAVE_POINTS) {
            val y = middle - max(1.dp.toPx(), heights[i] * reach)
            if (i == 0) band.moveTo(0f, y) else band.lineTo(i * step, y)
        }
        for (i in WAVE_POINTS - 1 downTo 0) {
            band.lineTo(i * step, middle + max(1.dp.toPx(), heights[i] * reach))
        }
        band.close()
        drawPath(band, color.copy(alpha = 0.3f + 0.4f * meter.level))
        drawPath(band, color, style = Stroke(width = 1.5.dp.toPx()))
        drawLine(color.copy(alpha = 0.5f), Offset(0f, middle), Offset(size.width, middle), strokeWidth = 1.dp.toPx())
    }
}
