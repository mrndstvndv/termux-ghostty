package com.termux.shared.termux.terminal.io;

import android.os.Build;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;
import com.termux.shared.termux.extrakeys.ExtraKeyButton;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.extrakeys.SpecialButton;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import static com.termux.shared.termux.extrakeys.ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS;


public class TerminalExtraKeys implements ExtraKeysView.IExtraKeysView {

    private final TerminalView mTerminalView;

    public TerminalExtraKeys(@NonNull TerminalView terminalView) {
        mTerminalView = terminalView;
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

            KeyEvent keyEvent = new KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState);
            mTerminalView.onKeyDown(keyCode, keyEvent);
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

                TerminalSession session = mTerminalView.getCurrentSession();

                if (ctrlDown && isUpperCase && shiftDown) {
                    // Kitty Keyboard Protocol for Ctrl+Shift+Letter
                    int modifier = altDown ? 14 : 6;
                    String sequence = "\u001b[" + finalCodePoint + ";" + modifier + "u";
                    if (session != null) {
                        session.write(sequence);
                    }
                    return;
                }

                if (ctrlDown) {
                    if (finalCodePoint >= 'a' && finalCodePoint <= 'z') {
                        finalCodePoint = finalCodePoint - 'a' + 1;
                    } else if (finalCodePoint >= 'A' && finalCodePoint <= 'Z') {
                        finalCodePoint = finalCodePoint - 'A' + 1;
                    } else if (finalCodePoint == ' ' || finalCodePoint == '2') {
                        finalCodePoint = 0;
                    } else if (finalCodePoint == '[' || finalCodePoint == '3') {
                        finalCodePoint = 27;
                    } else if (finalCodePoint == '\\' || finalCodePoint == '4') {
                        finalCodePoint = 28;
                    } else if (finalCodePoint == ']' || finalCodePoint == '5') {
                        finalCodePoint = 29;
                    } else if (finalCodePoint == '^' || finalCodePoint == '6') {
                        finalCodePoint = 30;
                    } else if (finalCodePoint == '_' || finalCodePoint == '7' || finalCodePoint == '/') {
                        finalCodePoint = 31;
                    } else if (finalCodePoint == '8') {
                        finalCodePoint = 127;
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // Pass false for controlDownFromEvent because finalCodePoint is already converted
                    mTerminalView.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD, finalCodePoint, false, altDown);
                } else {
                    if (session != null) {
                        session.writeCodePoint(altDown, finalCodePoint);
                    }
                }
            } else {
                TerminalSession session = mTerminalView.getCurrentSession();
                if (session != null && key.length() > 0) {
                    session.write(key);
                }
            }
        }
    }

    @Override
    public boolean performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        return false;
    }

}
