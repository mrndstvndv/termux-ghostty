package com.termux.terminal.compose.gpu

internal object GlesShaderSources {
    val VERTEX = """
        #version 300 es
        precision highp float;

        layout(location = 0) in vec2 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        layout(location = 2) in vec4 aColor;

        uniform vec2 uViewport;

        out vec2 vTexCoord;
        out vec4 vColor;

        void main() {
            vec2 ndc = vec2(
                (aPosition.x / uViewport.x) * 2.0 - 1.0,
                1.0 - (aPosition.y / uViewport.y) * 2.0
            );
            gl_Position = vec4(ndc, 0.0, 1.0);
            vTexCoord = aTexCoord;
            vColor = aColor;
        }
    """.trimIndent()

    val FRAGMENT = """
        #version 300 es
        precision mediump float;

        uniform sampler2D uAtlas;
        uniform int uTextured;

        in vec2 vTexCoord;
        in vec4 vColor;
        out vec4 fragColor;

        void main() {
            fragColor = uTextured == 0 ? vColor : texture(uAtlas, vTexCoord);
        }
    """.trimIndent()
}
