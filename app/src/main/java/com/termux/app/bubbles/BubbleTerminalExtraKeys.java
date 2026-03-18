package com.termux.app.bubbles;

import android.view.View;

import androidx.annotation.NonNull;

import com.termux.app.BubbleSessionActivity;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.terminal.io.TerminalExtraKeys;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

public final class BubbleTerminalExtraKeys extends TerminalExtraKeys {

    private final BubbleSessionActivity mActivity;
    private final BubbleTerminalViewClient mTerminalViewClient;
    private final ExtraKeysInfo mExtraKeysInfo;

    public BubbleTerminalExtraKeys(@NonNull BubbleSessionActivity activity, @NonNull TerminalView terminalView,
                                   @NonNull BubbleTerminalViewClient terminalViewClient) {
        super(terminalView);
        mActivity = activity;
        mTerminalViewClient = terminalViewClient;
        mExtraKeysInfo = TermuxTerminalExtraKeys.loadExtraKeysInfo(activity, activity.getProperties());
    }

    public ExtraKeysInfo getExtraKeysInfo() {
        return mExtraKeysInfo;
    }

    @Override
    protected void onTerminalExtraKeyButtonClick(View view, String key, boolean ctrlDown, boolean altDown,
                                                 boolean shiftDown, boolean fnDown) {
        if ("KEYBOARD".equals(key)) {
            mTerminalViewClient.onToggleSoftKeyboardRequest();
            return;
        }

        if ("PASTE".equals(key)) {
            TerminalSession currentSession = mActivity.getCurrentSession();
            if (currentSession != null)
                mActivity.getTerminalSessionClient().onPasteTextFromClipboard(currentSession);
            return;
        }

        if ("SCROLL".equals(key)) {
            TerminalSession currentSession = mActivity.getCurrentSession();
            if (currentSession != null)
                currentSession.toggleAutoScrollDisabled();
            return;
        }

        super.onTerminalExtraKeyButtonClick(view, key, ctrlDown, altDown, shiftDown, fnDown);
    }

}
