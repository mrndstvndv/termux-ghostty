package com.termux.terminal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ViewportLinkSnapshotTest {

    @Test
    public void contentEqualityIgnoresFrameSequence() {
        ViewportLinkSnapshot first = snapshot(1, "https://example.com");
        ViewportLinkSnapshot second = snapshot(2, "https://example.com");

        assertTrue(first.hasSameContent(second));
    }
    @Test
    public void copyContentSharesParsedSegments() {
        ViewportLinkSnapshot source = snapshot(3, "https://example.com");
        ViewportLinkSnapshot target = new ViewportLinkSnapshot();

        target.copyContentFrom(source);

        assertTrue(target.hasSameContent(source));
        assertEquals(source.getFrameSequence(), target.getFrameSequence());
        assertSame(source.getSegment(0), target.getSegment(0));
    }

    @Test
    public void contentEqualityDetectsGeometryAndSegmentChanges() {
        ViewportLinkSnapshot original = snapshot(1, "https://example.com");
        ViewportLinkSnapshot changedUrl = snapshot(2, "https://termux.dev");
        ViewportLinkSnapshot changedGeometry = ViewportLinkSnapshot.create(
            2,
            -1,
            2,
            8,
            new ViewportLinkSnapshot.Segment[] {
                new ViewportLinkSnapshot.Segment(1, 1, 5, "https://example.com", TerminalLinkSource.SOURCE_OSC8)
            }
        );

        assertFalse(original.hasSameContent(changedUrl));
        assertFalse(original.hasSameContent(changedGeometry));
        assertFalse(original.hasSameContent(null));
    }

    private static ViewportLinkSnapshot snapshot(long sequence, String url) {
        return ViewportLinkSnapshot.create(
            sequence,
            0,
            2,
            8,
            new ViewportLinkSnapshot.Segment[] {
                new ViewportLinkSnapshot.Segment(1, 1, 5, url, TerminalLinkSource.SOURCE_OSC8)
            }
        );
    }
}
