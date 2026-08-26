package com.termux.terminal.compose.gpu

internal object GlesShaderSources {
    val VERTEX = """
        #version 300 es
        precision highp float;
        precision highp int;

        layout(location = 0) in vec4 aRect;
        layout(location = 1) in vec4 aTexRect;
        layout(location = 2) in vec4 aColor;
        layout(location = 3) in uint aStyleLow;
        layout(location = 4) in uint aStyleHigh;
        layout(location = 5) in uint aStyleFlags;
        layout(location = 6) in uint aGlyphMode;

        uniform vec2 uViewport;
        uniform sampler2D uPalette;
        uniform sampler2D uRowCounts;
        uniform int uResolveStyle;
        uniform int uReverseVideo;
        uniform int uFixedRows;
        uniform int uRowStride;
        uniform float uRowHeight;
        uniform float uOffsetY;
        uniform int uRowOrigin;

        out vec2 vTexCoord;
        out vec4 vColor;
        out vec4 vBackground;
        flat out uint vInstanceValid;
        flat out uint vGlyphMode;

        const vec2 QUAD_VERTICES[4] = vec2[4](
            vec2(0.0, 0.0),
            vec2(0.0, 1.0),
            vec2(1.0, 0.0),
            vec2(1.0, 1.0)
        );

        vec4 decodeColor(uint payload, bool trueColor) {
            if (trueColor) {
                return vec4(
                    float((payload >> 16u) & 255u) / 255.0,
                    float((payload >> 8u) & 255u) / 255.0,
                    float(payload & 255u) / 255.0,
                    1.0
                );
            }
            return texelFetch(uPalette, ivec2(int(payload & 511u), 0), 0);
        }

        void resolveStyle(out vec4 foreground, out vec4 background) {
            uint effects = aStyleLow & 2047u;
            uint foregroundPayload = aStyleHigh >> 8u;
            uint backgroundPayload = (aStyleLow >> 16u) | ((aStyleHigh & 255u) << 16u);
            bool trueForeground = (effects & 512u) != 0u;
            bool trueBackground = (effects & 1024u) != 0u;
            bool bold = (effects & 9u) != 0u;
            uint foregroundIndex = foregroundPayload & 511u;
            if (!trueForeground && bold && foregroundIndex <= 7u) {
                foregroundIndex += 8u;
            }
            foreground = trueForeground
                ? decodeColor(foregroundPayload, true)
                : texelFetch(uPalette, ivec2(int(foregroundIndex), 0), 0);
            background = decodeColor(backgroundPayload, trueBackground);

            bool inverse = (effects & 16u) != 0u;
            bool overlayReverse = (aStyleFlags & 1u) != 0u;
            bool reverse = (uReverseVideo != 0 || overlayReverse) != inverse;
            if (reverse) {
                vec4 swapped = foreground;
                foreground = background;
                background = swapped;
            }
            if ((effects & 256u) != 0u) {
                foreground.rgb = floor(foreground.rgb * 255.0 * 2.0 / 3.0) / 255.0;
            }
        }

        uint rowInstanceCount(int row) {
            vec4 encoded = texelFetch(uRowCounts, ivec2(row, 0), 0);
            return uint(round(encoded.r * 255.0)) |
                (uint(round(encoded.g * 255.0)) << 8u) |
                (uint(round(encoded.b * 255.0)) << 16u) |
                (uint(round(encoded.a * 255.0)) << 24u);
        }

        void main() {
            int packetRow = 0;
            int fixedRow = 0;
            if (uFixedRows != 0) {
                packetRow = gl_InstanceID / uRowStride;
                fixedRow = packetRow + uRowOrigin;
                int slot = gl_InstanceID - packetRow * uRowStride;
                vInstanceValid = uint(slot) < rowInstanceCount(packetRow) ? 1u : 0u;
            } else {
                vInstanceValid = 1u;
            }
            if (vInstanceValid == 0u) {
                gl_Position = vec4(2.0, 2.0, 0.0, 1.0);
                vTexCoord = vec2(0.0);
                vColor = vec4(0.0);
                vBackground = vec4(0.0);
                vGlyphMode = 0u;
                return;
            }
            vec2 unit = QUAD_VERTICES[gl_VertexID];
            vec2 position = mix(aRect.xy, aRect.zw, unit);
            position.y += float(fixedRow) * uRowHeight + uOffsetY;
            vec2 ndc = vec2(
                (position.x / uViewport.x) * 2.0 - 1.0,
                1.0 - (position.y / uViewport.y) * 2.0
            );
            gl_Position = vec4(ndc, 0.0, 1.0);
            vTexCoord = mix(aTexRect.xy, aTexRect.zw, unit);
            bool directColor = (aStyleFlags & 2u) != 0u;
            if (uResolveStyle != 0 && !directColor) {
                resolveStyle(vColor, vBackground);
            } else {
                vColor = aColor;
                vBackground = aColor;
            }
            vGlyphMode = aGlyphMode;
        }
    """.trimIndent()

    val FRAGMENT = """
        #version 300 es
        precision mediump float;

        uniform sampler2D uAtlas;
        uniform int uTextured;
        uniform int uStyleBackground;

        in vec2 vTexCoord;
        in vec4 vColor;
        in vec4 vBackground;
        flat in uint vInstanceValid;
        flat in uint vGlyphMode;
        out vec4 fragColor;

        void main() {
            if (vInstanceValid == 0u) discard;
            if (uStyleBackground != 0) {
                fragColor = vBackground;
                return;
            }
            if (uTextured == 0) {
                fragColor = vColor;
                return;
            }
            vec4 sampled = texture(uAtlas, vTexCoord);
            if (vGlyphMode == 1u) {
                float coverage = sampled.a;
                fragColor = vec4(vColor.rgb * coverage, vColor.a * coverage);
                return;
            }
            fragColor = sampled;
        }
    """.trimIndent()

    val CURSOR_VERTEX = """
        #version 300 es
        precision highp float;

        layout(location = 0) in vec2 aPosition;
        uniform vec2 uViewport;
        uniform float uOffsetY;
        out vec2 vPosition;

        void main() {
            vec2 position = aPosition;
            position.y += uOffsetY;
            vec2 ndc = vec2(
                (position.x / uViewport.x) * 2.0 - 1.0,
                1.0 - (position.y / uViewport.y) * 2.0
            );
            gl_Position = vec4(ndc, 0.0, 1.0);
            vPosition = position;
        }
    """.trimIndent()

    val CURSOR_FRAGMENT = """
        #version 300 es
        precision mediump float;

        uniform vec4 uColor;
        uniform vec4 uCutout;
        in vec2 vPosition;
        out vec4 fragColor;

        void main() {
            if (vPosition.x >= uCutout.x && vPosition.x <= uCutout.z &&
                vPosition.y >= uCutout.y && vPosition.y <= uCutout.w) {
                discard;
            }
            fragColor = uColor;
        }
    """.trimIndent()
}
