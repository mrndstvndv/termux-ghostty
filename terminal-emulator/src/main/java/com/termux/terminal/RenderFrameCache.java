package com.termux.terminal;

import androidx.annotation.Nullable;

/**
 * UI-thread-owned visible-frame cache updated from {@link FrameDelta} publications.
 *
 * <p>Core invariant: Ghostty UI rendering reads only this cache. The worker-published
 * {@link ScreenSnapshot} attached to a {@link FrameDelta} is transport/debug data and may be
 * partial.</p>
 */
public final class RenderFrameCache {

    private final ScreenSnapshot mSnapshot = new ScreenSnapshot();
    private long mAppliedFrameSequence = -1;
    private boolean mInitialized;
    private boolean mLoggedMissingInitialFullRebuild;

    public boolean apply(FrameDelta frameDelta) {
        if (frameDelta == null) {
            throw new IllegalArgumentException("frameDelta must not be null");
        }
        if (frameDelta.getFrameSequence() <= mAppliedFrameSequence) {
            return false;
        }

        ScreenSnapshot transportSnapshot = frameDelta.getTransportSnapshot();
        if (!mInitialized && !frameDelta.isFullRebuild()) {
            logDroppedPartialBeforeInitialization(frameDelta);
            return false;
        }

        int topRowDelta = transportSnapshot.getTopRow() - mSnapshot.getTopRow();
        if (shouldFullRebuild(transportSnapshot, frameDelta, topRowDelta)) {
            mSnapshot.copyFrom(transportSnapshot);
        } else {
            if (topRowDelta != 0) {
                shiftRows(topRowDelta, transportSnapshot.getRows());
            }
            mSnapshot.copyFrameStateFrom(transportSnapshot);
            for (int index = 0; index < frameDelta.getDirtyRowCount(); index++) {
                mSnapshot.copyRowFrom(transportSnapshot, frameDelta.getDirtyRow(index));
            }
        }

        mInitialized = true;
        mLoggedMissingInitialFullRebuild = false;
        mAppliedFrameSequence = frameDelta.getFrameSequence();
        return true;
    }

    public void reset() {
        mInitialized = false;
        mLoggedMissingInitialFullRebuild = false;
        mAppliedFrameSequence = -1;
    }

    public boolean isInitialized() {
        return mInitialized;
    }

    @Nullable
    public ScreenSnapshot getSnapshotForRender(boolean cursorBlinkingEnabled, boolean cursorBlinkState) {
        if (!mInitialized) {
            return null;
        }

        mSnapshot.applyCursorBlinkState(cursorBlinkingEnabled, cursorBlinkState);
        return mSnapshot;
    }

    private boolean shouldFullRebuild(ScreenSnapshot transportSnapshot, FrameDelta frameDelta, int topRowDelta) {
        if (!mInitialized) {
            return true;
        }
        if (frameDelta.isFullRebuild()) {
            return true;
        }
        if (mSnapshot.getRows() != transportSnapshot.getRows()) {
            return true;
        }
        if (mSnapshot.getColumns() != transportSnapshot.getColumns()) {
            return true;
        }
        if (Math.abs(topRowDelta) >= transportSnapshot.getRows()) {
            return true;
        }

        return topRowDelta != 0 && transportSnapshot.getDirtyRowCount() == 0;
    }

    private void shiftRows(int topRowDelta, int rows) {
        if (topRowDelta == 0) {
            return;
        }

        if (topRowDelta > 0) {
            for (int row = 0; row < rows - topRowDelta; row++) {
                mSnapshot.copyRowFrom(mSnapshot, row + topRowDelta, row);
            }
            return;
        }

        int shift = -topRowDelta;
        for (int row = rows - 1; row >= shift; row--) {
            mSnapshot.copyRowFrom(mSnapshot, row - shift, row);
        }
    }

    private void logDroppedPartialBeforeInitialization(FrameDelta frameDelta) {
        if (mLoggedMissingInitialFullRebuild) {
            return;
        }

        GhosttyLog.warn("Dropping partial Ghostty frame before cache initialization"
            + " frame=" + frameDelta.getFrameSequence()
            + " topRow=" + frameDelta.getTopRow()
            + " rows=" + frameDelta.getRows()
            + " columns=" + frameDelta.getColumns()
            + " dirtyRows=" + frameDelta.getDirtyRowCount());
        mLoggedMissingInitialFullRebuild = true;
    }
}
