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

    public enum ApplyResult {
        APPLIED(false),
        IGNORED_OLDER_OR_DUPLICATE(false),
        REJECTED_UNINITIALIZED_PARTIAL(true),
        REJECTED_SEQUENCE_GAP(true),
        REJECTED_PARTIAL_REQUIRING_FULL_REBUILD(true);

        private final boolean mRequiresFullRefresh;

        ApplyResult(boolean requiresFullRefresh) {
            mRequiresFullRefresh = requiresFullRefresh;
        }

        public boolean requiresFullRefresh() {
            return mRequiresFullRefresh;
        }
    }

    private final ScreenSnapshot mSnapshot = new ScreenSnapshot();
    private long mAppliedFrameSequence = -1;
    private boolean mInitialized;
    private boolean mLoggedMissingInitialFullRebuild;
    private boolean mLoggedPartialRequiringFullRebuild;

    /**
     * Partial Ghostty frame deltas are only valid when the UI cache has contiguous sequence
     * history. If one or more frames were missed, the cache must be rebuilt from a fresh backend
     * snapshot instead of trying to guess the missing state from transport data.
     */
    public ApplyResult apply(FrameDelta frameDelta) {
        if (frameDelta == null) {
            throw new IllegalArgumentException("frameDelta must not be null");
        }
        if (frameDelta.getFrameSequence() <= mAppliedFrameSequence) {
            return ApplyResult.IGNORED_OLDER_OR_DUPLICATE;
        }

        ScreenSnapshot transportSnapshot = frameDelta.getTransportSnapshot();
        if (!mInitialized && !frameDelta.isFullRebuild()) {
            logDroppedPartialBeforeInitialization(frameDelta);
            return ApplyResult.REJECTED_UNINITIALIZED_PARTIAL;
        }
        if (hasSequenceGap(frameDelta)) {
            return ApplyResult.REJECTED_SEQUENCE_GAP;
        }

        int topRowDelta = transportSnapshot.getTopRow() - mSnapshot.getTopRow();
        boolean requiresFullRebuild = shouldFullRebuild(transportSnapshot, frameDelta, topRowDelta);
        if (requiresFullRebuild && !frameDelta.isFullRebuild()) {
            logDroppedPartialRequiringFullRebuild(frameDelta, topRowDelta);
            return ApplyResult.REJECTED_PARTIAL_REQUIRING_FULL_REBUILD;
        }

        if (requiresFullRebuild) {
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
        mLoggedPartialRequiringFullRebuild = false;
        mAppliedFrameSequence = frameDelta.getFrameSequence();
        return ApplyResult.APPLIED;
    }

    public void reset() {
        mInitialized = false;
        mLoggedMissingInitialFullRebuild = false;
        mLoggedPartialRequiringFullRebuild = false;
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

    private boolean hasSequenceGap(FrameDelta frameDelta) {
        if (!mInitialized) {
            return false;
        }
        if (frameDelta.isFullRebuild()) {
            return false;
        }

        return frameDelta.getFrameSequence() != mAppliedFrameSequence + 1;
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

    private void logDroppedPartialRequiringFullRebuild(FrameDelta frameDelta, int topRowDelta) {
        if (mLoggedPartialRequiringFullRebuild) {
            return;
        }

        GhosttyLog.warn("Dropping partial Ghostty frame that requires full rebuild"
            + " frame=" + frameDelta.getFrameSequence()
            + " cachedTopRow=" + mSnapshot.getTopRow()
            + " cachedRows=" + mSnapshot.getRows()
            + " cachedColumns=" + mSnapshot.getColumns()
            + " nextTopRow=" + frameDelta.getTopRow()
            + " nextRows=" + frameDelta.getRows()
            + " nextColumns=" + frameDelta.getColumns()
            + " topRowDelta=" + topRowDelta
            + " dirtyRows=" + frameDelta.getDirtyRowCount());
        mLoggedPartialRequiringFullRebuild = true;
    }
}
