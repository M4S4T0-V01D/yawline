package dev.m4s4t0.yawline.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The shift-light RPM bar: segments light green, then amber, then red; all of them flash at the
 * shift point.
 */
@Composable
fun RpmBar(rpm: Double, shiftRpm: Int, modifier: Modifier = Modifier) {
    val top = shiftRpm * 1.12f
    val f by animateFloatAsState((if (rpm.isNaN()) 0f else (rpm / top).toFloat()).coerceIn(0f, 1f), spring(stiffness = 900f), label = "rpm")
    val shift = !rpm.isNaN() && rpm >= shiftRpm
    var blink by remember { mutableStateOf(false) }
    LaunchedEffect(shift) { blink = false; while (shift) { delay(90); blink = !blink } }
    val bg = Yaw.panelAlt
    val ok = Yaw.ok; val warn = Yaw.warn; val bad = Yaw.bad; val accent2 = Yaw.accent2
    Canvas(modifier.fillMaxWidth().height(34.dp)) {
        val n = 32
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val segF = (i + 1f) / n
            val segRpm = segF * top
            val col = when {
                segRpm > shiftRpm -> bad
                segRpm > shiftRpm * 0.85f -> warn
                else -> ok
            }
            val lit = segF <= f + 0.0001f
            val color = when {
                shift && blink -> accent2
                lit -> col
                else -> bg
            }
            // Taller towards the red line, like a rising rev counter.
            val h = size.height * (0.45f + 0.55f * segF)
            drawRoundRect(color, Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(2.dp.toPx()))
        }
    }
}

/** A 240° arc gauge with a value fraction and an optional warning fraction. */
@Composable
fun ArcGauge(fraction: Float, modifier: Modifier, color: Color = Yaw.accent, warnFrom: Float = 2f) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(stiffness = 600f), label = "arc")
    val track = Yaw.panelAlt
    val bad = Yaw.bad
    Canvas(modifier) {
        val stroke = size.minDimension * 0.08f
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 150f, 240f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        if (warnFrom <= 1f) drawArc(bad.copy(alpha = 0.35f), 150f + 240f * warnFrom, 240f * (1 - warnFrom), false, Offset(inset, inset), arcSize, style = Stroke(stroke * 0.4f))
        drawArc(Brush.sweepGradient(listOf(color.copy(alpha = 0.6f), color)), 150f, 240f * f, false, Offset(inset, inset), arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** A thin horizontal bar: the value's place between a sensor's min and max. */
@Composable
fun MiniBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(stiffness = 600f), label = "bar")
    val track = Yaw.panelAlt
    Canvas(modifier.fillMaxWidth().height(4.dp)) {
        drawRoundRect(track, cornerRadius = CornerRadius(size.height))
        drawRoundRect(color, size = Size(size.width * f, size.height), cornerRadius = CornerRadius(size.height))
    }
}

/**
 * The drift meter: the arrow is where the car is going (always straight up); the car is drawn turned by
 * the slip angle, so you see the slide as it looked from above. Arc marks are every 15°.
 */
@Composable
fun DriftMeter(slip: Double, peak: Double, modifier: Modifier) {
    val a by animateFloatAsState(slip.toFloat().coerceIn(-90f, 90f), spring(stiffness = 300f), label = "slip")
    val text = Yaw.text; val dim = Yaw.dim; val border = Yaw.border; val accent = Yaw.accent; val accent2 = Yaw.accent2
    val heat = Yaw.heat(min(1f, abs(a) / 45f))
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2 * 0.92f
        // Scale: ±90° across the top half.
        for (deg in -90..90 step 15) {
            val rad = Math.toRadians(deg - 90.0)
            val major = deg % 45 == 0
            val r1 = r * (if (major) 0.86f else 0.91f)
            drawLine(if (major) dim else border, Offset(c.x + r1 * cos(rad).toFloat(), c.y + r1 * sin(rad).toFloat()),
                Offset(c.x + r * cos(rad).toFloat(), c.y + r * sin(rad).toFloat()), strokeWidth = if (major) 3f else 2f)
        }
        // Slip arc from straight-ahead to the current angle, and the peak tick.
        drawArc(heat, -90f, a, false, Offset(c.x - r * 0.78f, c.y - r * 0.78f), Size(r * 1.56f, r * 1.56f), style = Stroke(r * 0.07f, cap = StrokeCap.Round))
        if (peak > 1) for (sgn in listOf(-1, 1)) {
            val rad = Math.toRadians(sgn * peak - 90)
            drawCircle(accent2, r * 0.025f, Offset(c.x + r * 0.78f * cos(rad).toFloat(), c.y + r * 0.78f * sin(rad).toFloat()))
        }
        // Travel arrow.
        val arrow = Path().apply {
            moveTo(c.x, c.y - r * 0.68f); lineTo(c.x - r * 0.05f, c.y - r * 0.56f); lineTo(c.x + r * 0.05f, c.y - r * 0.56f); close()
        }
        drawLine(dim, Offset(c.x, c.y + r * 0.5f), Offset(c.x, c.y - r * 0.58f), strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        drawPath(arrow, dim)
        rotate(a, c) { drawCar(c, r * 0.5f, text, accent) }
    }
}

/** A top-down rally car about [len] long, nose up. */
private fun DrawScope.drawCar(c: Offset, len: Float, body: Color, accent: Color) {
    val w = len * 0.46f
    val left = c.x - w / 2; val top = c.y - len / 2
    drawRoundRect(body, Offset(left, top), Size(w, len), CornerRadius(w * 0.3f))
    // Windscreen, roof stripe and rear window.
    drawRoundRect(Color.Black.copy(alpha = 0.75f), Offset(left + w * 0.12f, top + len * 0.22f), Size(w * 0.76f, len * 0.16f), CornerRadius(w * 0.08f))
    drawRect(accent, Offset(c.x - w * 0.09f, top + len * 0.04f), Size(w * 0.18f, len * 0.92f))
    drawRoundRect(Color.Black.copy(alpha = 0.6f), Offset(left + w * 0.16f, top + len * 0.72f), Size(w * 0.68f, len * 0.1f), CornerRadius(w * 0.06f))
    // Wheels.
    val tw = w * 0.2f; val th = len * 0.17f
    for ((x, y) in listOf(left - tw * 0.6f to top + len * 0.12f, left + w - tw * 0.4f to top + len * 0.12f,
        left - tw * 0.6f to top + len * 0.7f, left + w - tw * 0.4f to top + len * 0.7f)) {
        drawRoundRect(Color(0xFF111111), Offset(x, y), Size(tw, th), CornerRadius(tw * 0.3f))
    }
}

/**
 * The friction circle: lateral G across, longitudinal up (accelerating) and down (braking), with a
 * fading trail of the last few seconds and the session's peak ring.
 */
@Composable
fun GgPlot(trail: List<Pair<Float, Float>>, peak: Float, modifier: Modifier, maxG: Float = 1.5f) {
    val border = Yaw.border; val dim = Yaw.dim; val accent = Yaw.accent; val accent2 = Yaw.accent2
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2 * 0.95f
        for (g in listOf(0.5f, 1.0f, 1.5f)) if (g <= maxG) drawCircle(border, r * g / maxG, c, style = Stroke(1.5f))
        drawLine(border, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.5f)
        drawLine(border, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.5f)
        if (peak > 0.05f) drawCircle(accent2.copy(alpha = 0.5f), r * min(peak, maxG) / maxG, c, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
        // Right turns push you left, so +lateral (left accel) is drawn to the left like a G-meter.
        trail.forEachIndexed { i, (lat, lon) ->
            val p = Offset(c.x - lat / maxG * r, c.y - lon / maxG * r)
            drawCircle(accent.copy(alpha = 0.08f + 0.5f * i / max(1, trail.size)), r * 0.025f, p)
        }
        trail.lastOrNull()?.let { (lat, lon) ->
            val p = Offset(c.x - lat.coerceIn(-maxG, maxG) / maxG * r, c.y - lon.coerceIn(-maxG, maxG) / maxG * r)
            drawCircle(accent, r * 0.06f, p)
            drawCircle(Color.White, r * 0.025f, p)
        }
        drawCircle(dim, 3f, c)
    }
}

/**
 * A route drawn to fit the box, north up. [colors] (one per point) paints it by speed, drift or
 * anything else; [cursor] marks one point.
 */
@Composable
fun RouteView(
    pts: List<Pair<Double, Double>>,
    modifier: Modifier,
    colors: List<Color>? = null,
    cursor: Int = -1,
    marks: List<Int> = emptyList(),
    lineColor: Color = Yaw.accent,
) {
    val dim = Yaw.dim; val text = Yaw.text; val ok = Yaw.ok; val bad = Yaw.bad
    Canvas(modifier) {
        if (pts.size < 2) return@Canvas
        val proj = Projector(pts, size.width, size.height, 12.dp.toPx())
        val w = 4.dp.toPx()
        var prev = proj(pts[0])
        for (i in 1 until pts.size) {
            val p = proj(pts[i])
            drawLine(colors?.getOrNull(i) ?: lineColor, prev, p, strokeWidth = w, cap = StrokeCap.Round)
            prev = p
        }
        for (m in marks) if (m in pts.indices) drawCircle(dim, 3.dp.toPx(), proj(pts[m]))
        drawCircle(ok, 5.dp.toPx(), proj(pts.first()))
        drawCircle(bad, 5.dp.toPx(), proj(pts.last()))
        if (cursor in pts.indices) {
            val p = proj(pts[cursor])
            drawCircle(text, 8.dp.toPx(), p, style = Stroke(3.dp.toPx()))
            drawCircle(text, 3.dp.toPx(), p)
        }
    }
}

/** Fits lat/lon into a box with equal metres on both axes. */
class Projector(pts: List<Pair<Double, Double>>, w: Float, h: Float, pad: Float) {
    private val minLat = pts.minOf { it.first }
    private val maxLat = pts.maxOf { it.first }
    private val minLon = pts.minOf { it.second }
    private val maxLon = pts.maxOf { it.second }
    private val k = cos(Math.toRadians((minLat + maxLat) / 2))
    private val spanX = max((maxLon - minLon) * k, 1e-6)
    private val spanY = max(maxLat - minLat, 1e-6)
    private val scale = min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY)
    private val offX = (w - spanX * scale) / 2
    private val offY = (h - spanY * scale) / 2

    operator fun invoke(p: Pair<Double, Double>) =
        Offset((offX + (p.second - minLon) * k * scale).toFloat(), (offY + (maxLat - p.first) * scale).toFloat())
}

/** A simple line chart of one or two series over the same x, with a cursor line. */
@Composable
fun LineChart(a: List<Float>, b: List<Float>?, modifier: Modifier, cursor: Int, colorA: Color = Yaw.accent, colorB: Color = Yaw.accent2) {
    val border = Yaw.border; val text = Yaw.text
    Canvas(modifier) {
        drawLine(border, Offset(0f, size.height), Offset(size.width, size.height), 1.5f)
        fun series(v: List<Float>, col: Color) {
            if (v.size < 2) return
            val finite = v.filter { !it.isNaN() }
            if (finite.isEmpty()) return
            val lo = finite.min(); val hi = max(finite.max(), lo + 1e-3f)
            val path = Path()
            var started = false
            v.forEachIndexed { i, y ->
                if (y.isNaN()) { started = false; return@forEachIndexed }
                val x = i * size.width / (v.size - 1)
                val py = size.height - (y - lo) / (hi - lo) * size.height * 0.92f
                if (!started) { path.moveTo(x, py); started = true } else path.lineTo(x, py)
            }
            drawPath(path, col, style = Stroke(2.dp.toPx()))
        }
        b?.let { series(it, colorB.copy(alpha = 0.8f)) }
        series(a, colorA)
        if (a.size > 1 && cursor in a.indices) {
            val x = cursor * size.width / (a.size - 1)
            drawLine(text, Offset(x, 0f), Offset(x, size.height), 2f)
        }
    }
}
