package com.termux.terminal.compose.gpu

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.View

/** Android host that keeps input in Compose and gives the renderer one GL thread. */
internal class GlesTerminalSurfaceView(
    context: Context,
    private val surface: GlesTerminalSurface
) : GLSurfaceView(context) {
    private val terminalRenderer = GlesTerminalRenderer(surface, ::requestRender)
    private var resumed = false
    private var disposed = false
    private var attachedToWindow = false

    init {
        setEGLContextClientVersion(3)
        // The surface is opaque, so alpha=0 avoids an unnecessary EGL alpha channel.
        setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        holder.setFormat(PixelFormat.OPAQUE)
        setPreserveEGLContextOnPause(true)
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setRenderer(terminalRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Compose owns all terminal input; this view is a pixels-only child. */
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onGenericMotionEvent(event: MotionEvent): Boolean = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (disposed || surface.isReleased()) return
        attachedToWindow = true
        surface.attachView(
            request = ::requestRender,
            releaseResources = ::queueGlRelease,
            lifecycleActive = ::setLifecycleActive
        )
    }

    override fun onDetachedFromWindow() {
        attachedToWindow = false
        surface.detachView()
        setLifecycleActive(false)
        super.onDetachedFromWindow()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        attachedToWindow = false
        surface.detachView()
        // Queue while the GLSurfaceView still owns this renderer, before pause.
        queueGlRelease()
        surface.release()
        if (resumed) {
            onPause()
            resumed = false
        }
    }

    @Suppress("ReturnCount")
    internal fun setLifecycleActive(active: Boolean) {
        if (disposed || !attachedToWindow) {
            if (!active && resumed) {
                resumed = false
                onPause()
            }
            return
        }
        if (active) {
            if (resumed) return
            resumed = true
            onResume()
            return
        }
        if (!resumed) return
        resumed = false
        onPause()
    }

    private fun queueGlRelease() {
        queueEvent(terminalRenderer::releaseOnGlThread)
    }
}
