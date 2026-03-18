package com.termux.terminal;

/** Raw mouse input transport for the Ghostty backend. */
public final class GhosttyMouseEvent {

    public static final int PRESS = 0;
    public static final int RELEASE = 1;
    public static final int MOTION = 2;

    public static final int BUTTON_NONE = 0;
    public static final int BUTTON_LEFT = 1;
    public static final int BUTTON_MIDDLE = 2;
    public static final int BUTTON_RIGHT = 3;
    public static final int BUTTON_WHEEL_UP = 4;
    public static final int BUTTON_WHEEL_DOWN = 5;
    public static final int BUTTON_WHEEL_LEFT = 6;
    public static final int BUTTON_WHEEL_RIGHT = 7;
    public static final int BUTTON_BACK = 8;
    public static final int BUTTON_FORWARD = 9;

    public static final int MODIFIER_SHIFT = 1 << 0;
    public static final int MODIFIER_ALT = 1 << 1;
    public static final int MODIFIER_CTRL = 1 << 2;

    public final int action;
    public final int button;
    public final int modifiers;
    public final float surfaceX;
    public final float surfaceY;
    public final int screenWidthPx;
    public final int screenHeightPx;
    public final int cellWidthPx;
    public final int cellHeightPx;
    public final int paddingTopPx;
    public final int paddingRightPx;
    public final int paddingBottomPx;
    public final int paddingLeftPx;

    public GhosttyMouseEvent(
        int action,
        int button,
        int modifiers,
        float surfaceX,
        float surfaceY,
        int screenWidthPx,
        int screenHeightPx,
        int cellWidthPx,
        int cellHeightPx,
        int paddingTopPx,
        int paddingRightPx,
        int paddingBottomPx,
        int paddingLeftPx
    ) {
        this.action = action;
        this.button = button;
        this.modifiers = modifiers;
        this.surfaceX = surfaceX;
        this.surfaceY = surfaceY;
        this.screenWidthPx = screenWidthPx;
        this.screenHeightPx = screenHeightPx;
        this.cellWidthPx = cellWidthPx;
        this.cellHeightPx = cellHeightPx;
        this.paddingTopPx = paddingTopPx;
        this.paddingRightPx = paddingRightPx;
        this.paddingBottomPx = paddingBottomPx;
        this.paddingLeftPx = paddingLeftPx;
    }
}
