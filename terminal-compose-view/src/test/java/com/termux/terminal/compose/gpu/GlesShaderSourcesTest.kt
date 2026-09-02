package com.termux.terminal.compose.gpu

import org.junit.Assert.assertTrue
import org.junit.Test

class GlesShaderSourcesTest {
    @Test
    fun allShadersStartWithVersionDirectiveOnLineOne() {
        val shaders = listOf(
            "GlesShaderSources.VERTEX" to GlesShaderSources.VERTEX,
            "GlesShaderSources.FRAGMENT" to GlesShaderSources.FRAGMENT,
            "GlesShaderSources.CURSOR_VERTEX" to GlesShaderSources.CURSOR_VERTEX,
            "GlesShaderSources.CURSOR_FRAGMENT" to GlesShaderSources.CURSOR_FRAGMENT,
            "GlesImageShaderSources.VERTEX" to GlesImageShaderSources.VERTEX,
            "GlesImageShaderSources.FRAGMENT" to GlesImageShaderSources.FRAGMENT
        )

        for ((name, source) in shaders) {
            assertTrue(
                "Shader $name must start with '#version 300 es' without leading whitespace",
                source.startsWith("#version 300 es")
            )
        }
    }
}
