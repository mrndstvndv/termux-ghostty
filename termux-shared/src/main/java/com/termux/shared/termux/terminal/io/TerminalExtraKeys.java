package com.termux.shared.termux.terminal.io;

import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;
import com.termux.shared.termux.extrakeys.ExtraKeyButton;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.extrakeys.SpecialButton;
import com.termux.terminal.compose.TerminalInputSink;

import static com.termux.shared.termux.extrakeys.ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS;


public class TerminalExtraKeys implements ExtraKeysView.IExtraKeysView {

    private final TerminalInputSink mTerminalInputSink;

    public TerminalExtraKeys(@NonNull TerminalInputSink terminalInputSink) {
        mTerminalInputSink = terminalInputSink;
    }

    @Override
    public void onExtraKeyButtonClick(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        String keyStr = buttonInfo.getKey();
        boolean isMacro = buttonInfo.isMacro() ||
            (keyStr.contains(" ") && !PRIMARY_KEY_CODES_FOR_STRINGS.containsKey(keyStr));

        if (isMacro) {
            String[] keys = keyStr.split(" ");
            boolean ctrlDown = false;
            boolean altDown = false;
            boolean shiftDown = false;
            boolean fnDown = false;
            for (String key : keys) {
                String upperKey = key.toUpperCase();
                if (SpecialButton.CTRL.getKey().equals(upperKey) || "CONTROL".equals(upperKey)) {
                    ctrlDown = true;
                } else if (SpecialButton.ALT.getKey().equals(upperKey)) {
                    altDown = true;
                } else if (SpecialButton.SHIFT.getKey().equals(upperKey) || "SHFT".equals(upperKey)) {
                    shiftDown = true;
                } else if (SpecialButton.FN.getKey().equals(upperKey) || "FUNCTION".equals(upperKey)) {
                    fnDown = true;
                } else {
                    onTerminalExtraKeyButtonClick(view, key, ctrlDown, altDown, shiftDown, fnDown);
                    ctrlDown = false;
                    altDown = false;
                    shiftDown = false;
                    fnDown = false;
                }
            }
        } else {
            ExtraKeysView extraKeysView = getExtraKeysView(view, button);
            boolean ctrlDown = false;
            boolean altDown = false;
            boolean shiftDown = false;
            boolean fnDown = false;

            if (extraKeysView != null) {
                Boolean ctrlState = extraKeysView.readSpecialButton(SpecialButton.CTRL, true);
                if (ctrlState != null && ctrlState) ctrlDown = true;

                Boolean altState = extraKeysView.readSpecialButton(SpecialButton.ALT, true);
                if (altState != null && altState) altDown = true;

                Boolean shiftState = extraKeysView.readSpecialButton(SpecialButton.SHIFT, true);
                if (shiftState != null && shiftState) shiftDown = true;

                Boolean fnState = extraKeysView.readSpecialButton(SpecialButton.FN, true);
                if (fnState != null && fnState) fnDown = true;
            }

            onTerminalExtraKeyButtonClick(view, buttonInfo.getKey(), ctrlDown, altDown, shiftDown, fnDown);
        }
    }

    private ExtraKeysView getExtraKeysView(View view, MaterialButton button) {
        View v = button != null ? button : view;
        while (v != null) {
            if (v instanceof ExtraKeysView) {
                return (ExtraKeysView) v;
            }
            if (v.getParent() instanceof View) {
                v = (View) v.getParent();
            } else {
                break;
            }
        }
        return null;
    }

    protected void onTerminalExtraKeyButtonClick(View view, String key, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown) {
        if (PRIMARY_KEY_CODES_FOR_STRINGS.containsKey(key)) {
            Integer keyCode = PRIMARY_KEY_CODES_FOR_STRINGS.get(key);
            if (keyCode == null) return;
            int metaState = 0;
            if (ctrlDown) metaState |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
            if (altDown) metaState |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
            if (shiftDown) metaState |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
            if (fnDown) metaState |= KeyEvent.META_FUNCTION_ON;

            mTerminalInputSink.submitKey(keyCode, metaState);
        } else {
            int codePoint = key.length() == 1 ? key.codePointAt(0) : -1;
            if (codePoint != -1) {
                int finalCodePoint = codePoint;
                boolean isUpperCase = finalCodePoint >= 'A' && finalCodePoint <= 'Z';

                if (shiftDown) {
                    if (finalCodePoint >= 'a' && finalCodePoint <= 'z') {
                        finalCodePoint -= 32;
                        isUpperCase = true;
                    }
                }

                if (ctrlDown && isUpperCase && shiftDown) {
                    // Kitty Keyboard Protocol for Ctrl+Shift+Letter
                    int modifier = altDown ? 14 : 6;
                    String sequence = "\u001b[" + finalCodePoint + ";" + modifier + "u";
                    mTerminalInputSink.submitText(sequence);
                    return;
                }

                mTerminalInputSink.submitCodePoint(finalCodePoint, ctrlDown, altDown);
            } else {
                if (key.length() > 0) mTerminalInputSink.submitText(key);
            }
        }
    }

    @Override
    public boolean performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        return false;
    }

}
