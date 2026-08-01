package com.mrndtvndv.term.ui.workspace

import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

private const val TAG = "TerminalEffect"

/**
 * Whole-frame post-processing effects for the terminal view.
 *
 * Two tiers:
 * - Android 13+ (API 33+): real AGSL shaders via [RuntimeShader] + RenderEffect,
 *   applied by wrapping the terminal frame render in a saveLayer.
 * - Older devices: lightweight Compose overlays for CRT / scanlines / vignette.
 *   Glitch and Matrix Rain are shader-only and inert below API 33.
 */
enum class TerminalEffect(val key: String, val label: String, val animated: Boolean) {
    NONE("none", "None", false),
    CRT("crt", "CRT", false),
    SCANLINES("scanlines", "Scanlines", false),
    VIGNETTE("vignette", "Vignette", false),
    GLITCH("glitch", "Glitch", true),
    MATRIX("matrix", "Matrix", true);

    companion object {
        fun fromPref(value: String?): TerminalEffect =
            entries.firstOrNull { it.key == value } ?: NONE
    }
}

/** AGSL sources. Every declared uniform is referenced so it survives optimization.
 * CRT, Glitch and Matrix are ports of ghostty-shaders (github.com/0xhckr/ghostty-shaders):
 * crt.glsl (CRTS by Timothy Lottes, UNLICENSE), glitchy.glsl (shadertoy wld3WN),
 * matrix-hallway.glsl ([SH17A] by Reinder Nijhoff, CC BY-NC-SA 4.0). */
private object AgslSources {

    const val CRT = """
        // Port of ghostty-shaders/crt.glsl — [CRTS] Public Domain CRT-Styled Scalar by
        // Timothy Lottes (adapted for Ghostty by Qwerasd), UNLICENSE. See:
        // https://gist.github.com/qwerasd205/c3da6c610c8ffe17d6d2d3cc7068f17f
        // Adapted to AGSL: the content texture is already full screen resolution, so the
        // internal SCALE is 1.0 (one texel per screen pixel).
        uniform shader content;
        uniform vec2 resolution;

        // Scanline thinness / horizontal blur / shadow-mask darkening / corner vignette
        const float INPUT_THIN = 0.75;
        const float INPUT_BLUR = -2.75;
        const float INPUT_MASK = 0.65;
        const float MIN_VIN = 0.5;

        float FromSrgb1(float c) {
            return (c <= 0.04045) ? c * (1.0 / 12.92) :
                pow(c * (1.0 / 1.055) + (0.055 / 1.055), 2.4);
        }
        vec3 FromSrgb(vec3 c) {
            return vec3(FromSrgb1(c.r), FromSrgb1(c.g), FromSrgb1(c.b));
        }

        // Sample the content texture at a normalized uv; clamp so out-of-bounds evals
        // (which would return transparent black) can never happen.
        vec3 CrtsFetch(vec2 uv) {
            vec2 bounds = max(resolution - vec2(1.0), vec2(0.0));
            return FromSrgb(content.eval(clamp(uv * resolution, vec2(0.0), bounds)).rgb);
        }

        vec2 CrtsTone(float thin, float mask) {
            vec2 ret;
            float midOut = 0.18 / ((1.5 - thin) * (0.5 * mask + 0.5));
            float pMidIn = 0.18;
            ret.x = ((-pMidIn) + midOut) / ((1.0 - pMidIn) * midOut);
            ret.y = ((-pMidIn) * midOut + pMidIn) / (midOut * (-pMidIn) + midOut);
            return ret;
        }

        // CRTS_MASK_SHADOW: diagonal rgb triads
        vec3 CrtsMask(vec2 pos, float dark) {
            pos.x += pos.y * 3.0;
            vec3 m = vec3(dark, dark, dark);
            float x = fract(pos.x * (1.0 / 6.0));
            if (x < (1.0 / 3.0)) m.r = 1.0;
            else if (x < (2.0 / 3.0)) m.g = 1.0;
            else m.b = 1.0;
            return m;
        }

        vec3 CrtsFilter(
            vec2 ipos,
            vec2 halfInputSize,
            vec2 rcpInputSize,
            vec2 rcpOutputSize,
            vec2 twoDivOutputSize,
            float inputHeight,
            vec2 warp,
            float thin,
            float blur,
            float mask,
            vec2 tone
        ) {
            // Optional apply warp (CRTS_WARP): convert to {-1 to 1} range
            vec2 pos = ipos * twoDivOutputSize - vec2(1.0, 1.0);

            // Distort pushes image outside {-1 to 1} range
            pos *= vec2(
                1.0 + (pos.y * pos.y) * warp.x,
                1.0 + (pos.x * pos.x) * warp.y);

            // Vignette
            float vin = 1.0 - (
                (1.0 - clamp(pos.x * pos.x, 0.0, 1.0)) * (1.0 - clamp(pos.y * pos.y, 0.0, 1.0)));
            vin = clamp((-vin) * inputHeight + inputHeight, 0.0, 1.0);

            // Leave in {0 to inputSize}
            pos = pos * halfInputSize + halfInputSize;

            // Snap to center of first scanline / one of four pixels
            float y0 = floor(pos.y - 0.5) + 0.5;
            float x0 = floor(pos.x - 1.5) + 0.5;

            // Initial UV position; fetch 4 nearest texels from 2 nearest scanlines
            vec2 p = vec2(x0 * rcpInputSize.x, y0 * rcpInputSize.y);
            vec3 colA0 = CrtsFetch(p);
            p.x += rcpInputSize.x;
            vec3 colA1 = CrtsFetch(p);
            p.x += rcpInputSize.x;
            vec3 colA2 = CrtsFetch(p);
            p.x += rcpInputSize.x;
            vec3 colA3 = CrtsFetch(p);
            p.y += rcpInputSize.y;
            vec3 colB3 = CrtsFetch(p);
            p.x -= rcpInputSize.x;
            vec3 colB2 = CrtsFetch(p);
            p.x -= rcpInputSize.x;
            vec3 colB1 = CrtsFetch(p);
            p.x -= rcpInputSize.x;
            vec3 colB0 = CrtsFetch(p);

            // Vertical filter: scanline intensity is a sine wave
            float off = pos.y - y0;
            float pi2 = 6.28318530717958;
            float hlf = 0.5;
            float scanA = cos(min(0.5, off * thin) * pi2) * hlf + hlf;
            float scanB = cos(min(0.5, (-off) * thin + thin) * pi2) * hlf + hlf;

            // Horizontal kernel is a simple gaussian filter
            float off0 = pos.x - x0;
            float off1 = off0 - 1.0;
            float off2 = off0 - 2.0;
            float off3 = off0 - 3.0;
            float pix0 = exp2(blur * off0 * off0);
            float pix1 = exp2(blur * off1 * off1);
            float pix2 = exp2(blur * off2 * off2);
            float pix3 = exp2(blur * off3 * off3);
            float pixT = 1.0 / (pix0 + pix1 + pix2 + pix3);

            // Get rid of wrong pixels on edge
            pixT *= max(MIN_VIN, vin);

            scanA *= pixT;
            scanB *= pixT;

            // Apply horizontal and vertical filters
            vec3 color =
                (colA0 * pix0 + colA1 * pix1 + colA2 * pix2 + colA3 * pix3) * scanA +
                (colB0 * pix0 + colB1 * pix1 + colB2 * pix2 + colB3 * pix3) * scanB;

            // Apply phosphor mask
            color *= CrtsMask(ipos, mask);

            // Tonal control, start by protecting from /0
            float peak = max(1.0 / (256.0 * 65536.0),
                max(color.r, max(color.g, color.b)));
            // Compute the ratios of {R,G,B}
            vec3 ratio = color * (1.0 / peak);
            // Apply tonal curve to peak value
            peak = peak * (1.0 / (peak * tone.x + tone.y));
            // Reconstruct color
            return ratio * peak;
        }

        float ToSrgb1(float c) {
            return (c < 0.0031308 ? c * 12.92 : 1.055 * pow(c, 0.41666) - 0.055);
        }
        vec3 ToSrgb(vec3 c) {
            return vec3(ToSrgb1(c.r), ToSrgb1(c.g), ToSrgb1(c.b));
        }

        half4 main(vec2 fragCoord) {
            float aspect = resolution.x / max(resolution.y, 1.0);
            vec3 col = CrtsFilter(
                fragCoord.xy,
                resolution * 0.5,
                1.0 / resolution,
                1.0 / resolution,
                2.0 / resolution,
                resolution.y,
                vec2(1.0 / (50.0 * aspect), 1.0 / 50.0),
                INPUT_THIN,
                INPUT_BLUR,
                INPUT_MASK,
                CrtsTone(INPUT_THIN, INPUT_MASK)
            );
            // Linear to SRGB for output
            return half4(ToSrgb(col), 1.0);
        }
    """

    const val SCANLINES = """
        uniform shader content;

        half4 main(vec2 xy) {
            half4 c = content.eval(xy);
            float line = cos(xy.y * 3.14159265);
            // darken odd pixel rows (authentic CRT look)
            c.rgb *= 0.82 + 0.18 * line;
            // faint light lines on even rows so the pattern is visible on dark backgrounds too
            c.rgb += vec3(0.045) * (1.0 + line) * 0.5;
            return half4(c.rgb, 1.0);
        }
    """

    const val VIGNETTE = """
        uniform shader content;
        uniform vec2 resolution;

        half4 main(vec2 xy) {
            half4 c = content.eval(xy);
            vec2 d = xy / max(resolution, vec2(1.0)) - 0.5;
            float vig = 1.0 - dot(d, d) * 2.1;
            return half4(c.rgb * clamp(vig, 0.2, 1.0), 1.0);
        }
    """

    const val GLITCH = """
        // Port of ghostty-shaders/glitchy.glsl — modified version of shadertoy wld3WN.
        // 10s loop, glitch triggers during the first 10% of it; analog distortion from
        // gradient noise, chromatic aberration, plus per-frame white noise.
        // Adapted to AGSL: uint hash replaced with a float hash (AGSL has no uints),
        // textureLod -> clamped content.eval in pixel space, iFrame -> floor(time*60).
        uniform shader content;
        uniform float time;
        uniform vec2 resolution;

        // seconds for which the glitch loop occurs
        const float DURATION = 10.0;
        // percentage of the duration for which the glitch is triggered
        const float AMT = 0.1;

        float SS(float a, float b, float x) {
            return smoothstep(a, b, x) * smoothstep(b, a, x);
        }

        // Hash by David Hoskins, float variant (original used uint arithmetic)
        vec3 hash33(vec3 p) {
            p = fract(p * vec3(0.1031, 0.1030, 0.0973));
            p += dot(p, p.yxz + 33.33);
            return fract((p.xxy + p.yxx) * p.zyx) * 2.0 - 1.0;
        }

        // Gradient noise by iq
        float gnoise(vec3 x) {
            // grid
            vec3 p = floor(x);
            vec3 w = fract(x);

            // quintic interpolant
            vec3 u = w * w * w * (w * (w * 6.0 - 15.0) + 10.0);

            // gradients
            vec3 ga = hash33(p + vec3(0.0, 0.0, 0.0));
            vec3 gb = hash33(p + vec3(1.0, 0.0, 0.0));
            vec3 gc = hash33(p + vec3(0.0, 1.0, 0.0));
            vec3 gd = hash33(p + vec3(1.0, 1.0, 0.0));
            vec3 ge = hash33(p + vec3(0.0, 0.0, 1.0));
            vec3 gf = hash33(p + vec3(1.0, 0.0, 1.0));
            vec3 gg = hash33(p + vec3(0.0, 1.0, 1.0));
            vec3 gh = hash33(p + vec3(1.0, 1.0, 1.0));

            // projections
            float va = dot(ga, w - vec3(0.0, 0.0, 0.0));
            float vb = dot(gb, w - vec3(1.0, 0.0, 0.0));
            float vc = dot(gc, w - vec3(0.0, 1.0, 0.0));
            float vd = dot(gd, w - vec3(1.0, 1.0, 0.0));
            float ve = dot(ge, w - vec3(0.0, 0.0, 1.0));
            float vf = dot(gf, w - vec3(1.0, 0.0, 1.0));
            float vg = dot(gg, w - vec3(0.0, 1.0, 1.0));
            float vh = dot(gh, w - vec3(1.0, 1.0, 1.0));

            // interpolation
            float gNoise = va + u.x * (vb - va) +
                u.y * (vc - va) +
                u.z * (ve - va) +
                u.x * u.y * (va - vb - vc + vd) +
                u.y * u.z * (va - vc - ve + vg) +
                u.z * u.x * (va - vb - ve + vf) +
                u.x * u.y * u.z * (-va + vb + vc - vd + ve - vf - vg + vh);

            return 2.0 * gNoise;
        }

        // gradient noise in range [0, 1]
        float gnoise01(vec3 x) {
            return 0.5 + 0.5 * gnoise(x);
        }

        half4 main(vec2 fragCoord) {
            vec2 uv = fragCoord / max(resolution, vec2(1.0));
            float t = time;

            // smoothed interval for which the glitch gets triggered
            float glitchAmount = SS(DURATION * 0.001, DURATION * AMT, mod(t, DURATION));
            float displayNoise = 0.0;
            vec3 col = vec3(0.0);
            vec2 eps = vec2(5.0 / max(resolution.x, 1.0), 0.0);
            vec2 st = vec2(0.0);

            // analog distortion
            float y = uv.y * resolution.y;
            float distortion = gnoise(vec3(0.0, y * 0.01, t * 500.0)) * (glitchAmount * 4.0 + 0.1);
            distortion *= gnoise(vec3(0.0, y * 0.02, t * 250.0)) * (glitchAmount * 2.0 + 0.025);

            ++displayNoise;
            distortion += smoothstep(0.999, 1.0, sin((uv.y + t * 1.6) * 2.0)) * 0.02;
            distortion -= smoothstep(0.999, 1.0, sin((uv.y + t) * 2.0)) * 0.02;
            st = uv + vec2(distortion, 0.0);

            // chromatic aberration; clamp so out-of-bounds evals (transparent black) never happen
            vec2 bounds = max(resolution - vec2(1.0), vec2(0.0));
            col.r += content.eval(clamp((st + eps + distortion) * resolution, vec2(0.0), bounds)).r;
            col.g += content.eval(clamp(st * resolution, vec2(0.0), bounds)).g;
            col.b += content.eval(clamp((st - eps - distortion) * resolution, vec2(0.0), bounds)).b;

            // white noise + scanlines
            displayNoise = 0.2 * clamp(displayNoise, 0.0, 1.0);
            col += (0.15 + 0.65 * glitchAmount) *
                hash33(vec3(fragCoord, mod(floor(t * 60.0), 1000.0))).r * displayNoise;
            col -= (0.25 + 0.75 * glitchAmount) *
                (sin(4.0 * t + uv.y * resolution.y * 1.75)) * displayNoise;
            return half4(col, 1.0);
        }
    """

    const val MATRIX = """
        // Port of ghostty-shaders/matrix-hallway.glsl — [SH17A] Matrix rain by
        // Reinder Nijhoff 2017 (CC BY-NC-SA 4.0), https://www.shadertoy.com/view/ldjBW1
        // Adapted to AGSL: texture(iChannel0) -> content.eval, iResolution.z -> 1.0,
        // macros -> functions.
        uniform shader content;
        uniform float time;
        uniform vec2 resolution;

        const float SPEED_MULTIPLIER = 1.0;
        const float GREEN_ALPHA = 0.33;
        const float BLACK_BLEND_THRESHOLD = 0.4;

        float R(vec3 p) {
            return fract(100.0 * sin(p.x * 8.0 + p.y));
        }

        half4 main(vec2 fragCoord) {
            vec3 res3 = vec3(max(resolution, vec2(1.0)), 1.0);
            vec3 v = vec3(fragCoord, 1) / res3 - 0.5;
            // scale?
            vec3 s = 0.9 / abs(v);
            s.z = min(s.y, s.x);
            vec3 i = ceil(800.0 * s.z * (s.y < s.x ? v.xzz : v.zyz)) * 0.1;
            vec3 j = fract(i);
            i -= j;
            vec3 p = vec3(9, int(time * SPEED_MULTIPLIER * (9.0 + 8.0 * sin(i).x)), 0) + i;
            vec3 col = content.eval(fragCoord).rgb;
            col.g = R(p) / s.z;
            p *= j;
            col *= (R(p) > 0.5 && j.x < 0.6 && j.y < 0.8) ? GREEN_ALPHA : 0.0;

            // Sample the terminal screen texture including alpha channel
            vec4 terminalColor = content.eval(fragCoord);

            float alpha = step(length(terminalColor.rgb), BLACK_BLEND_THRESHOLD);
            vec3 blendedColor = mix(terminalColor.rgb * 1.2, col, alpha);

            return half4(blendedColor, terminalColor.a);
        }
    """
}

/**
 * Creates the AGSL shader for [effect] on API 33+, or null when unavailable.
 * Compile failures degrade to null (effect simply doesn't render) instead of crashing.
 */
@Suppress("SwallowedException") // invalid AGSL for this device — degrade to no effect instead of crashing
@Composable
fun rememberRuntimeShader(effect: TerminalEffect): RuntimeShader? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return remember(effect) {
        if (effect == TerminalEffect.NONE) return@remember null
        try {
            RuntimeShader(
                when (effect) {
                    TerminalEffect.CRT -> AgslSources.CRT
                    TerminalEffect.SCANLINES -> AgslSources.SCANLINES
                    TerminalEffect.VIGNETTE -> AgslSources.VIGNETTE
                    TerminalEffect.GLITCH -> AgslSources.GLITCH
                    TerminalEffect.MATRIX -> AgslSources.MATRIX
                    TerminalEffect.NONE -> ""
                }
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "AGSL compile failed for ${effect.label}", e)
            null
        }
    }
}

/** Pushes per-frame uniforms. Failures are ignored — shaders that stripped a uniform must not crash us. */
@Suppress("SwallowedException") // uniforms optimized out on this device — nothing to update
fun RuntimeShader.updateUniforms(timeSeconds: Float, width: Float, height: Float) {
    try {
        setFloatUniform("time", timeSeconds)
    } catch (e: IllegalArgumentException) {
        // uniform optimized out on this device — nothing to update
    }
    try {
        setFloatUniform("resolution", width, height)
    } catch (e: IllegalArgumentException) {
        // uniform optimized out on this device — nothing to update
    }
}

/**
 * Legacy overlay for API < 33 where RuntimeShader is unavailable.
 * CRT = scanlines + vignette; scanlines and vignette render individually too.
 * Glitch and Matrix Rain have no legacy path.
 */
fun DrawScope.drawLegacyOverlay(effect: TerminalEffect) {
    when (effect) {
        TerminalEffect.CRT, TerminalEffect.SCANLINES -> {
            val scanColor = Color.Black.copy(alpha = 0.22f)
            var y = 1f
            while (y < size.height) {
                drawRect(
                    color = scanColor,
                    topLeft = Offset(0f, y),
                    size = Size(size.width, 1f)
                )
                y += 2f
            }
        }
        else -> Unit
    }
    when (effect) {
        TerminalEffect.CRT, TerminalEffect.VIGNETTE -> {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.42f)),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = maxOf(size.width, size.height) * 0.72f
                )
            )
        }
        else -> Unit
    }
}
