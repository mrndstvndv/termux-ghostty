package com.termux.terminal.compose.gpu

/** Top-left pixel coordinates projected into the OpenGL clip-space convention. */
internal class GlesProjection(
    val widthPx: Int,
    val heightPx: Int
) {
    init {
        require(widthPx > 0) { "widthPx must be positive" }
        require(heightPx > 0) { "heightPx must be positive" }
    }

    fun xToNdc(x: Float): Float = (x / widthPx) * 2f - 1f

    fun yToNdc(y: Float): Float = 1f - (y / heightPx) * 2f

    fun rectToNdc(left: Float, top: Float, right: Float, bottom: Float): FloatArray = floatArrayOf(
        xToNdc(left),
        yToNdc(top),
        xToNdc(right),
        yToNdc(bottom)
    )
}
