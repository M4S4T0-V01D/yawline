package dev.m4s4t0.yawline.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.m4s4t0.yawline.BuildConfig
import dev.m4s4t0.yawline.YawApp
import dev.m4s4t0.yawline.sensors.Live
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh

// Web Mercator, with the world as 0..1 on both axes.
private fun mercX(lon: Double) = (lon + 180) / 360
private fun mercY(lat: Double): Double {
    val s = sin(Math.toRadians(lat)).coerceIn(-0.9999, 0.9999)
    return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
}
private fun lonOf(x: Double) = x * 360 - 180
private fun latOf(y: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y))))

/** Where the map looks and how close. [follow] keeps it centred on the car. */
@Stable
class MapState(lat: Double, lon: Double, zoom: Double) {
    var cx by mutableDoubleStateOf(mercX(lon)); private set
    var cy by mutableDoubleStateOf(mercY(lat)); private set
    var zoom by mutableDoubleStateOf(zoom); private set
    var follow by mutableStateOf(false)
    internal var size by mutableStateOf(IntSize.Zero)
    internal var tilePx = 256f
    private var pendingFit: List<Pair<Double, Double>>? = null
    /** Moved by hand or fitted: screens use this to stop auto-centring on the first fix. */
    var touched = false; private set

    private val world get() = tilePx * 2.0.pow(zoom)

    fun moveTo(lat: Double, lon: Double, zoom: Double? = null) {
        cx = mercX(lon); cy = mercY(lat)
        if (zoom != null) this.zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    /** Zooms and centres to show all of [pts]. */
    fun fit(pts: List<Pair<Double, Double>>) {
        if (pts.isEmpty()) return
        touched = true
        if (size == IntSize.Zero) { pendingFit = pts; return }
        val xs = pts.map { mercX(it.second) }; val ys = pts.map { mercY(it.first) }
        val dx = max(xs.max() - xs.min(), 1e-9); val dy = max(ys.max() - ys.min(), 1e-9)
        cx = (xs.max() + xs.min()) / 2; cy = (ys.max() + ys.min()) / 2
        zoom = log2(min(size.width * 0.8 / (dx * tilePx), size.height * 0.8 / (dy * tilePx))).coerceIn(MIN_ZOOM, 17.0)
    }

    internal fun sized(s: IntSize) {
        size = s
        pendingFit?.let { pendingFit = null; fit(it) }
    }

    fun toScreen(lat: Double, lon: Double): Offset =
        Offset(((mercX(lon) - cx) * world + size.width / 2.0).toFloat(), ((mercY(lat) - cy) * world + size.height / 2.0).toFloat())

    fun toLatLon(o: Offset): Pair<Double, Double> =
        latOf(cy + (o.y - size.height / 2.0) / world) to lonOf(cx + (o.x - size.width / 2.0) / world)

    internal fun pan(dx: Float, dy: Float) {
        touched = true
        cx = ((cx - dx / world) % 1.0 + 1.0) % 1.0
        cy = (cy - dy / world).coerceIn(0.0, 1.0)
    }

    /** Zooms by [factor] keeping the point under [focus] still. */
    fun zoomBy(factor: Double, focus: Offset = Offset(size.width / 2f, size.height / 2f)) {
        val fx = cx + (focus.x - size.width / 2.0) / world
        val fy = cy + (focus.y - size.height / 2.0) / world
        zoom = (zoom + log2(factor)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        cx = fx - (focus.x - size.width / 2.0) / world
        cy = fy - (focus.y - size.height / 2.0) / world
    }

    companion object {
        const val MIN_ZOOM = 2.0
        const val MAX_ZOOM = 19.0
    }
}

@Composable
fun rememberMapState(lat: Double = 20.0, lon: Double = 0.0, zoom: Double = 2.0) = remember { MapState(lat, lon, zoom) }

/** A line drawn on the map; [ends] marks its start (green) and finish (red). */
class MapLine(val pts: List<Pair<Double, Double>>, val color: Color, val width: Dp = 5.dp, val ends: Boolean = false, val dashed: Boolean = false)

class MapDot(val lat: Double, val lon: Double, val color: Color, val radius: Dp = 6.dp)

/**
 * An OpenStreetMap map with lines and dots on it and the car's position. Drag to pan, pinch to zoom.
 * [onTap] gets the position of a tap; with [freehand], a one-finger drag draws a stroke instead of
 * panning (two fingers still pan and zoom) and the stroke goes to [onStroke].
 */
@Composable
fun MapView(
    state: MapState,
    modifier: Modifier = Modifier,
    lines: List<MapLine> = emptyList(),
    dots: List<MapDot> = emptyList(),
    me: Live? = null,
    onTap: ((Double, Double) -> Unit)? = null,
    freehand: Boolean = false,
    onStroke: ((List<Pair<Double, Double>>) -> Unit)? = null,
) {
    val density = LocalDensity.current
    state.tilePx = 160 * density.density
    val palette = Yaw.palette
    val filter = remember(palette) { tileFilter(palette) }
    var stroke by remember { mutableStateOf(emptyList<Offset>()) }
    // The gesture loop outlives recompositions; it reads the latest callbacks through these.
    val tapCb by rememberUpdatedState(onTap)
    val strokeCb by rememberUpdatedState(onStroke)
    val accent = Yaw.accent; val accent2 = Yaw.accent2; val bg = Yaw.bg; val ok = Yaw.ok; val bad = Yaw.bad

    LaunchedEffect(me?.lat, me?.lon, state.follow) {
        if (state.follow && me != null && me.hasFix) state.moveTo(me.lat, me.lon)
    }

    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Yaw.panelAlt)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { state.sized(it) }
                .pointerInput(freehand) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var multi = false
                        var moved = false
                        var travel = 0f
                        val drawn = arrayListOf(down.position)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size > 1 && !multi) { multi = true; stroke = emptyList() }
                            if (freehand && !multi) {
                                val p = pressed.first().position
                                travel += (p - drawn.last()).getDistance()
                                if ((p - drawn.last()).getDistance() > 4.dp.toPx()) { drawn.add(p); stroke = drawn.toList() }
                                if (travel > viewConfiguration.touchSlop) moved = true
                            } else {
                                val pan = ev.calculatePan()
                                val z = ev.calculateZoom()
                                travel += pan.getDistance()
                                if (travel > viewConfiguration.touchSlop || multi) moved = true
                                if (moved) {
                                    state.follow = false
                                    state.pan(pan.x, pan.y)
                                    if (z != 1f) state.zoomBy(z.toDouble(), ev.calculateCentroid())
                                }
                            }
                            ev.changes.forEach { it.consume() }
                        }
                        stroke = emptyList()
                        if (freehand && !multi && moved && drawn.size >= 2) strokeCb?.invoke(drawn.map(state::toLatLon))
                        else if (!moved && !multi) state.toLatLon(down.position).let { (lat, lon) -> tapCb?.invoke(lat, lon) }
                    }
                },
        ) {
            Tiles.loaded // redraw as tiles arrive
            drawTiles(state, filter)
            for (l in lines) drawLine(state, l, bg)
            for (l in lines) if (l.ends && l.pts.size > 1) {
                drawCircle(ok, 7.dp.toPx(), state.toScreen(l.pts.first().first, l.pts.first().second))
                drawCircle(bad, 7.dp.toPx(), state.toScreen(l.pts.last().first, l.pts.last().second))
            }
            for (d in dots) {
                val p = state.toScreen(d.lat, d.lon)
                drawCircle(bg, d.radius.toPx() + 2.dp.toPx(), p)
                drawCircle(d.color, d.radius.toPx(), p)
            }
            if (stroke.size > 1) {
                val path = Path().apply { moveTo(stroke[0].x, stroke[0].y); for (p in stroke) lineTo(p.x, p.y) }
                drawPath(path, accent, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (me != null && me.hasFix) drawMe(state.toScreen(me.lat, me.lon), me, accent2, bg)
        }
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MapButton(Icons.Filled.Add) { state.zoomBy(2.0) }
            MapButton(Icons.Filled.Remove) { state.zoomBy(0.5) }
            if (me != null) MapButton(Icons.Filled.MyLocation, on = state.follow) {
                state.follow = true
                if (me.hasFix) state.moveTo(me.lat, me.lon, max(state.zoom, 15.0))
            }
        }
        Text(
            "© OpenStreetMap contributors",
            style = TextStyle(fontSize = 9.sp, color = Yaw.dim),
            modifier = Modifier.align(Alignment.BottomEnd).background(Yaw.bg.copy(alpha = 0.6f)).padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun MapButton(icon: ImageVector, on: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
            .background(if (on) Yaw.accent2 else Yaw.panel.copy(alpha = 0.9f))
            .border(1.dp, Yaw.border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (on) Yaw.bg else Yaw.text, modifier = Modifier.size(22.dp)) }
}

/** Dark themes get a dimmed, theme-tinted map so it doesn't glare at night; light themes the real colours. */
private fun tileFilter(p: Palette): ColorFilter? {
    if (p.light) return null
    val b = 0.6f
    fun row(tint: Float, own: Int): FloatArray {
        val gray = floatArrayOf(0.3f, 0.59f, 0.11f)
        return FloatArray(5) { i -> if (i < 3) tint * b * (0.5f * gray[i] + if (i == own) 0.5f else 0f) else 0f }
    }
    val t = p.text
    return ColorFilter.colorMatrix(ColorMatrix(row(t.red, 0) + row(t.green, 1) + row(t.blue, 2) + floatArrayOf(0f, 0f, 0f, 1f, 0f)))
}

private fun DrawScope.drawTiles(s: MapState, filter: ColorFilter?) {
    val z = floor(s.zoom).toInt().coerceIn(0, 19)
    val n = 1 shl z
    val ts = s.tilePx * 2.0.pow(s.zoom - z)
    val world = ts * n
    val left = s.cx * world - size.width / 2
    val top = s.cy * world - size.height / 2
    val ys = floor(top / ts).toInt().coerceAtLeast(0)..floor((top + size.height) / ts).toInt().coerceAtMost(n - 1)
    val xs = floor(left / ts).toInt()..floor((left + size.width) / ts).toInt()
    val wrap = { tx: Int -> ((tx % n) + n) % n }
    Tiles.wanted = buildSet { for (ty in ys) for (tx in xs) add(Tiles.key(z, wrap(tx), ty)) }
    for (ty in ys) {
        for (tx in xs) {
            val wx = wrap(tx)
            val x0 = (tx * ts - left).roundToInt(); val x1 = ((tx + 1) * ts - left).roundToInt()
            val y0 = (ty * ts - top).roundToInt(); val y1 = ((ty + 1) * ts - top).roundToInt()
            val dst = IntOffset(x0, y0); val dstSize = IntSize(x1 - x0, y1 - y0)
            val img = Tiles.get(z, wx, ty)
            if (img != null) { drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), dst, dstSize, colorFilter = filter); continue }
            // Not here yet: stretch a piece of a lower-zoom tile we already have.
            for (up in 1..5) {
                if (z - up < 0) break
                val p = Tiles.peek(z - up, wx shr up, ty shr up) ?: continue
                val sub = p.width shr up
                val src = IntOffset((wx - ((wx shr up) shl up)) * sub, (ty - ((ty shr up) shl up)) * sub)
                drawImage(p, src, IntSize(sub, sub), dst, dstSize, colorFilter = filter)
                break
            }
        }
    }
}

private fun DrawScope.drawLine(s: MapState, l: MapLine, outline: Color) {
    if (l.pts.size < 2) return
    val path = Path()
    var last = Offset.Unspecified
    for ((i, p) in l.pts.withIndex()) {
        val o = s.toScreen(p.first, p.second)
        if (i == 0) path.moveTo(o.x, o.y)
        else if (i == l.pts.lastIndex || (o - last).getDistance() > 1.5f) path.lineTo(o.x, o.y) else continue
        last = o
    }
    val w = l.width.toPx()
    val effect = if (l.dashed) PathEffect.dashPathEffect(floatArrayOf(w * 2, w * 1.6f)) else null
    if (!l.dashed) drawPath(path, outline.copy(alpha = 0.7f), style = Stroke(w + 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, l.color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
}

/** The car: a dot, with an arrow along the direction of travel once moving. */
private fun DrawScope.drawMe(p: Offset, me: Live, color: Color, outline: Color) {
    val r = 9.dp.toPx()
    drawCircle(color.copy(alpha = 0.18f), r * 3, p)
    if (me.speed > 2 && !me.course.isNaN()) {
        rotate(me.course.toFloat(), p) {
            val path = Path().apply {
                moveTo(p.x, p.y - r * 1.9f); lineTo(p.x + r * 1.2f, p.y + r * 1.1f)
                lineTo(p.x, p.y + r * 0.5f); lineTo(p.x - r * 1.2f, p.y + r * 1.1f); close()
            }
            drawPath(path, outline, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))
            drawPath(path, color)
        }
    } else {
        drawCircle(outline, r + 2.dp.toPx(), p)
        drawCircle(color, r, p)
    }
}

/**
 * OpenStreetMap tiles: memory, then disk (kept 30 days, so areas you've looked at work without
 * signal), then the network, two downloads at a time as the tile policy asks.
 */
object Tiles {
    private val mem = LruCache<String, ImageBitmap>(160)
    private val pending = HashSet<String>()
    private val failedAt = HashMap<String, Long>()
    private val io = Dispatchers.IO.limitedParallelism(2)
    private var dir: File? = null
    /** Bumped when a tile arrives, to redraw. */
    var loaded by mutableIntStateOf(0); private set
    /** Tiles on screen in the last frame; queued downloads that scrolled away are dropped. */
    @Volatile internal var wanted: Set<String> = emptySet()

    fun init(ctx: Context) { dir = File(ctx.cacheDir, "tiles") }

    fun key(z: Int, x: Int, y: Int) = "$z/$x/$y"

    fun peek(z: Int, x: Int, y: Int): ImageBitmap? = mem.get(key(z, x, y))

    fun get(z: Int, x: Int, y: Int): ImageBitmap? {
        val k = key(z, x, y)
        mem.get(k)?.let { return it }
        synchronized(pending) {
            if (k in pending) return null
            if (System.currentTimeMillis() - (failedAt[k] ?: 0L) < 20_000) return null
            pending.add(k)
        }
        YawApp.scope.launch(io) {
            val img = if (k in wanted) runCatching { load(z, x, y) }.getOrNull() else null
            synchronized(pending) {
                pending.remove(k)
                if (img == null && k in wanted) failedAt[k] = System.currentTimeMillis()
            }
            if (img != null) {
                mem.put(k, img)
                withContext(Dispatchers.Main) { loaded++ }
            }
        }
        return null
    }

    private fun load(z: Int, x: Int, y: Int): ImageBitmap? {
        val f = File(dir ?: return null, "$z/$x/$y.png")
        val cached = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
        if (cached != null && System.currentTimeMillis() - f.lastModified() < 30L * 24 * 3600 * 1000) return cached.asImageBitmap()
        val bytes = runCatching {
            val c = URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "YAWLINE/${BuildConfig.VERSION_NAME} (Android; github.com/${BuildConfig.UPDATE_REPO})")
            try {
                if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
            } finally { c.disconnect() }
        }.getOrNull()
        val fresh = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bytes != null && fresh != null) {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            runCatching { tmp.writeBytes(bytes); tmp.renameTo(f) }
            return fresh.asImageBitmap()
        }
        return cached?.asImageBitmap()
    }
}
