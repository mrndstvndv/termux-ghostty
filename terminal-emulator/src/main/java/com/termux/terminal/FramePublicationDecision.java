package com.termux.terminal;

/** Decides whether a completed worker build contains new visible state. */
final class FramePublicationDecision {

    private FramePublicationDecision() {
    }

    static boolean shouldPublish(ScreenSnapshot candidate, ViewportLinkSnapshot candidateLinks,
                                 FrameDelta publishedFrame) {
        if (candidate == null || candidateLinks == null) {
            throw new IllegalArgumentException("Candidate snapshots must not be null");
        }
        if (candidate.getUpdateKind() != FrameUpdateKind.UNCHANGED) {
            return true;
        }
        return publishedFrame == null
            || !candidateLinks.hasSameContent(publishedFrame.getViewportLinkSnapshot());
    }
}
