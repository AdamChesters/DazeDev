package com.adamchesters.dazedev.mobile.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import androidx.annotation.RequiresApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.adamchesters.dazedev.mobile.voice.EdgeGlowMesh
import com.adamchesters.dazedev.mobile.voice.LevelMeter
import com.adamchesters.dazedev.mobile.voice.SHIMMER_END
import com.adamchesters.dazedev.mobile.voice.SHIMMER_PEAK
import com.adamchesters.dazedev.mobile.voice.SHIMMER_START
import com.adamchesters.dazedev.mobile.voice.WAVE_RING
import com.adamchesters.dazedev.mobile.voice.liftedLevel
import com.adamchesters.dazedev.mobile.voice.rms
import com.adamchesters.dazedev.mobile.voice.waveHeights
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/** Samples per point, as on the desktop border: a 150 Hz voice makes a wave about 150dp long. */
private const val WAVE_DENSITY = 2.3f

/** With animations removed the glow updates about five times a second. */
private const val STILL_FRAME_MS = 200L

private const val FADE_NANOS = 180_000_000f

/** One lap of the travelling highlight round the screen. */
private const val SHIMMER_NANOS = 4_000_000_000L

/** Width of the bright line at the very edge. */
private const val CORE_DP = 2f

/** Corner radius used where the phone does not report its screen corners. */
private const val FALLBACK_RADIUS_DP = 28f

/**
 * While listening, a glow runs round the whole screen like an edge lighting notification. It
 * deepens, brightens and ripples with the microphone input and settles to a thin dim line in
 * silence, so it is obvious whether the microphone hears anything. [read] copies the newest
 * samples into the array it is given and returns how many there are.
 *
 * Call it from inside the voice draft sheet. The sheet has its own full-screen window above the
 * app, so the glow is drawn in the overlay of that window's root view: above the sheet and its
 * scrim, edge to edge and behind the system bars. A view overlay never takes touches, focus or
 * accessibility focus, so the sheet, the keyboard and the back gesture work as before. Nothing
 * here is laid out, and TalkBack does not see it; the "Listening" text says the same thing.
 */
@Composable
internal fun VoiceEdgeGlow(
    listening: Boolean,
    read: (FloatArray) -> Int,
) {
    val view = LocalView.current
    val context = LocalContext.current
    val density = LocalDensity.current.density
    // "Remove animations" in the phone's accessibility settings: an even glow, no ripple or shimmer.
    val still =
        remember(context) {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }
    val glow = remember(view, density) { EdgeGlow(view, density) }
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val tertiary = MaterialTheme.colorScheme.tertiary.toArgb()
    SideEffect { glow.setColors(primary, tertiary) }
    val latestRead by rememberUpdatedState(read)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val started = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(glow, listening, started, still) {
        if (!started) {
            // In the background no frames run. A glow that is only fading out goes at once.
            if (!listening) glow.hide()
            return@LaunchedEffect
        }
        if (listening) glow.show(still)
        val onFrame: (Long) -> Unit = { now -> glow.frame(now, listening, still, latestRead) }
        while (glow.shown) {
            withFrameNanos(onFrame)
            if (still) delay(STILL_FRAME_MS)
        }
    }
    DisposableEffect(glow) { onDispose(glow::hide) }
}

/** Keeps the glow's drawable in the window overlay while it shows, and works out each frame. */
private class EdgeGlow(
    private val view: View,
    private val dp: Float,
) : View.OnLayoutChangeListener {
    private val mesh = EdgeGlowMesh(dp)
    private val drawable = GlowDrawable(mesh, dp)
    private val samples = FloatArray(2048)
    private var target = FloatArray(0)
    private val meter = LevelMeter()
    private val radii = FloatArray(4)
    private val laidOut = FloatArray(4)
    private var laidOutWidth = 0
    private var laidOutHeight = 0
    private var host: View? = null
    private var lastFrame = 0L
    private var opacity = 0f
    private var primary = 0
    private var tertiary = 0

    val shown get() = host != null

    fun setColors(
        primary: Int,
        tertiary: Int,
    ) {
        this.primary = primary
        this.tertiary = tertiary
        drawable.setColors(primary, tertiary)
    }

    fun show(still: Boolean) {
        if (host != null) return
        // The root of the window this composition lives in: the sheet's window, not the app's.
        val root = view.rootView
        host = root
        meter.reset()
        lastFrame = 0L
        opacity = if (still) 1f else 0f
        mesh.shape.fill(0f)
        drawable.update(0f, 0f, 0f, still)
        root.overlay.add(drawable)
        root.addOnLayoutChangeListener(this)
        relayout(root)
    }

    fun hide() {
        val root = host ?: return
        host = null
        root.removeOnLayoutChangeListener(this)
        root.overlay.remove(drawable)
    }

    override fun onLayoutChange(
        v: View,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        oldLeft: Int,
        oldTop: Int,
        oldRight: Int,
        oldBottom: Int,
    ) = relayout(v)

    /** Rebuilds the fixed geometry when the window's size or reported corners change. */
    private fun relayout(root: View) {
        val width = root.width
        val height = root.height
        if (width <= 0 || height <= 0) return
        screenCorners(root, FALLBACK_RADIUS_DP * dp, radii)
        if (width == laidOutWidth && height == laidOutHeight && radii.contentEquals(laidOut)) return
        laidOutWidth = width
        laidOutHeight = height
        radii.copyInto(laidOut)
        mesh.layout(width.toFloat(), height.toFloat(), radii)
        if (target.size != mesh.points) target = FloatArray(mesh.points)
        drawable.layout(width, height, radii)
    }

    fun frame(
        now: Long,
        listening: Boolean,
        still: Boolean,
        read: (FloatArray) -> Int,
    ) {
        val step = if (lastFrame == 0L) 0f else (now - lastFrame) / FADE_NANOS
        lastFrame = now
        opacity =
            when {
                still -> if (listening) 1f else 0f
                listening -> min(1f, opacity + step)
                else -> max(0f, opacity - step)
            }
        val count = read(samples)
        val level = meter.update(rms(samples, count), now)
        val n = mesh.points
        if (n > 0) {
            val shape = mesh.shape
            if (still) {
                shape.fill(liftedLevel(level) * 0.5f)
            } else {
                waveHeights(samples, count, liftedLevel(level), target, WAVE_DENSITY)
                for (i in 0 until n) shape[i] += (target[i] - shape[i]) * 0.7f
            }
            val phase = if (still) 0f else (now % SHIMMER_NANOS) / SHIMMER_NANOS.toFloat()
            mesh.build(level, phase, if (still) 0f else 1f, opacity, primary, tertiary)
            drawable.update(level, phase, opacity, still)
        }
        if (!listening && opacity <= 0f) hide()
    }
}

/** Draws the mesh and the bright core line. Every object it draws with is made ahead of time. */
private class GlowDrawable(
    private val mesh: EdgeGlowMesh,
    private val dp: Float,
) : Drawable() {
    // Opaque white, so the vertex colours come through unchanged however the canvas combines them.
    private val meshPaint = Paint().apply { color = Color.WHITE }
    private val linePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Path()
    private val wave = Path()
    private val band = Path()
    private val turn = Matrix()
    private var sweep: SweepGradient? = null
    private var centreX = 0f
    private var centreY = 0f
    private var width = 0
    private var height = 0
    private var primary = 0
    private var tertiary = 0
    private var level = 0f
    private var phase = 0f
    private var opacity = 0f
    private var still = false

    fun setColors(
        primary: Int,
        tertiary: Int,
    ) {
        if (primary == this.primary && tertiary == this.tertiary && sweep != null) return
        this.primary = primary
        this.tertiary = tertiary
        makeSweep()
    }

    fun layout(
        width: Int,
        height: Int,
        radii: FloatArray,
    ) {
        this.width = width
        this.height = height
        setBounds(0, 0, width, height)
        // A little in from the edge, so the screen's own rounded corners do not clip the line.
        val inset = CORE_DP * dp / 2 + dp
        val corners = FloatArray(8) { max(0f, radii[it / 2] - inset) }
        core.rewind()
        core.addRoundRect(RectF(inset, inset, width - inset, height - inset), corners, Path.Direction.CW)
        centreX = width / 2f
        centreY = height / 2f
        makeSweep()
    }

    private fun makeSweep() {
        if (width == 0) return
        sweep =
            SweepGradient(
                centreX,
                centreY,
                intArrayOf(primary, primary, tertiary, primary, primary),
                floatArrayOf(0f, SHIMMER_START, SHIMMER_PEAK, SHIMMER_END, 1f),
            )
    }

    fun update(
        level: Float,
        phase: Float,
        opacity: Float,
        still: Boolean,
    ) {
        this.level = level
        this.phase = phase
        this.opacity = opacity
        this.still = still
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (opacity <= 0f || mesh.points == 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            canvas.drawVertices(
                Canvas.VertexMode.TRIANGLES,
                mesh.vertexFloats,
                mesh.vertices,
                0,
                null,
                0,
                mesh.colors,
                0,
                mesh.indices,
                0,
                mesh.indexCount,
                meshPaint,
            )
        } else {
            drawWithoutMesh(canvas)
        }
        // The core line takes the travelling highlight from the same sweep as the mesh.
        linePaint.shader =
            if (still) {
                null
            } else {
                sweep?.also {
                    turn.setRotate(phase * 360f, centreX, centreY)
                    it.setLocalMatrix(turn)
                }
            }
        linePaint.color = primary
        linePaint.alpha = alphaOf((0.45f + 0.55f * liftedLevel(level)) * opacity)
        linePaint.strokeWidth = CORE_DP * dp
        canvas.drawPath(core, linePaint)
    }

    /**
     * Android 9 cannot draw vertex meshes with hardware acceleration, so there the glow is a plain
     * band from the edge to the wave, with a line along the wave.
     */
    private fun drawWithoutMesh(canvas: Canvas) {
        val n = mesh.points
        val row = WAVE_RING * n * 2
        val vertices = mesh.vertices
        wave.rewind()
        wave.moveTo(vertices[row], vertices[row + 1])
        for (i in 1 until n) wave.lineTo(vertices[row + 2 * i], vertices[row + 2 * i + 1])
        wave.close()
        band.rewind()
        // Rewinding resets the fill rule; even-odd fills only between the edge and the wave.
        band.fillType = Path.FillType.EVEN_ODD
        band.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        band.addPath(wave)
        fillPaint.color = primary
        fillPaint.alpha = alphaOf((0.1f + 0.3f * liftedLevel(level)) * opacity)
        canvas.drawPath(band, fillPaint)
        linePaint.shader = null
        linePaint.color = primary
        linePaint.alpha = alphaOf((0.2f + 0.6f * liftedLevel(level)) * opacity)
        linePaint.strokeWidth = 1.5f * dp
        canvas.drawPath(wave, linePaint)
    }

    private fun alphaOf(value: Float) = (value * 255 + 0.5f).toInt().coerceIn(0, 255)

    // The overlay never fades the drawable itself; the glow's own opacity does that.
    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) {
        meshPaint.colorFilter = colorFilter
        linePaint.colorFilter = colorFilter
        fillPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** The window's corner radii in pixels: as the phone reports them on Android 12 and later, else [fallback]. */
private fun screenCorners(
    view: View,
    fallback: Float,
    out: FloatArray,
) {
    out.fill(fallback)
    val insets = view.rootWindowInsets ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) reportedCorners(insets, out)
}

@RequiresApi(Build.VERSION_CODES.S)
private fun reportedCorners(
    insets: WindowInsets,
    out: FloatArray,
) {
    insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.let { out[0] = it.radius.toFloat() }
    insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.let { out[1] = it.radius.toFloat() }
    insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.let { out[2] = it.radius.toFloat() }
    insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.let { out[3] = it.radius.toFloat() }
}
