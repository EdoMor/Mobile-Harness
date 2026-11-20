package com.jarves.mh.ui

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.jarves.mh.R
import com.jarves.mh.runtime.WirelessAdbManager
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * The approved "run finished" pulse: rings of dots around the screen's center light up as a
 * wave passes outward, with a short bloom at the center. Pure math, so it is unit-tested and
 * matches the web preview it was signed off from. Every size is in units of the dot spacing,
 * so it looks the same on any screen.
 */
internal object PulseMath {
    /** One wave, from the center to the edges. */
    const val PULSE_MS = 1_800L
    /** The second wave of a pair starts this much after the first. */
    const val PAIR_GAP_MS = 900L
    /** Black screen between the two pairs. */
    const val WAIT_MS = 60_000L
    /** From the end of the last pulse to locking the phone. */
    const val LOCK_DELAY_MS = 500L
    /** A pair of pulses, start to finish. */
    const val PAIR_MS = PAIR_GAP_MS + PULSE_MS
    private const val BLOOM_MS = 520f

    data class Dot(val x: Float, val y: Float, val r: Float)

    class Field(val width: Float, val height: Float, val spacing: Float, val maxR: Float, val dots: List<Dot>) {
        val cx: Float get() = width / 2f
        val cy: Float get() = height / 2f
    }

    /** Dots on rings around the center, one spacing apart, with a fixed jitter so every pulse looks the same. */
    fun field(width: Float, height: Float): Field {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = hypot(cx, cy)
        val spacing = width / 21f
        var seed = 7
        fun random(): Float {
            seed = seed * 1_664_525 + 1_013_904_223
            return (seed.toLong() and 0xFFFF_FFFFL).toFloat() / 4_294_967_296f
        }
        val dots = ArrayList<Dot>()
        var ring = 1
        while (ring * spacing < maxR + spacing) {
            val r = ring * spacing
            val count = max(6, ((2 * PI * r) / spacing).roundToInt())
            val phase = ring * 0.618
            for (i in 0 until count) {
                val angle = phase + (i * 2 * PI) / count
                val rr = r + (random() - 0.5f) * spacing * 0.28f
                val x = cx + (cos(angle) * rr).toFloat()
                val y = cy + (sin(angle) * rr).toFloat()
                if (x < -spacing || y < -spacing || x > width + spacing || y > height + spacing) continue
                dots += Dot(x, y, hypot(x - cx, y - cy))
            }
            ring++
        }
        return Field(width, height, spacing, maxR, dots)
    }

    fun easeOut(t: Float): Float = 1f - (1f - t).pow(3)

    fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /** How lit a dot at distance [r] from the center is, given the elapsed time of each wave. */
    fun intensity(r: Float, elapsedMs: List<Long>, spacing: Float, maxR: Float): Float {
        var total = 0f
        for (elapsed in elapsedMs) {
            if (elapsed < 0 || elapsed > PULSE_MS) continue
            val p = elapsed.toFloat() / PULSE_MS
            val front = easeOut(p) * maxR * 1.08f
            val off = r - front
            // Sharp leading edge, softer trail behind the wave.
            val sigma = if (off > 0) spacing * 0.85f else spacing * 2.4f
            val wave = exp(-(off * off) / (2 * sigma * sigma))
            val fade = 1f - smooth(0.68f, 1f, p)
            val reach = 1f - 0.55f * (r / maxR)
            total += wave * fade * reach
        }
        return min(1f, total)
    }

    /** Dot radius, as a fraction of the spacing, for an intensity. */
    fun dotRadius(intensity: Float, spacing: Float): Float = spacing * (0.0385f + 0.147f * intensity)

    /** Center bloom as a wave leaves it: radius and opacity, or null once it has faded. */
    fun bloom(elapsedMs: Long, spacing: Float): Pair<Float, Float>? {
        val p = elapsedMs / BLOOM_MS
        if (p < 0f || p > 1f) return null
        return spacing * (0.21f + 1.12f * easeOut(p)) to 0.95f * (1f - p)
    }
}

private val PulseFinished = Color(0xFFF28C52)
private val PulseAttention = Color(0xFFFF6B5E)

/**
 * Plays the ending: two pulses, a minute of black, two more pulses, then [onFinished] (which
 * locks the phone). A tap anywhere calls [onDismiss] and stops everything.
 */
@Composable
fun RunFinishedPulseScreen(failed: Boolean, onDismiss: () -> Unit, onFinished: suspend () -> Unit) {
    val color = if (failed) PulseAttention else PulseFinished
    var field by remember { mutableStateOf<PulseMath.Field?>(null) }
    val pulseStarts = remember { mutableStateListOf<Long>() }
    var now by remember { mutableLongStateOf(0L) }
    var showHint by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        suspend fun pulsePair() {
            val start = withFrameNanos { it } / 1_000_000L
            pulseStarts.clear()
            pulseStarts += start
            pulseStarts += start + PulseMath.PAIR_GAP_MS
            val end = start + PulseMath.PAIR_MS
            while (true) {
                val frame = withFrameNanos { it } / 1_000_000L
                now = frame
                if (frame >= end) break
            }
            pulseStarts.clear()
        }
        pulsePair()
        delay(PulseMath.WAIT_MS)
        pulsePair()
        delay(PulseMath.LOCK_DELAY_MS)
        showHint = false
        onFinished()
    }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size -> field = PulseMath.field(size.width.toFloat(), size.height.toFloat()) },
        ) {
            val f = field ?: return@Canvas
            if (pulseStarts.isEmpty()) return@Canvas
            val elapsed = pulseStarts.map { now - it }
            for (dot in f.dots) {
                val intensity = PulseMath.intensity(dot.r, elapsed, f.spacing, f.maxR)
                if (intensity < 0.02f) continue
                val radius = PulseMath.dotRadius(intensity, f.spacing)
                val center = Offset(dot.x, dot.y)
                if (intensity > 0.45f) {
                    drawCircle(color.copy(alpha = intensity * 0.16f), radius * 3.2f, center, blendMode = BlendMode.Plus)
                }
                drawCircle(color.copy(alpha = intensity), radius, center, blendMode = BlendMode.Plus)
            }
            val center = Offset(f.cx, f.cy)
            for (elapsedMs in elapsed) {
                val (radius, alpha) = PulseMath.bloom(elapsedMs, f.spacing) ?: continue
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                    blendMode = BlendMode.Plus,
                )
            }
        }
        if (showHint) {
            Text(
                stringResource(R.string.adb_pulse_dismiss_hint),
                color = Color(0xFF3A404B),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            )
        }
    }
}

/**
 * Full-screen black screen shown when a screen-off adb run ends. The app opens it over adb
 * (see [WirelessAdbManager]), with a one-time token so other apps cannot start the lock.
 */
class RunFinishedPulseActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppStrings.context(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = WirelessAdbManager.get(this)
        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (savedInstanceState == null && !manager.acceptEndingToken(token)) {
            finish()
            return
        }
        val failed = intent.getBooleanExtra(EXTRA_FAILED, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            RunFinishedPulseScreen(
                failed = failed,
                onDismiss = { finish() },
                onFinished = {
                    manager.lockPhone()
                    finish()
                },
            )
        }
    }

    companion object {
        const val EXTRA_TOKEN = "token"
        const val EXTRA_FAILED = "failed"
    }
}
