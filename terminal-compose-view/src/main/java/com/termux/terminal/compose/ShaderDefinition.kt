package com.termux.terminal.compose

import java.nio.charset.StandardCharsets

/**
 * A consumer-defined AGSL post-processing shader definition.
 *
 * The consumer supplies the ID, source, and uniform metadata; the library owns
 * compilation, validation, render-effect binding, and release. The core
 * provides no built-in sources, labels, or visual presets — an ID and consumer
 * metadata are the whole contract.
 *
 * A definition may be a post-process that samples the terminal content through
 * an input shader named `content` (the default). Shaders declaring a `time` or
 * `resolution` uniform should set [usesTimeUniform] / [usesResolutionUniform]
 * so the canvas keeps those uniforms fresh.
 */
data class ShaderDefinition(
    val id: String,
    val source: String,
    val usesTimeUniform: Boolean = false,
    val usesResolutionUniform: Boolean = false,
    val requiresContentInput: Boolean = true
) {
    val sourceBytes: Int
        get() = source.toByteArray(StandardCharsets.UTF_8).size
}

/**
 * Result of validating and compiling [ShaderDefinition] sources.
 *
 * The library returns structured results instead of throwing through
 * composition: [Success] carries a handle the canvas uses to bind the shader,
 * [Unsupported] reports that the platform cannot run runtime shaders (API <
 * 33), and [Invalid] reports a validation or compilation failure. The canvas
 * renders the terminal normally when any definition fails.
 */
sealed interface ShaderCompileResult {
    class Success(val handle: ShaderHandle) : ShaderCompileResult
    class Unsupported(val reason: String) : ShaderCompileResult
    class Invalid(val definitionId: String, val reason: String) : ShaderCompileResult
}

/**
 * Opaque handle to a compiled runtime shader. The handle is owned by the
 * library's shader cache; consumers only compare or retain it while the canvas
 * is composed.
 */
interface ShaderHandle

/** Diagnostic event reported through [TerminalCanvasConfig.onDiagnostics]. */
sealed interface TerminalDiagnostic {
    /** A shader failed validation or compilation; the effect is skipped. */
    data class ShaderFailed(val definitionId: String, val reason: String) : TerminalDiagnostic

    /** A shader chain was limited by a configured bound. */
    data class ShaderLimited(val definitionId: String, val reason: String) : TerminalDiagnostic

    /** The platform cannot run runtime shaders; effects are disabled. */
    data class ShaderUnsupported(val reason: String) : TerminalDiagnostic

    /** A recoverable backend error (e.g. resize or command failure). */
    data class BackendError(val code: Int, val message: String) : TerminalDiagnostic
}
