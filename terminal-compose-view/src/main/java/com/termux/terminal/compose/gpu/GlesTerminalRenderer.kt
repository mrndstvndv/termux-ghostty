package com.termux.terminal.compose.gpu

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Trace
import com.termux.terminal.compose.internal.CursorEffectRenderPlan
import com.termux.terminal.compose.internal.planCursorEffect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val MaxInstancesPerBatch = 4096
private const val StyledInstanceStrideFloats = 15
private const val GlyphInstanceStrideFloats = 16
private const val StyledInstanceStrideBytes = StyledInstanceStrideFloats * Float.SIZE_BYTES
private const val GlyphInstanceStrideBytes = GlyphInstanceStrideFloats * Float.SIZE_BYTES
private const val InstanceBufferBytes = MaxInstancesPerBatch * GlyphInstanceStrideBytes
private const val DiagnosticFrameInterval = 16L
private val EmptyTerminalQuads = emptyList<TerminalQuad>()
private val EmptyTerminalGlyphs = emptyList<TerminalGlyphPlacement>()

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
@Suppress("TooManyFunctions", "LargeClass")
internal class GlesTerminalRenderer(
    private val surface: GlesTerminalSurface,
    private val requestRender: () -> Unit
) : GLSurfaceView.Renderer {
    private val instanceBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(InstanceBufferBytes)
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
    private val renderPlanner = TerminalRenderPlanner()
    private val cursorEffectPlan = CursorEffectRenderPlan()
    private var vendor = "unknown"
    private var renderer = "unknown"
    private var version = "unknown"
    private var shadingLanguageVersion = "unknown"
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

        val plan = try {
            traceSection("EctoGles.plan") {
                if (atlasReset) currentResources.atlas.reset()
                renderPlanner.plan(snapshot)
            }
        } catch (error: GlesRendererException) {
            presentFallback(snapshot, "plan", error.message ?: "GLES plan failed")
            return
        } catch (error: RuntimeException) {
            presentFallback(snapshot, "plan", error.message ?: "GLES plan failed")
            return
        }

        try {
            clear(backgroundColor(snapshot))
            currentResources.palette.bind(snapshot.frame.palette)
            traceSection("EctoGles.backgrounds") {
                drawStyledRows(
                    resources = currentResources,
                    rows = plan.rows,
                    rowStride = snapshot.frame.columns + 1,
                    rowHeight = snapshot.metrics.cellHeightPx,
                    styleBackground = true,
                    reverseVideo = snapshot.frame.reverseVideo
                )
            }
            // Retained glyph rows are rebuilt when the atlas generation changes.
            traceSection("EctoGles.glyphs") {
                drawGlyphs(
                    resources = currentResources,
                    rows = plan.rows,
                    rowStride = snapshot.frame.columns,
                    rowHeight = snapshot.metrics.cellHeightPx,
                    reverseVideo = snapshot.frame.reverseVideo
                )
            }
            traceSection("EctoGles.decorations") {
                drawStyledRows(
                    resources = currentResources,
                    rows = plan.rows,
                    rowStride = snapshot.frame.columns,
                    rowHeight = snapshot.metrics.cellHeightPx,
                    styleBackground = false,
                    reverseVideo = snapshot.frame.reverseVideo
                )
            }
            traceSection("EctoGles.cursor-effect") {
                if (planCursorEffect(
                        effectSnapshot = snapshot.cursorEffect,
                        frame = snapshot.frame,
                        metrics = snapshot.metrics,
                        timeSeconds = animationTime,
                        output = cursorEffectPlan
                    )
                ) {
                    currentResources.cursorEffects.draw(
                        plan = cursorEffectPlan,
                        viewportWidth = viewportWidth,
                        viewportHeight = viewportHeight
                    )
                }
            }
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
        try {
            currentResources?.release()
        } catch (error: RuntimeException) {
            reportError("resource-release", error.message ?: "GLES resource release failed")
        }
    }

    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    private fun createResources(limits: GlyphAtlasLimits): GlesResources {
        val program = GlesProgram.create()
        val cursorEffects = try {
            GlesCursorEffectRenderer.create()
        } catch (error: RuntimeException) {
            program.release()
            throw error
        }
        return try {
            GlesResources(
                program = program,
                cursorEffects = cursorEffects,
                dirtyBuffers = GlesDirtyInstanceStore(),
                glyphRows = GlesGlyphRowBuffer(),
                atlas = GlesGlyphAtlas(limits),
                palette = GlesPaletteTexture()
            )
        } catch (error: GlesRendererException) {
            cursorEffects.release()
            program.release()
            throw error
        } catch (error: RuntimeException) {
            cursorEffects.release()
            program.release()
            throw GlesResourceException("GLES resource setup failed", error)
        }
    }

    private fun drawStyledRows(
        resources: GlesResources,
        rows: List<TerminalRenderRowPlan?>,
        rowStride: Int,
        rowHeight: Float,
        styleBackground: Boolean,
        reverseVideo: Boolean
    ) {
        val buffer = if (styleBackground) {
            resources.dirtyBuffers.backgrounds
        } else {
            resources.dirtyBuffers.decorations
        }
        val itemsForRow: (TerminalRenderRowPlan?) -> List<TerminalQuad> = { row ->
            if (styleBackground) row?.backgroundQuads ?: EmptyTerminalQuads
            else row?.decorations ?: EmptyTerminalQuads
        }
        buffer.update(
            rowCount = rows.size,
            rowStride = rowStride,
            strideBytes = StyledInstanceStrideBytes,
            strideFloats = StyledInstanceStrideFloats,
            scratch = instanceBuffer,
            sourceFor = { index -> itemsForRow(rows[index]) },
            countFor = { index -> itemsForRow(rows[index]).size },
            fillRow = { index, rowBuffer ->
                itemsForRow(rows[index]).forEach { quad ->
                    val directColor = quad.style == null
                    appendStyledInstance(
                        buffer = rowBuffer,
                        quad = quad,
                        color = quad.argb,
                        style = quad.style ?: 0L,
                        styleFlags = quad.styleFlags or
                            (if (directColor) GlesStyleFlagDirectColor else 0)
                    )
                }
            }
        )
        if (!buffer.hasInstances()) return
        resources.program.bind(
            viewportWidth,
            viewportHeight,
            textured = false,
            resolveStyle = true,
            styleBackground = styleBackground,
            reverseVideo = reverseVideo,
            fixedRows = true,
            rowStride = rowStride,
            rowHeight = rowHeight
        )
        buffer.bindRowCounts()
        buffer.draw(::bindStyledInstanceAttributes)
    }

    private fun drawGlyphs(
        resources: GlesResources,
        rows: List<TerminalRenderRowPlan?>,
        rowStride: Int,
        rowHeight: Float,
        reverseVideo: Boolean
    ) {
        val itemsForRow: (TerminalRenderRowPlan?) -> List<TerminalGlyphPlacement> = { row ->
            row?.glyphs ?: EmptyTerminalGlyphs
        }
        val pageSize = resources.atlas.pageSizePx.toFloat()
        val textureWidth = resources.atlas.textureWidthPx.toFloat()
        resources.glyphRows.update(
            rowCount = rows.size,
            rowStride = rowStride,
            strideFloats = GlyphInstanceStrideFloats,
            atlasGeneration = { resources.atlas.generation },
            sourceFor = { index -> itemsForRow(rows[index]) },
            maximumCountFor = { index -> itemsForRow(rows[index]).size },
            fillRow = { index, buffer ->
                var count = 0
                itemsForRow(rows[index]).forEach { glyph ->
                    val region = resources.atlas.resolve(glyph.key) ?: return@forEach
                    val pageLeft = region.pageIndex * pageSize
                    appendGlyphInstance(
                        buffer = buffer,
                        quad = TerminalQuad(
                            left = glyph.left + region.drawOffsetX,
                            top = glyph.top + region.drawOffsetY,
                            right = glyph.left + region.drawOffsetX + region.width,
                            bottom = glyph.top + region.drawOffsetY + region.height,
                            argb = 0
                        ),
                        u0 = (pageLeft + region.left) / textureWidth,
                        v0 = region.top / pageSize,
                        u1 = (pageLeft + region.right) / textureWidth,
                        v1 = region.bottom / pageSize,
                        style = glyph.style,
                        styleFlags = glyph.styleFlags,
                        rasterMode = region.rasterMode
                    )
                    count++
                }
                count
            }
        )
        if (!resources.glyphRows.hasInstances()) return
        val texture = resources.atlas.textureId()
        if (texture == 0) throw GlesResourceException("atlas texture has id 0")
        resources.program.bind(
            viewportWidth,
            viewportHeight,
            textured = true,
            resolveStyle = true,
            reverseVideo = reverseVideo,
            fixedRows = true,
            rowStride = rowStride,
            rowHeight = rowHeight
        )
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        resources.glyphRows.bindRowCounts()
        resources.glyphRows.draw(::bindGlyphInstanceAttributes)
    }

    private fun bindStyledInstanceAttributes() {
        bindStyledInstanceAttributes(StyledInstanceStrideBytes)
        GLES30.glDisableVertexAttribArray(6)
    }

    private fun bindGlyphInstanceAttributes() {
        bindStyledInstanceAttributes(GlyphInstanceStrideBytes)
        GLES30.glEnableVertexAttribArray(6)
        GLES30.glVertexAttribIPointer(
            6,
            1,
            GLES30.GL_UNSIGNED_INT,
            GlyphInstanceStrideBytes,
            15 * Float.SIZE_BYTES
        )
        GLES30.glVertexAttribDivisor(6, 1)
    }

    private fun bindStyledInstanceAttributes(strideBytes: Int) {
        bindFloatAttributes(strideBytes)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glEnableVertexAttribArray(5)
        GLES30.glVertexAttribIPointer(
            3,
            1,
            GLES30.GL_UNSIGNED_INT,
            strideBytes,
            12 * Float.SIZE_BYTES
        )
        GLES30.glVertexAttribIPointer(
            4,
            1,
            GLES30.GL_UNSIGNED_INT,
            strideBytes,
            13 * Float.SIZE_BYTES
        )
        GLES30.glVertexAttribIPointer(
            5,
            1,
            GLES30.GL_UNSIGNED_INT,
            strideBytes,
            14 * Float.SIZE_BYTES
        )
        GLES30.glVertexAttribDivisor(3, 1)
        GLES30.glVertexAttribDivisor(4, 1)
        GLES30.glVertexAttribDivisor(5, 1)
    }

    private fun bindFloatAttributes(strideBytes: Int) {
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, strideBytes, 0)
        GLES30.glVertexAttribPointer(
            1,
            4,
            GLES30.GL_FLOAT,
            false,
            strideBytes,
            4 * Float.SIZE_BYTES
        )
        GLES30.glVertexAttribPointer(
            2,
            4,
            GLES30.GL_FLOAT,
            false,
            strideBytes,
            8 * Float.SIZE_BYTES
        )
        GLES30.glVertexAttribDivisor(0, 1)
        GLES30.glVertexAttribDivisor(1, 1)
        GLES30.glVertexAttribDivisor(2, 1)
    }

    private fun appendInstance(
        buffer: FloatBuffer,
        quad: TerminalQuad,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        color: Int
    ) {
        buffer.put(quad.left)
        buffer.put(quad.top)
        buffer.put(quad.right)
        buffer.put(quad.bottom)
        buffer.put(u0)
        buffer.put(v0)
        buffer.put(u1)
        buffer.put(v1)
        val alpha = ((color ushr 24) and 0xFF) / 255f
        buffer.put(((color ushr 16) and 0xFF) / 255f * alpha)
        buffer.put(((color ushr 8) and 0xFF) / 255f * alpha)
        buffer.put((color and 0xFF) / 255f * alpha)
        buffer.put(alpha)
    }

    @Suppress("LongParameterList")
    private fun appendStyledInstance(
        buffer: FloatBuffer,
        quad: TerminalQuad,
        u0: Float = 0f,
        v0: Float = 0f,
        u1: Float = 0f,
        v1: Float = 0f,
        color: Int = 0,
        style: Long,
        styleFlags: Int
    ) {
        appendInstance(
            buffer = buffer,
            quad = quad,
            u0 = u0,
            v0 = v0,
            u1 = u1,
            v1 = v1,
            color = color
        )
        buffer.put(Float.fromBits(style.toInt()))
        buffer.put(Float.fromBits((style ushr 32).toInt()))
        buffer.put(Float.fromBits(styleFlags))
    }

    @Suppress("LongParameterList")
    private fun appendGlyphInstance(
        buffer: FloatBuffer,
        quad: TerminalQuad,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        style: Long,
        styleFlags: Int,
        rasterMode: Int
    ) {
        appendStyledInstance(
            buffer = buffer,
            quad = quad,
            u0 = u0,
            v0 = v0,
            u1 = u1,
            v1 = v1,
            style = style,
            styleFlags = styleFlags
        )
        buffer.put(Float.fromBits(rasterMode))
    }

    private inline fun <Result> traceSection(name: String, block: () -> Result): Result {
        Trace.beginSection(name)
        return try {
            block()
        } finally {
            Trace.endSection()
        }
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

    @Suppress("NestedBlockDepth")
    private class GlesResources(
        val program: GlesProgram,
        val cursorEffects: GlesCursorEffectRenderer,
        val dirtyBuffers: GlesDirtyInstanceStore,
        val glyphRows: GlesGlyphRowBuffer,
        val atlas: GlesGlyphAtlas,
        val palette: GlesPaletteTexture
    ) {
        fun release() {
            try {
                atlas.release()
            } finally {
                try {
                    palette.release()
                } finally {
                    try {
                        dirtyBuffers.release()
                    } finally {
                        try {
                            glyphRows.release()
                        } finally {
                            try {
                                cursorEffects.release()
                            } finally {
                                program.release()
                            }
                        }
                    }
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

@Suppress("LongParameterList")
private class GlesProgram private constructor(
    private val programId: Int,
    private val viewportUniform: Int,
    private val texturedUniform: Int,
    private val atlasUniform: Int,
    private val paletteUniform: Int,
    private val rowCountsUniform: Int,
    private val resolveStyleUniform: Int,
    private val styleBackgroundUniform: Int,
    private val reverseVideoUniform: Int,
    private val fixedRowsUniform: Int,
    private val rowStrideUniform: Int,
    private val rowHeightUniform: Int
) {
    fun bind(
        width: Int,
        height: Int,
        textured: Boolean,
        resolveStyle: Boolean = false,
        styleBackground: Boolean = false,
        reverseVideo: Boolean = false,
        fixedRows: Boolean = false,
        rowStride: Int = 1,
        rowHeight: Float = 0f
    ) {
        GLES30.glUseProgram(programId)
        GLES30.glUniform2f(viewportUniform, width.toFloat(), height.toFloat())
        GLES30.glUniform1i(texturedUniform, if (textured) 1 else 0)
        GLES30.glUniform1i(atlasUniform, 0)
        GLES30.glUniform1i(paletteUniform, 1)
        GLES30.glUniform1i(rowCountsUniform, 2)
        GLES30.glUniform1i(resolveStyleUniform, if (resolveStyle) 1 else 0)
        GLES30.glUniform1i(styleBackgroundUniform, if (styleBackground) 1 else 0)
        GLES30.glUniform1i(reverseVideoUniform, if (reverseVideo) 1 else 0)
        GLES30.glUniform1i(fixedRowsUniform, if (fixedRows) 1 else 0)
        GLES30.glUniform1i(rowStrideUniform, rowStride.coerceAtLeast(1))
        GLES30.glUniform1f(rowHeightUniform, rowHeight)
    }

    fun release() {
        GLES30.glDeleteProgram(programId)
    }

    @Suppress("ThrowsCount")
    companion object {
        @Suppress("ThrowsCount", "LongMethod")
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
                val palette = GLES30.glGetUniformLocation(program, "uPalette")
                val rowCounts = GLES30.glGetUniformLocation(program, "uRowCounts")
                val resolveStyle = GLES30.glGetUniformLocation(program, "uResolveStyle")
                val styleBackground = GLES30.glGetUniformLocation(program, "uStyleBackground")
                val reverseVideo = GLES30.glGetUniformLocation(program, "uReverseVideo")
                val fixedRows = GLES30.glGetUniformLocation(program, "uFixedRows")
                val rowStride = GLES30.glGetUniformLocation(program, "uRowStride")
                val rowHeight = GLES30.glGetUniformLocation(program, "uRowHeight")
                requireUniform(viewport, "uViewport")
                requireUniform(textured, "uTextured")
                requireUniform(atlas, "uAtlas")
                requireUniform(palette, "uPalette")
                requireUniform(rowCounts, "uRowCounts")
                requireUniform(resolveStyle, "uResolveStyle")
                requireUniform(styleBackground, "uStyleBackground")
                requireUniform(reverseVideo, "uReverseVideo")
                requireUniform(fixedRows, "uFixedRows")
                requireUniform(rowStride, "uRowStride")
                requireUniform(rowHeight, "uRowHeight")
                return GlesProgram(
                    program,
                    viewport,
                    textured,
                    atlas,
                    palette,
                    rowCounts,
                    resolveStyle,
                    styleBackground,
                    reverseVideo,
                    fixedRows,
                    rowStride,
                    rowHeight
                )
            } catch (error: GlesProgramException) {
                GLES30.glDeleteProgram(program)
                throw error
            } finally {
                GLES30.glDeleteShader(vertexShader)
                GLES30.glDeleteShader(fragmentShader)
            }
        }

        private fun requireUniform(location: Int, name: String) {
            if (location < 0) throw GlesProgramException("GLES uniform is incomplete: $name")
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
