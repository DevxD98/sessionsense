package com.sessionsense.session_sense

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.hypot

/** Liquid glass needs AGSL runtime shaders (Android 13+); older phones keep the Haze blur bar. */
internal val liquidGlassSupported get() = Build.VERSION.SDK_INT >= 33

/**
 * Apple-style liquid glass, as an AGSL pass over the (lightly blurred) content behind the bar:
 * - a lens rim: near the edge, samples are pulled inward along the shape's normal, so content curves and magnifies;
 * - dispersion: red, green and blue bend by slightly different amounts right at the rim;
 * - vibrancy and adaptive tint: saturation up, darks lifted, brights calmed so icons read over anything;
 * - thickness: a soft sheen across the top, the bottom rim in shade, a bright hairline edge;
 * - a specular glint on the rim facing [light], which follows the phone's tilt;
 * - the selection pill is a second, smaller lens that magnifies what's under it and has its own glint.
 * All coordinates are pixels in the captured layer, which extends [GLASS_MARGIN] beyond the bar on every side.
 */
private const val LIQUID_GLASS = """
uniform shader content;
uniform float2 barOrigin;
uniform float2 barSize;
uniform float2 pillCenter;
uniform float2 pillSize;
uniform float2 light;
uniform float refraction;
uniform float dispersion;

float sdRoundRect(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

float2 sdNormal(float2 p, float2 halfSize, float r) {
    float dx = sdRoundRect(p + float2(1.0, 0.0), halfSize, r) - sdRoundRect(p - float2(1.0, 0.0), halfSize, r);
    float dy = sdRoundRect(p + float2(0.0, 1.0), halfSize, r) - sdRoundRect(p - float2(0.0, 1.0), halfSize, r);
    float2 g = float2(dx, dy);
    float len = length(g);
    return len > 0.0001 ? g / len : float2(0.0, -1.0);
}

half3 refracted(float2 uv, float2 shift, float spread) {
    half r = content.eval(uv + shift * (1.0 + spread)).r;
    half g = content.eval(uv + shift).g;
    half b = content.eval(uv + shift * (1.0 - spread)).b;
    return half3(r, g, b);
}

half4 main(float2 coord) {
    float2 halfSize = barSize * 0.5;
    float radius = halfSize.y;
    float2 p = coord - (barOrigin + halfSize);
    float d = sdRoundRect(p, halfSize, radius);
    if (d > 1.0) return half4(0.0);

    float2 n = sdNormal(p, halfSize, radius);
    // 0 across the flat middle, rising to 1 at the rim over the bevel.
    float rim = clamp(1.0 + d / (radius * 0.55), 0.0, 1.0);
    float bend = rim * rim * rim;
    float2 shift = -n * bend * refraction;

    // The pill: a second lens that magnifies what's under it.
    float2 pp = coord - pillCenter;
    float pillR = pillSize.y * 0.5;
    float pd = sdRoundRect(pp, pillSize * 0.5, pillR);
    float inPill = clamp(0.5 - pd, 0.0, 1.0);
    float2 pn = sdNormal(pp, pillSize * 0.5, pillR);
    float pillRim = clamp(1.0 + pd / (pillR * 0.8), 0.0, 1.0);
    float2 uv = mix(coord, pillCenter + pp * 0.86, inPill);
    shift += -pn * pillRim * pillRim * pillRim * refraction * 0.7 * inPill;

    half3 col = refracted(uv, shift, dispersion * bend);

    // Vibrancy, then an adaptive tint so the glass never goes muddy over dark or glaring over bright content.
    half luma = dot(col, half3(0.2126, 0.7152, 0.0722));
    col = mix(half3(luma), col, 1.3);
    col = mix(col, half3(0.95, 0.97, 1.0), 0.025 + 0.035 * (1.0 - luma));
    col = mix(col, half3(0.06, 0.07, 0.08), 0.16 * luma);

    // Thickness.
    float top = clamp(-p.y / halfSize.y, 0.0, 1.0);
    col += half3(0.05) * top * top * (1.0 - rim);
    col *= 1.0 - 0.25 * rim * clamp(n.y, 0.0, 1.0);

    // Specular glint on the rim facing the light, a fainter one opposite.
    float facing = max(dot(n, -light), 0.0);
    // Kept faint: a bright band along the top edge reads as chrome, not glass.
    float glint = pow(facing, 12.0) * smoothstep(0.7, 1.0, rim);
    float backGlint = pow(max(dot(n, light), 0.0), 14.0) * smoothstep(0.8, 1.0, rim) * 0.06;
    col += half3(glint * 0.12 + backGlint);

    // Pill body and glint.
    col += half3(0.045) * inPill;
    float pillGlint = pow(max(dot(pn, -light), 0.0), 8.0) * smoothstep(0.65, 1.0, pillRim) * inPill;
    col += half3(pillGlint * 0.14);

    // A faint, even hairline so the edge is defined without a white stripe.
    float hairline = smoothstep(1.3, 0.0, abs(d + 0.7));
    col += half3(0.07) * hairline;

    float alpha = clamp(0.5 - d, 0.0, 1.0);
    return half4(col * alpha, alpha);
}
"""

private val GLASS_MARGIN = 28.dp

/** Direction light travels across the screen (down and slightly right by default), nudged by tilting the phone. */
@Composable internal fun rememberTiltLight(): State<Offset> {
    val context = LocalContext.current
    val light = remember { mutableStateOf(Offset(.28f, 1f).normalized()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var smoothX = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                // Left/right tilt swings the light along the rim; low-passed so it glides rather than jitters.
                val tilt = (e.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
                smoothX += (tilt - smoothX) * .12f
                // Only a visible change redraws the glass; a phone lying still must not repaint 50 times a second.
                val next = Offset(.28f - smoothX * 1.6f, 1f).normalized()
                if ((next - light.value).getDistance() > .004f) light.value = next
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        // Only while the app is on screen.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> sensor?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
                Lifecycle.Event.ON_PAUSE -> sensors.unregisterListener(listener)
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); sensors.unregisterListener(listener) }
    }
    return light
}

private fun Offset.normalized(): Offset { val l = hypot(x, y); return if (l > 0f) Offset(x / l, y / l) else this }

/**
 * Draws the liquid glass for a bar filling this composable. [backdrop] holds everything behind the bar, recorded in
 * root coordinates; [pill] is the selection pill in this composable's local pixels.
 */
@RequiresApi(33)
@Composable internal fun LiquidGlassSurface(backdrop: GraphicsLayer, pill: () -> Rect, light: State<Offset>, modifier: Modifier) {
    val shader = remember { RuntimeShader(LIQUID_GLASS) }
    val glass = rememberGraphicsLayer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val blur = remember(density) { with(density) { 3.dp.toPx() }.let { RenderEffect.createBlurEffect(it, it, Shader.TileMode.CLAMP) } }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Canvas(modifier.onGloballyPositioned { origin = it.positionInRoot() }) {
        val m = GLASS_MARGIN.toPx()
        val area = IntSize((size.width + 2 * m).toInt(), (size.height + 2 * m).toInt())
        glass.record(size = area) { translate(-(origin.x - m), -(origin.y - m)) { drawLayer(backdrop) } }
        val p = pill(); val l = light.value
        shader.setFloatUniform("barOrigin", m, m)
        shader.setFloatUniform("barSize", size.width, size.height)
        shader.setFloatUniform("pillCenter", p.center.x + m, p.center.y + m)
        shader.setFloatUniform("pillSize", p.width, p.height)
        shader.setFloatUniform("light", l.x, l.y)
        shader.setFloatUniform("refraction", 16.dp.toPx())
        shader.setFloatUniform("dispersion", .22f)
        glass.renderEffect = RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"), blur).asComposeRenderEffect()
        translate(-m, -m) { drawLayer(glass) }
    }
}
