package com.termux.terminal;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class GhosttySessionWorkerStartupTest {

    @Test
    public void sshOutputRegistrationPrecedesInitialSnapshot() {
        List<String> events = new ArrayList<>();

        GhosttySessionWorker.initializeWorkerStartup(
            true,
            () -> events.add("snapshot"),
            () -> events.add("ssh-output")
        );

        assertEquals(Arrays.asList("ssh-output", "snapshot"), events);
    }
}
