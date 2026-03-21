package com.termux.terminal;

/**
 * Worker-published Ghostty frame metadata.
 *
 * <p>The attached {@link ScreenSnapshot} is a transport/debug payload for
 * {@link RenderFrameCache} only. Partial publications may omit unchanged rows and metadata, so UI
 * code must never render that snapshot directly.</p>
 *
 * <p>The attached {@link ViewportLinkSnapshot} is always a full visible-viewport link snapshot for
 * the same published frame sequence.</p>
 */
public final class FrameDelta {

    public static final int REASON_APPEND = 1;
    public static final int REASON_APPEND_DIRECT = 1 << 1;
    public static final int REASON_RESIZE = 1 << 2;
    public static final int REASON_RESET = 1 << 3;
    public static final int REASON_VIEWPORT_SCROLL = 1 << 4;
    public static final int REASON_COLOR_SCHEME = 1 << 5;

    private final long mFrameSequence;
    private final int mReasonFlags;
    private final ScreenSnapshot mTransportSnapshot;
    private final ViewportLinkSnapshot mViewportLinkSnapshot;

    FrameDelta(long frameSequence, int reasonFlags, ScreenSnapshot transportSnapshot) {
        this(frameSequence, reasonFlags, transportSnapshot,
            createDefaultViewportLinkSnapshot(frameSequence, transportSnapshot));
    }

    FrameDelta(long frameSequence, int reasonFlags, ScreenSnapshot transportSnapshot,
               ViewportLinkSnapshot viewportLinkSnapshot) {
        if (transportSnapshot == null) {
            throw new IllegalArgumentException("transportSnapshot must not be null");
        }
        if (viewportLinkSnapshot == null) {
            throw new IllegalArgumentException("viewportLinkSnapshot must not be null");
        }

        mFrameSequence = frameSequence;
        mReasonFlags = reasonFlags;
        mTransportSnapshot = transportSnapshot;
        mViewportLinkSnapshot = viewportLinkSnapshot;
    }

    public long getFrameSequence() {
        return mFrameSequence;
    }

    public int getReasonFlags() {
        return mReasonFlags;
    }

    public boolean isFullRebuild() {
        return mTransportSnapshot.isFullRebuild();
    }

    public int getTopRow() {
        return mTransportSnapshot.getTopRow();
    }

    public int getRows() {
        return mTransportSnapshot.getRows();
    }

    public int getColumns() {
        return mTransportSnapshot.getColumns();
    }

    public int getDirtyRowCount() {
        return mTransportSnapshot.getDirtyRowCount();
    }

    public int getDirtyRow(int index) {
        return mTransportSnapshot.getDirtyRow(index);
    }

    public boolean hasPaletteUpdate() {
        return mTransportSnapshot.hasPaletteUpdate();
    }

    public boolean hasRenderMetadataUpdate() {
        return mTransportSnapshot.hasRenderMetadataUpdate();
    }

    public boolean hasModeBitsUpdate() {
        return mTransportSnapshot.hasModeBitsUpdate();
    }

    public ViewportLinkSnapshot getViewportLinkSnapshot() {
        return mViewportLinkSnapshot;
    }

    ScreenSnapshot getTransportSnapshot() {
        return mTransportSnapshot;
    }

    private static ViewportLinkSnapshot createDefaultViewportLinkSnapshot(long frameSequence,
                                                                          ScreenSnapshot transportSnapshot) {
        if (transportSnapshot == null) {
            throw new IllegalArgumentException("transportSnapshot must not be null");
        }

        return ViewportLinkSnapshot.createEmpty(frameSequence, transportSnapshot.getTopRow(),
            transportSnapshot.getRows(), transportSnapshot.getColumns());
    }
}
