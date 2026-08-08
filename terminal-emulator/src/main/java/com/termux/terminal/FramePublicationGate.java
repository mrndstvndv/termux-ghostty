package com.termux.terminal;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps a mutable worker snapshot from being recycled until the main thread has copied it into
 * the UI-owned render cache. Dirty work that arrives meanwhile remains coalesced for the next
 * build.
 */
final class FramePublicationGate {

    private final AtomicBoolean mSnapshotDirty = new AtomicBoolean(true);
    private final AtomicBoolean mUIUpdatePending = new AtomicBoolean(false);

    void markSnapshotDirty() {
        mSnapshotDirty.set(true);
    }

    boolean isSnapshotDirty() {
        return mSnapshotDirty.get();
    }

    boolean tryStartSnapshotBuild() {
        if (mUIUpdatePending.get()) {
            return false;
        }
        return mSnapshotDirty.compareAndSet(true, false);
    }

    boolean isUIUpdatePending() {
        return mUIUpdatePending.get();
    }

    boolean tryScheduleUIUpdate() {
        return mUIUpdatePending.compareAndSet(false, true);
    }

    boolean completeUIUpdate() {
        mUIUpdatePending.set(false);
        return mSnapshotDirty.get();
    }
}
