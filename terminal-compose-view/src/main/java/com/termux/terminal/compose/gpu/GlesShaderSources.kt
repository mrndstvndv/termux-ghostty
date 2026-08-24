package com.termux.terminal.compose.gpu

internal object GlesShaderSources {
    val VERTEX = """
        #version 300 es
        precision highp float;

        layout(location = 0) in vec4 aRect;
        layout(location = 1) in vec4 aTexRect;
        layout(location = 2) in vec4 aColor;

        uniform vec2 uViewport;

        out vec2 vTexCoord;
        out vec4 vColor;

        const vec2 QUAD_VERTICES[6] = vec2[6](
            vec2(0.0, 0.0),
            vec2(0.0, 1.0),
            vec2(1.0, 1.0),
            vec2(0.0, 0.0),
            vec2(1.0, 1.0),
            vec2(1.0, 0.0)
        );

        void main() {
            vec2 unit = QUAD_VERTICES[gl_VertexID];
            vec2 position = mix(aRect.xy, aRect.zw, unit);
            vec2 ndc = vec2(
                (position.x / uViewport.x) * 2.0 - 1.0,
                1.0 - (position.y / uViewport.y) * 2.0
            );
            gl_Position = vec4(ndc, 0.0, 1.0);
            vTexCoord = mix(aTexRect.xy, aTexRect.zw, unit);
            vColor = aColor;
        }
    """.trimIndent()

    val FRAGMENT = """
        #version 300 es
        precision mediump float;

        uniform sampler2D uAtlas;
        uniform int uTextured;
        uniform int uMaskGlyph;

        in vec2 vTexCoord;
        in vec4 vColor;
        out vec4 fragColor;

        void main() {
            if (uTextured == 0) {
                fragColor = vColor;
                return;
            }
            vec4 sampled = texture(uAtlas, vTexCoord);
            if (uMaskGlyph != 0) {
                float coverage = sampled.a;
                fragColor = vec4(vColor.rgb * coverage, vColor.a * coverage);
                return;
            }
            fragColor = sampled;
        }
    """.trimIndent()
}
