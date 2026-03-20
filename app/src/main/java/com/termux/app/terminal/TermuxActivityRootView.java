package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.core.view.WindowInsetsCompat;

import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;

/**
 * Applies a residual IME overlap workaround when {@code adjustResize} still leaves the bottom of
 * the terminal UI covered. The correction is based on actual on-screen overlap and uses padding on
 * the root view so it stays stable in freeform and floating windows.
 */
public class TermuxActivityRootView extends LinearLayout {

    private static final String LOG_TAG = "TermuxActivityRootView";

    private final Rect mVisibleWindowRect = new Rect();
    private final Rect mBottomSpaceViewRect = new Rect();
    private final int[] mBottomSpaceViewLocation = new int[2];
    private final Runnable mApplyTerminalMarginAdjustmentRunnable = this::applyTerminalMarginAdjustment;

    private TermuxActivity mActivity;
    private boolean mRootViewLoggingEnabled;
    private boolean mTerminalMarginAdjustmentEnabled;
    private boolean mImeVisible;
    private boolean mTerminalMarginAdjustmentUpdatePosted;
    private int mAppliedBottomCorrection;
    private int mBasePaddingLeft;
    private int mBasePaddingTop;
    private int mBasePaddingRight;
    private int mBasePaddingBottom;

    public TermuxActivityRootView(Context context) {
        super(context);
    }

    public TermuxActivityRootView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public TermuxActivityRootView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setActivity(TermuxActivity activity) {
        mActivity = activity;
    }

    public void setIsRootViewLoggingEnabled(boolean value) {
        mRootViewLoggingEnabled = value;
    }

    public void setTerminalMarginAdjustmentEnabled(boolean enabled) {
        if (mTerminalMarginAdjustmentEnabled == enabled) {
            if (enabled) requestTerminalMarginAdjustmentUpdate();
            return;
        }

        mTerminalMarginAdjustmentEnabled = enabled;

        if (!enabled) {
            removeCallbacks(mApplyTerminalMarginAdjustmentRunnable);
            mTerminalMarginAdjustmentUpdatePosted = false;
            setAppliedBottomCorrection(0);
            return;
        }

        requestApplyInsets();
        requestTerminalMarginAdjustmentUpdate();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        if (!changed) return;
        if (!mTerminalMarginAdjustmentEnabled) return;
        if (!mImeVisible && mAppliedBottomCorrection == 0) return;

        requestTerminalMarginAdjustmentUpdate();
    }

    private void onWindowInsetsChanged(WindowInsets insets) {
        WindowInsetsCompat windowInsets = WindowInsetsCompat.toWindowInsetsCompat(insets);
        int imeBottomInset = windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        int systemBarsBottomInset = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;

        mImeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime()) || imeBottomInset > systemBarsBottomInset;
        updateBasePadding();

        if (mRootViewLoggingEnabled) {
            Logger.logVerbose(LOG_TAG,
                "onWindowInsetsChanged: imeVisible=" + mImeVisible +
                    ", imeBottomInset=" + imeBottomInset +
                    ", systemBarsBottomInset=" + systemBarsBottomInset +
                    ", basePaddingBottom=" + mBasePaddingBottom +
                    ", appliedBottomCorrection=" + mAppliedBottomCorrection);
        }

        if (!mTerminalMarginAdjustmentEnabled) {
            if (mAppliedBottomCorrection != 0) setAppliedBottomCorrection(0);
            return;
        }

        requestTerminalMarginAdjustmentUpdate();
    }

    private void updateBasePadding() {
        mBasePaddingLeft = getPaddingLeft();
        mBasePaddingTop = getPaddingTop();
        mBasePaddingRight = getPaddingRight();
        mBasePaddingBottom = Math.max(0, getPaddingBottom() - mAppliedBottomCorrection);
    }

    private void requestTerminalMarginAdjustmentUpdate() {
        if (!mTerminalMarginAdjustmentEnabled) return;
        if (mTerminalMarginAdjustmentUpdatePosted) return;

        mTerminalMarginAdjustmentUpdatePosted = true;
        post(mApplyTerminalMarginAdjustmentRunnable);
    }

    private void applyTerminalMarginAdjustment() {
        mTerminalMarginAdjustmentUpdatePosted = false;

        int desiredBottomCorrection = calculateDesiredBottomCorrection();
        if (mRootViewLoggingEnabled) {
            Logger.logVerbose(LOG_TAG,
                "applyTerminalMarginAdjustment: imeVisible=" + mImeVisible +
                    ", current=" + mAppliedBottomCorrection +
                    ", desired=" + desiredBottomCorrection);
        }

        setAppliedBottomCorrection(desiredBottomCorrection);
    }

    private int calculateDesiredBottomCorrection() {
        if (!mImeVisible) return 0;
        if (mActivity == null || !mActivity.isVisible()) return 0;

        View bottomSpaceView = mActivity.getTermuxActivityBottomSpaceView();
        if (bottomSpaceView == null || !bottomSpaceView.isShown()) return 0;

        bottomSpaceView.getWindowVisibleDisplayFrame(mVisibleWindowRect);
        bottomSpaceView.getLocationOnScreen(mBottomSpaceViewLocation);
        mBottomSpaceViewRect.set(
            mBottomSpaceViewLocation[0],
            mBottomSpaceViewLocation[1],
            mBottomSpaceViewLocation[0] + bottomSpaceView.getWidth(),
            mBottomSpaceViewLocation[1] + bottomSpaceView.getHeight());

        int overlap = mBottomSpaceViewRect.bottom - mVisibleWindowRect.bottom;
        int desiredBottomCorrection = Math.max(0, mAppliedBottomCorrection + overlap);

        if (mRootViewLoggingEnabled) {
            Logger.logVerbose(LOG_TAG,
                "calculateDesiredBottomCorrection: visibleWindowRect=" + mVisibleWindowRect +
                    ", bottomSpaceViewRect=" + mBottomSpaceViewRect +
                    ", overlap=" + overlap +
                    ", current=" + mAppliedBottomCorrection +
                    ", desired=" + desiredBottomCorrection);
        }

        return desiredBottomCorrection;
    }

    private void setAppliedBottomCorrection(int bottomCorrection) {
        if (bottomCorrection == mAppliedBottomCorrection) return;

        if (mRootViewLoggingEnabled) {
            Logger.logVerbose(LOG_TAG,
                "setAppliedBottomCorrection: current=" + mAppliedBottomCorrection +
                    ", new=" + bottomCorrection +
                    ", basePaddingBottom=" + mBasePaddingBottom);
        }

        mAppliedBottomCorrection = bottomCorrection;
        setPadding(mBasePaddingLeft, mBasePaddingTop, mBasePaddingRight, mBasePaddingBottom + bottomCorrection);
    }

    public static class WindowInsetsListener implements View.OnApplyWindowInsetsListener {
        @Override
        public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
            WindowInsets appliedInsets = v.onApplyWindowInsets(insets);
            if (v instanceof TermuxActivityRootView) {
                ((TermuxActivityRootView) v).onWindowInsetsChanged(insets);
            }
            return appliedInsets;
        }
    }

}
