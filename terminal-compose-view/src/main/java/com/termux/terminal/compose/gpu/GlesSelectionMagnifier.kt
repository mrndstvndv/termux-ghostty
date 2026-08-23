package com.termux.terminal.compose.gpu

import android.os.Build
import android.view.View
import android.widget.Magnifier
import kotlin.math.roundToInt

private const val SelectionMagnifierZoom = 2f
private const val SelectionMagnifierWidthDp = 128f
private const val SelectionMagnifierHeightDp = 56f
private const val SelectionMagnifierCornerRadiusDp = 28f

/** Targets the actual SurfaceView so the platform magnifier copies GLES pixels. */
internal class GlesSelectionMagnifier(
    private val view: View
) {
    private var magnifier: Magnifier? = null

    fun show(sourceCenterX: Float, sourceCenterY: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (!view.isAttachedToWindow) return
        val magnifier = magnifier ?: createMagnifier().also { magnifier = it }
        magnifier.show(sourceCenterX, sourceCenterY)
    }

    fun updateContent() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        magnifier?.update()
    }

    fun dismiss() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        magnifier?.dismiss()
    }

    @Suppress("DEPRECATION", "NewApi")
    private fun createMagnifier(): Magnifier {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Magnifier(view)

        val density = view.resources.displayMetrics.density
        return Magnifier.Builder(view)
            .setSize(
                (SelectionMagnifierWidthDp * density).roundToInt(),
                (SelectionMagnifierHeightDp * density).roundToInt()
            )
            .setCornerRadius(SelectionMagnifierCornerRadiusDp * density)
            .setInitialZoom(SelectionMagnifierZoom)
            .setClippingEnabled(true)
            .build()
    }
}
