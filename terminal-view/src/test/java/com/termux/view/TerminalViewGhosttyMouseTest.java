package com.termux.view;

import android.view.KeyEvent;
import android.view.MotionEvent;

import com.termux.terminal.GhosttyMouseEvent;
import com.termux.terminal.TerminalConstants;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TerminalViewGhosttyMouseTest {

    @Test
    public void capturesHardwareMouseOnlyWhenGhosttyTrackingIsActive() {
        assertTrue(TerminalView.shouldCaptureGhosttyMouse(true, true));
        assertFalse(TerminalView.shouldCaptureGhosttyMouse(false, true));
        assertFalse(TerminalView.shouldCaptureGhosttyMouse(true, false));
    }

    @Test
    public void mapsAndroidMetaStateToGhosttyModifiers() {
        int metaState = KeyEvent.META_SHIFT_ON | KeyEvent.META_ALT_ON | KeyEvent.META_CTRL_ON;
        int modifiers = TerminalView.ghosttyModifiersFromMetaState(metaState);
        assertEquals(
            GhosttyMouseEvent.MODIFIER_SHIFT | GhosttyMouseEvent.MODIFIER_ALT | GhosttyMouseEvent.MODIFIER_CTRL,
            modifiers
        );
    }

    @Test
    public void usesRequiredPressedButtonPrecedence() {
        assertEquals(
            GhosttyMouseEvent.BUTTON_LEFT,
            TerminalView.ghosttyPressedButtonFromButtonState(MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_PRIMARY)
        );
        assertEquals(
            GhosttyMouseEvent.BUTTON_RIGHT,
            TerminalView.ghosttyPressedButtonFromButtonState(MotionEvent.BUTTON_TERTIARY | MotionEvent.BUTTON_SECONDARY)
        );
        assertEquals(
            GhosttyMouseEvent.BUTTON_MIDDLE,
            TerminalView.ghosttyPressedButtonFromButtonState(MotionEvent.BUTTON_BACK | MotionEvent.BUTTON_TERTIARY)
        );
        assertEquals(
            GhosttyMouseEvent.BUTTON_BACK,
            TerminalView.ghosttyPressedButtonFromButtonState(MotionEvent.BUTTON_FORWARD | MotionEvent.BUTTON_BACK)
        );
        assertEquals(
            GhosttyMouseEvent.BUTTON_FORWARD,
            TerminalView.ghosttyPressedButtonFromButtonState(MotionEvent.BUTTON_FORWARD)
        );
        assertEquals(
            GhosttyMouseEvent.BUTTON_NONE,
            TerminalView.ghosttyPressedButtonFromButtonState(0)
        );
    }

    @Test
    public void mapsActionButtonsAndWheelAxes() {
        assertEquals(GhosttyMouseEvent.BUTTON_RIGHT, TerminalView.ghosttyButtonFromActionButton(MotionEvent.BUTTON_SECONDARY));
        assertEquals(GhosttyMouseEvent.BUTTON_BACK, TerminalView.ghosttyButtonFromActionButton(MotionEvent.BUTTON_BACK));
        assertEquals(GhosttyMouseEvent.BUTTON_FORWARD, TerminalView.ghosttyButtonFromActionButton(MotionEvent.BUTTON_FORWARD));

        assertEquals(GhosttyMouseEvent.BUTTON_WHEEL_UP, TerminalView.ghosttyVerticalWheelButton(1.0f));
        assertEquals(GhosttyMouseEvent.BUTTON_WHEEL_DOWN, TerminalView.ghosttyVerticalWheelButton(-1.0f));
        assertEquals(GhosttyMouseEvent.BUTTON_WHEEL_RIGHT, TerminalView.ghosttyHorizontalWheelButton(1.0f));
        assertEquals(GhosttyMouseEvent.BUTTON_WHEEL_LEFT, TerminalView.ghosttyHorizontalWheelButton(-1.0f));
        assertEquals(1, TerminalView.ghosttyScrollEventCount(0.1f));
        assertEquals(2, TerminalView.ghosttyScrollEventCount(-2.0f));
    }

    @Test
    public void preservesLegacyTouchButtonMappingsForGhosttyRouting() {
        assertEquals(
            GhosttyMouseEvent.BUTTON_LEFT,
            TerminalView.ghosttyButtonFromTerminalMouseCode(TerminalConstants.MOUSE_LEFT_BUTTON)
        );
        assertEquals(
            GhosttyMouseEvent.BUTTON_LEFT,
            TerminalView.ghosttyButtonFromTerminalMouseCode(TerminalConstants.MOUSE_LEFT_BUTTON_MOVED)
        );
        assertEquals(
            GhosttyMouseEvent.BUTTON_WHEEL_UP,
            TerminalView.ghosttyButtonFromTerminalMouseCode(TerminalConstants.MOUSE_WHEELUP_BUTTON)
        );
        assertEquals(
            GhosttyMouseEvent.BUTTON_WHEEL_DOWN,
            TerminalView.ghosttyButtonFromTerminalMouseCode(TerminalConstants.MOUSE_WHEELDOWN_BUTTON)
        );
    }
}
