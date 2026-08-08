package com.termux.terminal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;

public final class FramePublicationDecisionTest {

    private static final int SNAPSHOT_MAGIC = 0x54475832;

    @Test
    public void suppressesUnchangedRowsAndLinks() {
        ScreenSnapshot candidate = unchangedSnapshot();
        ViewportLinkSnapshot links = links(0, "https://example.com");
        FrameDelta published = new FrameDelta(1, FrameDelta.REASON_APPEND, candidate,
            links(1, "https://example.com"));

        assertFalse(FramePublicationDecision.shouldPublish(candidate, links, published));
    }

    @Test
    public void publishesInitialAndLinkOnlyFrames() {
        ScreenSnapshot candidate = unchangedSnapshot();
        ViewportLinkSnapshot links = links(0, "https://termux.dev");
        FrameDelta published = new FrameDelta(1, FrameDelta.REASON_APPEND, candidate,
            links(1, "https://example.com"));

        assertTrue(FramePublicationDecision.shouldPublish(candidate, links, null));
        assertTrue(FramePublicationDecision.shouldPublish(candidate, links, published));
    }

    private static ScreenSnapshot unchangedSnapshot() {
        ScreenSnapshot snapshot = new ScreenSnapshot();
        ByteBuffer buffer = snapshot.getBuffer().order(ByteOrder.nativeOrder());
        buffer.putInt(SNAPSHOT_MAGIC);
        buffer.putInt(0);
        buffer.putInt(1);
        buffer.putInt(4);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        snapshot.markNativeSnapshot(buffer.position());
        return snapshot;
    }

    private static ViewportLinkSnapshot links(long sequence, String url) {
        return ViewportLinkSnapshot.create(sequence, 0, 1, 4,
            new ViewportLinkSnapshot.Segment[] {
                new ViewportLinkSnapshot.Segment(0, 0, 4, url)
            });
    }
}
