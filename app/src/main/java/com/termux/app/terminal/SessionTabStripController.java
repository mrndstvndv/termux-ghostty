package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.color.MaterialColors;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TextStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * Controls the horizontal session tab strip shown above the terminal view
 * when use-session-tabs is enabled in termux.properties.
 *
 * Single source of truth: mSelectedIndex drives the highlighted tab.
 * Structural changes (add/remove sessions) rebuild tabs via {@link #notifyDataSetChanged()}.
 * Selection changes only update styling via {@link #onSessionChanged()}.
 */
public class SessionTabStripController {

    private final TermuxActivity mActivity;
    private final LinearLayout mTabStrip;
    private final HorizontalScrollView mScrollView;

    /** Single source of truth for which tab is selected. */
    private int mSelectedIndex = -1;

    /** Whether the dark theme is active (cached on rebuild). */
    private boolean mIsDark;

    private static final int TAB_PADDING_HORIZONTAL_DP = 12;
    private static final int TAB_PADDING_VERTICAL_DP = 6;
    private static final int TAB_TEXT_SIZE_SP = 13;

    /** Cached colors, refreshed on rebuild. */
    private int mColorOnSurface;
    private int mColorOnSurfaceVariant;
    private int mColorPrimaryContainer;
    private int mColorOnPrimaryContainer;
    private int mColorSurfaceContainerLow;
    private int mColorError;

    public SessionTabStripController(TermuxActivity activity, LinearLayout tabStrip, HorizontalScrollView scrollView) {
        mActivity = activity;
        mTabStrip = tabStrip;
        mScrollView = scrollView;
    }

    /**
     * Resolve Material You / M3 colors from theme attributes.
     */
    private void resolveColors() {
        mColorOnSurface = MaterialColors.getColor(mActivity, com.google.android.material.R.attr.colorOnSurface, Color.WHITE);
        mColorOnSurfaceVariant = MaterialColors.getColor(mActivity, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY);
        mColorPrimaryContainer = MaterialColors.getColor(mActivity, com.google.android.material.R.attr.colorPrimaryContainer, 0xFFDDDDDD);
        mColorOnPrimaryContainer = MaterialColors.getColor(mActivity, com.google.android.material.R.attr.colorOnPrimaryContainer, Color.BLACK);
        mColorSurfaceContainerLow = MaterialColors.getColor(mActivity, com.google.android.material.R.attr.colorSurfaceContainerLow, Color.TRANSPARENT);
        mColorError = MaterialColors.getColor(mActivity, com.google.android.material.R.attr.colorError, Color.RED);
    }

    /**
     * Full rebuild: call when sessions are added/removed/renamed.
     */
    @SuppressLint("SetTextI18n")
    public void notifyDataSetChanged() {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        mTabStrip.removeAllViews();
        mSelectedIndex = -1;

        resolveColors();
        TerminalSession currentSession = mActivity.getCurrentSession();

        List<TermuxSession> sessions = service.getTermuxSessions();
        for (int i = 0; i < sessions.size(); i++) {
            TerminalSession session = sessions.get(i).getTerminalSession();
            if (session == null) continue;

            boolean isCurrent = session == currentSession;
            if (isCurrent) mSelectedIndex = i;

            TextView tab = createTabView(i, session, isCurrent);
            mTabStrip.addView(tab);
        }

        // "+" button always at the end
        mTabStrip.addView(createAddTabView());

        // If no session was marked current but sessions exist, default to first
        if (mSelectedIndex < 0 && !sessions.isEmpty()) {
            mSelectedIndex = 0;
            View firstTab = mTabStrip.getChildAt(0);
            if (firstTab instanceof TextView) {
                styleTabSelected((TextView) firstTab);
            }
        }

        scrollToTab(mSelectedIndex);
        updateBackgroundColor();
    }

    /**
     * Selection changed: only update styling of old and new tab.
     * Call this when the current session changes without structural changes.
     */
    public void onSessionChanged() {
        TerminalSession currentSession = mActivity.getCurrentSession();
        if (currentSession == null) return;

        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        int newIndex = service.getIndexOfSession(currentSession);
        if (newIndex < 0) return;

        // If tabs aren't built yet or out of sync, do full rebuild
        // Account for "+" button: tabs count should be sessions + 1
        int expectedChildCount = service.getTermuxSessionsSize() + 1;
        if (mTabStrip.getChildCount() != expectedChildCount) {
            notifyDataSetChanged();
            return;
        }

        if (newIndex == mSelectedIndex) return;

        int oldIndex = mSelectedIndex;
        mSelectedIndex = newIndex;

        // Unhighlight old tab
        if (oldIndex >= 0 && oldIndex < mTabStrip.getChildCount()) {
            View oldTab = mTabStrip.getChildAt(oldIndex);
            if (oldTab instanceof TextView) {
                styleTabUnselected((TextView) oldTab);
            }
        }

        // Highlight new tab
        if (mSelectedIndex >= 0 && mSelectedIndex < mTabStrip.getChildCount()) {
            View newTab = mTabStrip.getChildAt(mSelectedIndex);
            if (newTab instanceof TextView) {
                styleTabSelected((TextView) newTab);
            }
        }

        scrollToTab(mSelectedIndex);
        updateBackgroundColor();
    }

    private TextView createTabView(int index, TerminalSession session, boolean isCurrent) {
        TextView tab = new TextView(mActivity);

        int padH = dpToPx(TAB_PADDING_HORIZONTAL_DP);
        int padV = dpToPx(TAB_PADDING_VERTICAL_DP);
        tab.setPadding(padH, padV, padH, padV);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        );
        lp.setMargins(dpToPx(2), 0, dpToPx(2), 0);
        tab.setLayoutParams(lp);
        tab.setGravity(Gravity.CENTER_VERTICAL);
        tab.setTextSize(TAB_TEXT_SIZE_SP);

        // Label
        String name = session.mSessionName;
        String numberPart = "[" + (index + 1) + "]";
        String label = TextUtils.isEmpty(name) ? numberPart : numberPart + " " + name;
        tab.setText(label);

        // Text color based on session state
        boolean sessionRunning = session.isRunning();
        int textColor;
        if (!sessionRunning && session.getExitStatus() != 0) {
            textColor = mColorError;
        } else {
            textColor = mColorOnSurface;
        }
        tab.setTextColor(textColor);

        if (!sessionRunning) {
            tab.setPaintFlags(tab.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        }

        // Selection style
        if (isCurrent) {
            styleTabSelected(tab);
        } else {
            styleTabUnselected(tab);
        }

        // Tag for identification
        tab.setTag(session);

        // Click to switch - setCurrentSession() triggers onCurrentSessionChanged() internally
        tab.setOnClickListener(v -> {
            mActivity.getTermuxTerminalSessionClient().setCurrentSession(session);
        });

        // Long press for context menu
        tab.setOnLongClickListener(v -> {
            showSessionContextMenu(session, index);
            return true;
        });

        return tab;
    }

    private void styleTabSelected(TextView tab) {
        tab.setTypeface(null, Typeface.BOLD);
        tab.setBackgroundColor(mColorPrimaryContainer);
        tab.setTextColor(mColorOnPrimaryContainer);
    }

    private void styleTabUnselected(TextView tab) {
        tab.setTypeface(null, Typeface.NORMAL);
        tab.setBackgroundColor(Color.TRANSPARENT);

        // Restore original text color based on session state
        Object tag = tab.getTag();
        if (tag instanceof TerminalSession) {
            TerminalSession session = (TerminalSession) tag;
            if (!session.isRunning() && session.getExitStatus() != 0) {
                tab.setTextColor(mColorError);
                return;
            }
        }
        tab.setTextColor(mColorOnSurface);
    }

    private TextView createAddTabView() {
        TextView tab = new TextView(mActivity);

        int padH = dpToPx(TAB_PADDING_HORIZONTAL_DP);
        int padV = dpToPx(TAB_PADDING_VERTICAL_DP);
        tab.setPadding(padH, padV, padH, padV);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        );
        lp.setMargins(dpToPx(2), 0, dpToPx(2), 0);
        tab.setLayoutParams(lp);
        tab.setGravity(Gravity.CENTER_VERTICAL);
        tab.setTextSize(TAB_TEXT_SIZE_SP + 2);
        tab.setText("+");
        tab.setTextColor(mColorOnSurfaceVariant);

        tab.setOnClickListener(v -> {
            mActivity.getTermuxTerminalSessionClient().addNewSession(false, null);
        });

        tab.setOnLongClickListener(v -> {
            mActivity.getTermuxTerminalSessionClient().addNewSession(true, null);
            return true;
        });

        return tab;
    }

    private void showSessionContextMenu(TerminalSession session, int index) {
        List<CharSequence> actionLabels = new ArrayList<>();
        List<Runnable> actionHandlers = new ArrayList<>();

        actionLabels.add(mActivity.getString(R.string.action_rename_session));
        actionHandlers.add(() -> mActivity.getTermuxTerminalSessionClient().renameSession(session));

        if (mActivity.getTermuxService() != null && mActivity.getTermuxService().canBubbleSessions()) {
            if (mActivity.getTermuxService().isSessionBubbled(session.mHandle)) {
                actionLabels.add(mActivity.getString(R.string.action_unbubble_session));
                actionHandlers.add(() -> mActivity.getTermuxService().unbubbleSession(session));
            } else if (session.isRunning()) {
                actionLabels.add(mActivity.getString(R.string.action_bubble_session));
                actionHandlers.add(() -> mActivity.getTermuxService().bubbleSession(session, true));
            }
        }

        new AlertDialog.Builder(mActivity)
            .setItems(actionLabels.toArray(new CharSequence[0]), (dialog, which) -> actionHandlers.get(which).run())
            .show();
    }

    private void scrollToTab(int index) {
        if (index < 0 || index >= mTabStrip.getChildCount()) return;

        View tabView = mTabStrip.getChildAt(index);
        mScrollView.post(() -> {
            int scrollX = tabView.getLeft() - (mScrollView.getWidth() - tabView.getWidth()) / 2;
            mScrollView.smoothScrollTo(Math.max(0, scrollX), 0);
        });
    }

    public void setVisible(boolean visible) {
        mScrollView.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Update tab strip background to match the terminal view.
     */
    public void updateBackgroundColor() {
        TerminalSession session = mActivity.getCurrentSession();
        if (session != null) {
            mScrollView.setBackgroundColor(session.getBackgroundColor());
        } else {
            // No session yet - use global color scheme directly
            mScrollView.setBackgroundColor(TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        }
    }

    private int dpToPx(int dp) {
        float density = mActivity.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
