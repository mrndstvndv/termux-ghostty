package com.mrndtvndv.term.server

import com.mrndtvndv.term.domain.SshShellChannel
import com.termux.terminal.TerminalSessionIO
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps SSH writes ordered without blocking the UI thread that owns the terminal session. */
internal class TerminalSessionIOBridge(
    private val channel: SshShellChannel,
) : TerminalSessionIO {
    private sealed interface PendingOperation {
        data class Write(val data: ByteArray) : PendingOperation

        data class Resize(
            val columns: Int,
            val rows: Int,
            val cellWidth: Int,
            val cellHeight: Int
        ) : PendingOperation
    }

    private val closed = AtomicBoolean(false)
    private val drainScheduled = AtomicBoolean(false)
    private val pendingOperations = ConcurrentLinkedQueue<PendingOperation>()
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "terminal-ssh-io").apply { isDaemon = true }
    }

    override fun write(data: ByteArray?, offset: Int, count: Int) {
        if (data == null || count <= 0 || closed.get()) return
        pendingOperations.add(PendingOperation.Write(data.copyOfRange(offset, offset + count)))
        scheduleDrain()
    }

    override fun onResize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        if (closed.get()) return
        pendingOperations.add(PendingOperation.Resize(columns, rows, cellWidth, cellHeight))
        scheduleDrain()
    }

    override fun onClose() {
        if (!closed.compareAndSet(false, true)) return
        pendingOperations.clear()
        writer.shutdownNow()
    }

    private fun scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return
        try {
            writer.execute(::drain)
        } catch (_: RejectedExecutionException) {
            drainScheduled.set(false)
            // A concurrent session close won the race with this input event.
        }
    }

    private fun drain() {
        try {
            while (!closed.get()) {
                when (val operation = pendingOperations.poll() ?: break) {
                    is PendingOperation.Write -> drainWrites(operation.data)
                    is PendingOperation.Resize -> runSafely {
                        channel.resizeWindow(
                            operation.columns,
                            operation.rows,
                            operation.columns * operation.cellWidth,
                            operation.rows * operation.cellHeight
                        )
                    }
                }
            }
        } finally {
            drainScheduled.set(false)
            if (!closed.get() && pendingOperations.isNotEmpty()) scheduleDrain()
        }
    }

    private fun drainWrites(first: ByteArray) {
        runSafely {
            val output = channel.outputStream
            output.write(first, 0, first.size)
            while (true) {
                val next = pendingOperations.peek()
                if (next !is PendingOperation.Write) break
                pendingOperations.poll()
                output.write(next.data, 0, next.data.size)
            }
            output.flush()
        }
    }

    private fun runSafely(operation: () -> Unit) {
        try {
            operation()
        } catch (_: Exception) {
            // The session is closing or the SSH channel has disconnected.
        }
    }
}
