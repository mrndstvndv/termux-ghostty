package com.termux.terminal.compose.internal

import android.graphics.RuntimeShader
import android.os.Build
import com.termux.terminal.compose.ShaderCompileResult
import com.termux.terminal.compose.ShaderDefinition
import com.termux.terminal.compose.ShaderHandle
import com.termux.terminal.compose.TerminalDiagnostic
import java.nio.charset.StandardCharsets

internal const val MaxShaderSourceBytes = 256 * 1024
internal const val MaxShaderDefinitions = 8
internal const val MaxShaderChainDepth = 4

internal class CompiledShader(
    val definition: ShaderDefinition,
    val shader: RuntimeShader
) : ShaderHandle

/**
 * Validates and compiles consumer [ShaderDefinition]s into [CompiledShader]s.
 *
 * Compilation is result-based: failures degrade to a skipped effect plus a
 * [TerminalDiagnostic] instead of crashing through composition. On API < 33 no
 * runtime-shader class is loaded or instantiated; the canvas renders normally.
 */
internal class TerminalShaderCompiler(
    private val onDiagnostics: (TerminalDiagnostic) -> Unit
) {

    /**
     * Compiles the given definitions in order. Results are reported through
     * [ShaderCompileResult] only on platform success; every invalid definition
     * is skipped and diagnosed. The returned list is bounded by
     * [MaxShaderDefinitions] and [MaxShaderChainDepth].
     */
    fun compile(definitions: List<ShaderDefinition>): List<CompiledShader> {
        if (definitions.isEmpty()) return emptyList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onDiagnostics(TerminalDiagnostic.ShaderUnsupported("RuntimeShader requires API 33+"))
            return emptyList()
        }

        val chain = definitions.take(MaxShaderDefinitions)
        if (chain.size < definitions.size) {
            onDiagnostics(
                TerminalDiagnostic.ShaderLimited(
                    definitions[MaxShaderDefinitions].id,
                    "more than $MaxShaderDefinitions definitions"
                )
            )
        }

        val compiled = mutableListOf<CompiledShader>()
        for (definition in chain) {
            val skipReason = when {
                compiled.size >= MaxShaderChainDepth ->
                    TerminalDiagnostic.ShaderLimited(
                        definition.id,
                        "chain depth limited to $MaxShaderChainDepth"
                    )
                else -> validate(definition)?.let {
                    TerminalDiagnostic.ShaderFailed(definition.id, it)
                }
            }
            if (skipReason != null) {
                onDiagnostics(skipReason)
                continue
            }
            val shader = try {
                RuntimeShader(definition.source)
            } catch (error: IllegalArgumentException) {
                onDiagnostics(
                    TerminalDiagnostic.ShaderFailed(
                        definition.id,
                        "compile failed: ${error.message}"
                    )
                )
                null
            }
            if (shader != null) {
                compiled.add(CompiledShader(definition, shader))
            }
        }
        return compiled
    }

    private fun validate(definition: ShaderDefinition): String? {
        val errors = mutableListOf<String>()
        if (definition.id.isBlank()) {
            errors.add("id must not be blank")
        }
        val bytes = definition.source.toByteArray(StandardCharsets.UTF_8).size
        if (bytes > MaxShaderSourceBytes) {
            errors.add("source exceeds $MaxShaderSourceBytes bytes")
        }
        if (definition.source.isBlank()) {
            errors.add("source must not be blank")
        }
        if (definition.requiresContentInput && !CONTENT_UNIFORM_PATTERN.containsMatchIn(definition.source)) {
            errors.add("source must declare `uniform shader content`")
        }
        if (!MAIN_FUNCTION_PATTERN.containsMatchIn(definition.source)) {
            errors.add("source must define `main(vec2)`")
        }
        if (definition.usesTimeUniform && !uniformFloatPattern("time").containsMatchIn(definition.source)) {
            errors.add("usesTimeUniform=true but `uniform float time` is not declared")
        }
        if (definition.usesResolutionUniform &&
            !uniformVec2Pattern("resolution").containsMatchIn(definition.source)
        ) {
            errors.add("usesResolutionUniform=true but `uniform vec2 resolution` is not declared")
        }
        for (match in UNIFORM_PATTERN.findAll(definition.source)) {
            val name = match.groupValues[1]
            if (name !in ALLOWED_UNIFORMS) {
                errors.add("unsupported uniform `$name`")
            }
        }
        return errors.firstOrNull()
    }

    private companion object {
        val CONTENT_UNIFORM_PATTERN = Regex("uniform\\s+shader\\s+content\\s*;")
        val MAIN_FUNCTION_PATTERN = Regex("(?:half4|vec4)\\s+main\\s*\\(\\s*vec2\\b")
        val UNIFORM_PATTERN = Regex("uniform\\s+(?:float|vec2|shader)\\s+([A-Za-z_]\\w*)\\s*;")
        val ALLOWED_UNIFORMS = setOf("content", "time", "resolution")

        fun uniformFloatPattern(name: String): Regex =
            Regex("uniform\\s+float\\s+$name\\s*;")

        fun uniformVec2Pattern(name: String): Regex =
            Regex("uniform\\s+vec2\\s+$name\\s*;")
    }
}

/** Pushes only the uniforms declared by the compiled shader. */
internal fun CompiledShader.updateUniforms(
    timeSeconds: Float,
    width: Float,
    height: Float,
    updateResolution: Boolean
) {
    if (definition.usesTimeUniform) {
        shader.setFloatUniform("time", timeSeconds)
    }
    if (updateResolution && definition.usesResolutionUniform) {
        shader.setFloatUniform("resolution", width, height)
    }
}
