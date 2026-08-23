package com.termux.terminal.compose.gpu

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val MaxQuadsPerBatch = 4096
private const val VerticesPerQuad = 6
private const val VertexStrideBytes = 8 * Float.SIZE_BYTES
private const val VertexBufferBytes = MaxQuadsPerBatch * VerticesPerQuad * VertexStrideBytes
private const val DiagnosticFrameInterval = 16L

/** Redundant callbacks remain complete framebuffer presentations. */
internal data class GlesPresentationDecision(
    val requiresCompleteFramebuffer: Boolean,
    val redundant: Boolean
)

internal fun glesPresentationDecision(
    snapshot: GlesTerminalSnapshot,
    presentedSnapshot: GlesTerminalSnapshot?,
    animationTime: Float,
    lastAnimationTime: Float,
    atlasReset: Boolean
): GlesPresentationDecision {
    val redundant = !atlasReset &&
        presentedSnapshot === snapshot &&
        animationTime == lastAnimationTime
    return GlesPresentationDecision(
        requiresCompleteFramebuffer = true,
        redundant = redundant
    )
}

/** All GLES calls and mutable GL resources are confined to this renderer thread. */
@Suppress("TooManyFunctions")
internal class GlesTerminalRenderer(
    private val surface: GlesTerminalSurface,
    private val requestRender: () -> Unit
) : GLSurfaceView.Renderer {
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(MaxQuadsPerBatch * VerticesPerQuad * VertexStrideBytes)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private var resources: GlesResources? = null
    private var lastSnapshot: GlesTerminalSnapshot? = null
    private var presentedSnapshot: GlesTerminalSnapshot? = null
    private var lastAnimationTime = Float.NaN
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var generation = 0L
    private var drawCount = 0L
    private var skippedDrawCount = 0L
    private var lastError: String? = null
    private var pendingGlyphFlush: (() -> Unit)? = null
    private var vendor = "unknown"
    private var renderer = "unknown"
    private var version = "unknown"
    private var shadingLanguageVersion = "unknown"
    private var unsupportedAgslGeneration = Long.MIN_VALUE
    private var releasedOnGlThread = false

    @Suppress("TooGenericExceptionCaught")
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        releaseOnGlThread()
        if (surface.isReleased()) return
        releasedOnGlThread = false
        generation++
        vendor = GLES30.glGetString(GL10.GL_VENDOR) ?: "unknown"
        renderer = GLES30.glGetString(GL10.GL_RENDERER) ?: "unknown"
        version = GLES30.glGetString(GL10.GL_VERSION) ?: "unknown"
        shadingLanguageVersion = GLES30.glGetString(GLES30.GL_SHADING_LANGUAGE_VERSION) ?: "unknown"
        unsupportedAgslGeneration = Long.MIN_VALUE
        presentedSnapshot = null
        lastError = null

        if (!version.startsWith("OpenGL ES 3.")) {
            reportError("context", "GLES 3.0 is unavailable: $version")
            return
        }

        val maxTextureSize = queryMaxTextureSize()
        if (maxTextureSize < 32) {
            reportError("context", "GL_MAX_TEXTURE_SIZE is too small: $maxTextureSize")
            return
        }
        val limits = GlyphAtlasLimits.forGlMaxTextureSize(maxTextureSize)
        resources = try {
            createResources(limits)
        } catch (error: GlesResourceException) {
            reportError("resource", error.message ?: "failed to create GLES resources")
            null
        } catch (error: GlesProgramException) {
            reportError("program", error.message ?: "failed to create GLES resources")
            null
        } catch (error: RuntimeException) {
            reportError("resource", error.message ?: "failed to create GLES resources")
            null
        }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        checkGlError("surface-created")
        reportState(force = true)
        requestRender()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (surface.isReleased()) return
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        checkGlError("viewport")
        reportState(force = true)
        requestRender()
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount", "TooGenericExceptionCaught")
    override fun onDrawFrame(gl: GL10?) {
        if (surface.isReleased()) {
            releaseOnGlThread()
            return
        }
        val nextSnapshot = surface.acquireSnapshot()
        if (nextSnapshot != null) lastSnapshot = nextSnapshot
        val snapshot = lastSnapshot
        val currentResources = resources
        if (snapshot == null || currentResources == null) {
            skippedDrawCount++
            clear(0xFF000000.toInt())
            return
        }

        val animationTime = surface.animationTimeSeconds()
        val atlasReset = surface.consumeAtlasReset()
        // GLSurfaceView swaps after every callback; redundant callbacks must still paint a
        // complete framebuffer instead of presenting an untouched back buffer.
        val presentationDecision = glesPresentationDecision(
                snapshot = snapshot,
                presentedSnapshot = presentedSnapshot,
                animationTime = animationTime,
                lastAnimationTime = lastAnimationTime,
                atlasReset = atlasReset
            )
        if (presentationDecision.redundant) {
            skippedDrawCount++
        }
        val unsupportedShaders = snapshot.visual.agslShaders
        if (unsupportedShaders.isNotEmpty() && unsupportedAgslGeneration != generation) {
            unsupportedAgslGeneration = generation
            surface.reportDiagnostic(
                GlesTerminalDiagnostic.UnsupportedAgsl(
                    state = diagnosticState(),
                    shaderIds = unsupportedShaders.map { it.id }.take(8),
                    reason = "AGSL ShaderDefinition sources are not GLSL ES 3.00"
                )
            )
        }

        val plan = try {
            if (atlasReset) {
                currentResources.atlas.reset { pendingGlyphFlush?.invoke() }
            }
            TerminalRenderPlanner().plan(snapshot)
        } catch (error: GlesRendererException) {
            presentFallback(snapshot, "plan", error.message ?: "GLES plan failed")
            return
        } catch (error: RuntimeException) {
            presentFallback(snapshot, "plan", error.message ?: "GLES plan failed")
            return
        }

        try {
            clear(backgroundColor(snapshot))
            drawColoredQuads(currentResources, plan.cellBackgrounds)
            drawColoredQuads(currentResources, plan.cursorQuads)
            // Glyph batches are keyed by atlas page and generation; reset callbacks flush old
            // batches before their textures are deleted, while the ring fences each submission.
            drawGlyphs(currentResources, plan.glyphs)
            drawColoredQuads(currentResources, plan.decorations)
            if (checkGlError("frame")) {
                throw GlesResourceException("frame reported a GLES error")
            }
        } catch (error: GlesRendererException) {
            presentFallback(snapshot, "frame", error.message ?: "GLES frame failed")
            return
        } catch (error: RuntimeException) {
            presentFallback(snapshot, "frame", error.message ?: "GLES frame failed")
            return
        }

        drawCount++
        presentedSnapshot = snapshot
        lastAnimationTime = animationTime
        surface.notifyFramePresented()
        reportState(
            force = atlasReset || drawCount == 1L || drawCount % DiagnosticFrameInterval == 0L
        )
    }

    /** Called through GLSurfaceView.queueEvent; never from the Compose thread. */
    @Suppress("TooGenericExceptionCaught")
    fun releaseOnGlThread() {
        if (releasedOnGlThread) return
        releasedOnGlThread = true
        val currentResources = resources
        resources = null
        presentedSnapshot = null
        pendingGlyphFlush = null
        try {
            currentResources?.release()
        } catch (error: RuntimeException) {
            reportError("resource-release", error.message ?: "GLES resource release failed")
        }
    }

    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    private fun createResources(limits: GlyphAtlasLimits): GlesResources {
        val program = GlesProgram.create()
        return try {
            GlesResources(
                program = program,
                bufferRing = GlesBufferRing.create(VertexBufferBytes),
                atlas = GlesGlyphAtlas(limits)
            )
        } catch (error: GlesRendererException) {
            program.release()
            throw error
        } catch (error: RuntimeException) {
            program.release()
            throw GlesResourceException("GLES resource setup failed", error)
        }
    }

    private fun drawColoredQuads(resources: GlesResources, quads: List<TerminalQuad>) {
        if (quads.isEmpty()) return
        resources.program.bind(viewportWidth, viewportHeight, textured = false)
        var offset = 0
        while (offset < quads.size) {
            val end = minOf(offset + MaxQuadsPerBatch, quads.size)
            vertexBuffer.clear()
            for (index in offset until end) {
                appendQuad(vertexBuffer, quads[index], 0f, 0f, 0f, 0f, quads[index].argb)
            }
            uploadAndDraw(resources, end - offset)
            offset = end
        }
    }

    private fun drawGlyphs(
        resources: GlesResources,
        glyphs: List<TerminalGlyphPlacement>
    ) {
        if (glyphs.isEmpty()) return
        val batcher = GlesGlyphBatchAccumulator(
            maxQuadsPerBatch = MaxQuadsPerBatch,
            maxActiveBatches = resources.atlas.maxPages
        )
        val flushPending = {
            batcher.flush().forEach { batch -> drawGlyphBatch(resources, batch) }
        }
        pendingGlyphFlush = flushPending
        try {
            glyphs.forEach { glyph ->
                val region = resources.atlas.resolve(glyph.key, beforeReset = flushPending)
                    ?: return@forEach
                val fullBatch = batcher.add(GlesResolvedGlyph(glyph, region))
                if (fullBatch != null) drawGlyphBatch(resources, fullBatch)
            }
            flushPending()
        } finally {
            pendingGlyphFlush = null
        }
    }

    private fun drawGlyphBatch(resources: GlesResources, batch: GlesGlyphBatch) {
        if (batch.glyphs.isEmpty()) return
        val texture = resources.atlas.textureId(batch.key.pageIndex)
        if (texture == 0) {
            throw GlesResourceException("atlas texture: page ${batch.key.pageIndex} has id 0")
        }
        resources.program.bind(viewportWidth, viewportHeight, textured = true)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        vertexBuffer.clear()
        val pageSize = resources.atlas.pageSizePx.toFloat()
        batch.glyphs.forEach { glyph ->
            if (glyph.batchKey != batch.key) {
                throw GlesResourceException("atlas batch contains a stale generation reference")
            }
            val region = glyph.region
            appendQuad(
                buffer = vertexBuffer,
                quad = TerminalQuad(
                    left = glyph.placement.left + region.drawOffsetX,
                    top = glyph.placement.top + region.drawOffsetY,
                    right = glyph.placement.left + region.drawOffsetX + region.width,
                    bottom = glyph.placement.top + region.drawOffsetY + region.height,
                    argb = 0xFFFFFFFF.toInt()
                ),
                u0 = region.left / pageSize,
                v0 = region.top / pageSize,
                u1 = region.right / pageSize,
                v1 = region.bottom / pageSize,
                color = 0xFFFFFFFF.toInt()
            )
        }
        uploadAndDraw(resources, batch.glyphs.size)
    }

    private fun bindVertexAttributes() {
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, VertexStrideBytes, 0)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, VertexStrideBytes, 2 * Float.SIZE_BYTES)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, VertexStrideBytes, 4 * Float.SIZE_BYTES)
    }

    private fun uploadAndDraw(resources: GlesResources, quadCount: Int) {
        vertexBuffer.flip()
        resources.bufferRing.uploadAndDraw(
            vertexBuffer = vertexBuffer,
            vertexCount = quadCount * VerticesPerQuad,
            configureAttributes = ::bindVertexAttributes
        )
    }

    private fun appendQuad(
        buffer: FloatBuffer,
        quad: TerminalQuad,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        color: Int
    ) {
        appendVertex(buffer, quad.left, quad.top, u0, v0, color)
        appendVertex(buffer, quad.left, quad.bottom, u0, v1, color)
        appendVertex(buffer, quad.right, quad.bottom, u1, v1, color)
        appendVertex(buffer, quad.left, quad.top, u0, v0, color)
        appendVertex(buffer, quad.right, quad.bottom, u1, v1, color)
        appendVertex(buffer, quad.right, quad.top, u1, v0, color)
    }

    private fun appendVertex(
        buffer: FloatBuffer,
        x: Float,
        y: Float,
        u: Float,
        v: Float,
        argb: Int
    ) {
        val alpha = ((argb ushr 24) and 0xFF) / 255f
        buffer.put(x)
        buffer.put(y)
        buffer.put(u)
        buffer.put(v)
        buffer.put(((argb ushr 16) and 0xFF) / 255f * alpha)
        buffer.put(((argb ushr 8) and 0xFF) / 255f * alpha)
        buffer.put((argb and 0xFF) / 255f * alpha)
        buffer.put(alpha)
    }

    private fun backgroundColor(snapshot: GlesTerminalSnapshot): Int =
        if (snapshot.frame.reverseVideo) {
            snapshot.frame.palette.color(
                com.termux.terminal.compose.TerminalPalette.COLOR_INDEX_FOREGROUND
            )
        } else {
            snapshot.frame.palette.color(
                com.termux.terminal.compose.TerminalPalette.COLOR_INDEX_BACKGROUND
            )
        }

    @Suppress("TooGenericExceptionCaught")
    private fun presentFallback(snapshot: GlesTerminalSnapshot, stage: String, message: String) {
        presentedSnapshot = null
        lastAnimationTime = Float.NaN
        reportError(stage, message)
        try {
            // Replaying a plan is unsafe after an atlas/ring failure. A solid opaque background
            // is deterministic and complete, so the swap cannot expose mixed old/new rows.
            clear(backgroundColor(snapshot))
            checkGlError("fallback")
        } catch (error: RuntimeException) {
            reportError("fallback", error.message ?: "GLES fallback failed")
        }
    }

    private fun clear(argb: Int) {
        val alpha = ((argb ushr 24) and 0xFF) / 255f
        GLES30.glClearColor(
            ((argb ushr 16) and 0xFF) / 255f,
            ((argb ushr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
            alpha.coerceAtLeast(1f)
        )
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    private fun queryMaxTextureSize(): Int {
        val values = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, values, 0)
        return values[0]
    }

    private fun checkGlError(stage: String): Boolean {
        var foundError = false
        repeat(8) {
            val error = GLES30.glGetError()
            if (error == GLES30.GL_NO_ERROR) return foundError
            foundError = true
            lastError = "$stage: 0x${error.toString(16)}"
            reportError(stage, lastError ?: "unknown GL error")
        }
        return foundError
    }

    private fun reportError(stage: String, message: String) {
        lastError = message
        surface.reportDiagnostic(
            GlesTerminalDiagnostic.Error(
                state = diagnosticState(),
                stage = stage,
                message = message
            )
        )
    }

    private fun reportState(force: Boolean = false) {
        if (!force) return
        surface.reportDiagnostic(GlesTerminalDiagnostic.State(diagnosticState()))
    }

    private fun diagnosticState(): GlesDiagnosticState {
        val snapshot = lastSnapshot
        val atlas = resources?.atlas?.diagnostics() ?: GlesAtlasDiagnostics.EMPTY
        val frame = if (snapshot == null) {
            GlesFrameDiagnostics(
                drawCount = drawCount,
                skippedDrawCount = skippedDrawCount,
                terminalSequence = Long.MIN_VALUE,
                contentRevision = Long.MIN_VALUE,
                presentationRevision = Long.MIN_VALUE,
                surfaceGeneration = generation
            )
        } else {
            GlesFrameDiagnostics(
                drawCount = drawCount,
                skippedDrawCount = skippedDrawCount,
                terminalSequence = snapshot.frame.sequence,
                contentRevision = snapshot.contentRevision,
                presentationRevision = snapshot.presentationRevision,
                surfaceGeneration = generation
            )
        }
        return GlesDiagnosticState(
            vendor = vendor,
            renderer = renderer,
            version = version,
            shadingLanguageVersion = shadingLanguageVersion,
            generation = generation,
            atlas = atlas,
            frame = frame,
            error = lastError
        )
    }

    private class GlesResources(
        val program: GlesProgram,
        val bufferRing: GlesBufferRing,
        val atlas: GlesGlyphAtlas
    ) {
        fun release() {
            try {
                atlas.release()
            } finally {
                try {
                    bufferRing.release()
                } finally {
                    program.release()
                }
            }
        }
    }
}

internal open class GlesRendererException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

internal class GlesProgramException(message: String) : GlesRendererException(message)

internal class GlesResourceException(
    message: String,
    cause: Throwable? = null
) : GlesRendererException(message, cause)

private class GlesProgram private constructor(
    private val programId: Int,
    private val viewportUniform: Int,
    private val texturedUniform: Int,
    private val atlasUniform: Int
) {
    fun bind(width: Int, height: Int, textured: Boolean) {
        GLES30.glUseProgram(programId)
        GLES30.glUniform2f(viewportUniform, width.toFloat(), height.toFloat())
        GLES30.glUniform1i(texturedUniform, if (textured) 1 else 0)
        GLES30.glUniform1i(atlasUniform, 0)
    }

    fun release() {
        GLES30.glDeleteProgram(programId)
    }

    @Suppress("ThrowsCount")
    companion object {
        @Suppress("ThrowsCount")
        fun create(): GlesProgram {
            val vertexShader = compile("vertex", GLES30.GL_VERTEX_SHADER, GlesShaderSources.VERTEX)
            val fragmentShader = try {
                compile("fragment", GLES30.GL_FRAGMENT_SHADER, GlesShaderSources.FRAGMENT)
            } catch (error: GlesProgramException) {
                GLES30.glDeleteShader(vertexShader)
                throw error
            }
            val program = GLES30.glCreateProgram()
            if (program == 0) {
                GLES30.glDeleteShader(vertexShader)
                GLES30.glDeleteShader(fragmentShader)
                throw GlesProgramException("glCreateProgram returned 0")
            }
            try {
                GLES30.glAttachShader(program, vertexShader)
                GLES30.glAttachShader(program, fragmentShader)
                GLES30.glLinkProgram(program)
                val status = IntArray(1)
                GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
                if (status[0] == 0) {
                    throw GlesProgramException(
                        "GLES program link failed: ${GLES30.glGetProgramInfoLog(program)}"
                    )
                }
                val viewport = GLES30.glGetUniformLocation(program, "uViewport")
                val textured = GLES30.glGetUniformLocation(program, "uTextured")
                val atlas = GLES30.glGetUniformLocation(program, "uAtlas")
                if (viewport < 0 || textured < 0 || atlas < 0) {
                    throw GlesProgramException("GLES program uniforms are incomplete")
                }
                return GlesProgram(program, viewport, textured, atlas)
            } catch (error: GlesProgramException) {
                GLES30.glDeleteProgram(program)
                throw error
            } finally {
                GLES30.glDeleteShader(vertexShader)
                GLES30.glDeleteShader(fragmentShader)
            }
        }

        private fun compile(sourceTag: String, type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            if (shader == 0) throw GlesProgramException("glCreateShader returned 0")
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                throw GlesProgramException("$sourceTag shader compile failed: $log")
            }
            return shader
        }
    }
}
