package com.termux.terminal.compose.gpu

import java.util.concurrent.atomic.AtomicReference

/**
 * One-slot latest-wins publication primitive.
 *
 * The released marker lives in the same atomic state as the snapshot. This
 * makes release linearizable with publish: a publication cannot appear after
 * a completed release even when the calls race.
 */
internal class TerminalSnapshotHandoff {
    private val slot = AtomicReference<Slot>(Empty(Long.MIN_VALUE))

    fun publish(snapshot: GlesTerminalSnapshot): Boolean {
        while (true) {
            val current = slot.get()
            if (
                current === Released ||
                current.revision >= snapshot.presentationRevision ||
                current.contentRevision > snapshot.frame.sequence
            ) {
                return false
            }
            if (slot.compareAndSet(current, Pending(snapshot))) return true
        }
    }

    fun acquire(): GlesTerminalSnapshot? {
        while (true) {
            when (val current = slot.get()) {
                Released -> return null
                is Empty -> return null
                is Pending -> if (slot.compareAndSet(
                        current,
                        Empty(current.snapshot.presentationRevision, current.snapshot.frame.sequence)
                    )
                ) {
                    return current.snapshot
                }
            }
        }
    }

    fun hasPending(): Boolean = slot.get() is Pending

    fun release() {
        slot.getAndSet(Released)
    }

    private interface Slot {
        val revision: Long
        val contentRevision: Long
    }

    private data class Empty(
        override val revision: Long,
        override val contentRevision: Long = Long.MIN_VALUE
    ) : Slot

    private object Released : Slot {
        override val revision: Long = Long.MAX_VALUE
        override val contentRevision: Long = Long.MAX_VALUE
    }

    private class Pending(val snapshot: GlesTerminalSnapshot) : Slot {
        override val revision: Long = snapshot.presentationRevision
        override val contentRevision: Long = snapshot.frame.sequence
    }
}
