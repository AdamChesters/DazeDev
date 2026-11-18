package com.adamchesters.dazedev.mobile.ui

import android.graphics.Canvas
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
import com.adamchesters.dazedev.mobile.voice.EdgeMotes
import com.adamchesters.dazedev.mobile.voice.LevelMeter
import com.adamchesters.dazedev.mobile.voice.SHIMMER_END
import com.adamchesters.dazedev.mobile.voice.SHIMMER_PEAK
import com.adamchesters.dazedev.mobile.voice.SHIMMER_START
import com.adamchesters.dazedev.mobile.voice.liftedLevel
import com.adamchesters.dazedev.mobile.voice.rms
import com.adamchesters.dazedev.mobile.voice.waveHeights
import com.adamchesters.dazedev.mobile.voice.withAlpha
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/** Samples per point, as on the desktop border: a 150 Hz voice makes a wave about 150dp long. */
private const val WAVE_DENSITY = 2.3f

/**
 * How fast the level that drives the motes may fall, in meter heights a second. It rises at once.
 * This is much quicker than the meter the line and still dots use, so the motes keep up with speech.
 */
private const val DRIVE_FALL = 5f

/** With animations removed the dots update about five times a second. */
private const val STILL_FRAME_MS = 200L

private const val FADE_NANOS = 180_000_000f

/** One lap of the travelling highlight round the screen. */
private const val SHIMMER_NANOS = 4_000_000_000L

/** Width of the dim line at the very edge. */
private const val CORE_DP = 2f

/** Opacity of the line in silence, and how much a loud voice adds. */
private const val LINE_QUIET = 0.25f
private const val LINE_LOUD = 0.25f

/** Each mote is a faint halo this bright, with a centre this share of its radius at full brightness. */
private const val HALO = 0.35f
private const val CENTRE = 0.45f

/** Corner radius used where the phone does not report its screen corners. */
private const val FALLBACK_RADIUS_DP = 28f

/**
 * While listening, small glowing motes leap off the edge of the whole screen and flicker: more of
 * them where the microphone wave is tall, far more the louder the voice, a burst on each rise, and
 * few soon after it drops. Most hop and land a little way along the edge, the rest shoot in and
 * fade, and all of them ride the wave and swell with the voice. A thin dim line stays at the edge,
 * so in silence it is still clear the microphone is open. [read] copies the newest samples into the
 * array it is given and returns how many there are.
 *
 * Call it from inside the voice draft sheet. The sheet has its own full-screen window above the
 * app, so the motes are drawn in the overlay of that window's root view: above the sheet and its
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
    // "Remove animations" in the phone's accessibility settings: fixed dots that brighten, nothing moves.
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
    private val motes = EdgeMotes(dp)
    private val drawable = GlowDrawable(motes, dp)
    private val samples = FloatArray(2048)
    private var target = FloatArray(0)
    private val meter = LevelMeter()
    private val drive = LevelMeter(DRIVE_FALL)
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
        drive.reset()
        lastFrame = 0L
        opacity = if (still) 1f else 0f
        motes.clear()
        motes.shape.fill(0f)
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
        motes.layout(width.toFloat(), height.toFloat(), radii)
        if (target.size != motes.points) target = FloatArray(motes.points)
        drawable.layout(width, height, radii)
    }

    fun frame(
        now: Long,
        listening: Boolean,
        still: Boolean,
        read: (FloatArray) -> Int,
    ) {
        val elapsed = if (lastFrame == 0L) 0L else now - lastFrame
        lastFrame = now
        val step = elapsed / FADE_NANOS
        opacity =
            when {
                still -> if (listening) 1f else 0f
                listening -> min(1f, opacity + step)
                else -> max(0f, opacity - step)
            }
        val count = read(samples)
        val amplitude = rms(samples, count)
        val level = meter.update(amplitude, now)
        val quick = drive.update(amplitude, now)
        var phase = 0f
        if (motes.points > 0) {
            if (still) {
                motes.hold(level, primary)
            } else {
                val shape = motes.shape
                waveHeights(samples, count, liftedLevel(quick), target, WAVE_DENSITY)
                for (i in shape.indices) shape[i] += (target[i] - shape[i]) * 0.7f
                phase = (now % SHIMMER_NANOS) / SHIMMER_NANOS.toFloat()
                motes.advance(elapsed / 1e9f, quick, phase, primary, tertiary)
            }
            drawable.update(level, phase, opacity, still)
        }
        if (!listening && opacity <= 0f) hide()
    }
}

/** Draws the dim edge line and the motes. Every object it draws with is made ahead of time. */
private class GlowDrawable(
    private val motes: EdgeMotes,
    private val dp: Float,
) : Drawable() {
    private val linePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
    private val motePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Path()
    private val turn = Matrix()
    private var sweep: SweepGradient? = null
    private var centreX = 0f
    private var centreY = 0f
    private var width = 0
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
        if (opacity <= 0f) return
        // The line takes the travelling highlight from the same sweep the motes take their colour from.
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
        linePaint.alpha = alphaOf((LINE_QUIET + LINE_LOUD * liftedLevel(level)) * opacity)
        linePaint.strokeWidth = CORE_DP * dp
        canvas.drawPath(core, linePaint)
        // A faint halo under a brighter centre gives each mote a soft edge on every Android version.
        for (m in 0 until motes.count) {
            val alpha = motes.alpha[m] * opacity
            if (alpha <= 0f) continue
            val x = motes.moteX[m]
            val y = motes.moteY[m]
            val r = motes.radius[m]
            motePaint.color = withAlpha(motes.color[m], alpha * HALO)
            canvas.drawCircle(x, y, r, motePaint)
            motePaint.color = withAlpha(motes.color[m], alpha)
            canvas.drawCircle(x, y, r * CENTRE, motePaint)
        }
    }

    private fun alphaOf(value: Float) = (value * 255 + 0.5f).toInt().coerceIn(0, 255)

    // The overlay never fades the drawable itself; the glow's own opacity does that.
    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) {
        linePaint.colorFilter = colorFilter
        motePaint.colorFilter = colorFilter
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
