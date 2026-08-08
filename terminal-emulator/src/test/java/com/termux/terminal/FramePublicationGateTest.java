package com.termux.terminal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FramePublicationGateTest {

    @Test
    public void dirtySnapshotWaitsUntilOutstandingUIUpdateIsConsumed() {
        FramePublicationGate gate = new FramePublicationGate();

        assertTrue(gate.tryStartSnapshotBuild());
        assertTrue(gate.tryScheduleUIUpdate());
        gate.markSnapshotDirty();
        gate.markSnapshotDirty();

        assertFalse(gate.tryStartSnapshotBuild());
        assertTrue(gate.isSnapshotDirty());
        assertTrue(gate.completeUIUpdate());
        assertTrue(gate.tryStartSnapshotBuild());
        assertFalse(gate.tryStartSnapshotBuild());
    }
}
