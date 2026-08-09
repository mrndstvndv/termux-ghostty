package com.termux.terminal;

/** Raw vertical scroll input routed by the Ghostty worker against live terminal state. */
public final class GhosttyScrollEvent {

    public final int rowsDown;
    public final float surfaceX;
    public final float surfaceY;
    public final int screenWidthPx;
    public final int screenHeightPx;
    public final int cellWidthPx;
    public final int cellHeightPx;
    public final int paddingTopPx;

    public GhosttyScrollEvent(
        int rowsDown,
        float surfaceX,
        float surfaceY,
        int screenWidthPx,
        int screenHeightPx,
        int cellWidthPx,
        int cellHeightPx,
        int paddingTopPx
    ) {
        this.rowsDown = rowsDown;
        this.surfaceX = surfaceX;
        this.surfaceY = surfaceY;
        this.screenWidthPx = screenWidthPx;
        this.screenHeightPx = screenHeightPx;
        this.cellWidthPx = cellWidthPx;
        this.cellHeightPx = cellHeightPx;
        this.paddingTopPx = paddingTopPx;
    }

    GhosttyMouseEvent toMouseEvent() {
        int button = rowsDown < 0
            ? GhosttyMouseEvent.BUTTON_WHEEL_UP
            : GhosttyMouseEvent.BUTTON_WHEEL_DOWN;
        return new GhosttyMouseEvent(
            GhosttyMouseEvent.PRESS,
            button,
            0,
            surfaceX,
            surfaceY,
            screenWidthPx,
            screenHeightPx,
            cellWidthPx,
            cellHeightPx,
            paddingTopPx,
            0,
            0,
            0
        );
    }
}
