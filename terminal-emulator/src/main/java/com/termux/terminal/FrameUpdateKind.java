package com.termux.terminal;

/** Classification of the visible work produced by a native snapshot build. */
public enum FrameUpdateKind {
    /** No rows, render metadata, palette, or mode bits changed. */
    UNCHANGED,
    /** The complete viewport must replace the previously published frame. */
    FULL,
    /** Dirty rows and/or frame metadata can update the existing frame. */
    ROWS
}
