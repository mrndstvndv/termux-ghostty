package com.mrndtvndv.term.server

import com.mrndtvndv.term.domain.SshShellChannel
import com.termux.terminal.TerminalSessionIO
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps SSH writes ordered without blocking the UI thread that owns the terminal session. */
internal class TerminalSessionIOBridge(
    private val channel: SshShellChannel,
) : TerminalSessionIO {
    private val closed = AtomicBoolean(false)
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "terminal-ssh-io").apply { isDaemon = true }
    }

    override fun write(data: ByteArray?, offset: Int, count: Int) {
        if (data == null || count <= 0 || closed.get()) return

        val copy = data.copyOfRange(offset, offset + count)
        enqueue { writeToChannel(copy) }
    }

    override fun onResize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        if (closed.get()) return
        enqueue { channel.resizeWindow(columns, rows, columns * cellWidth, rows * cellHeight) }
    }

    override fun onClose() {
        if (!closed.compareAndSet(false, true)) return
        writer.shutdownNow()
    }

    private fun enqueue(operation: () -> Unit) {
        if (closed.get()) return
        try {
            writer.execute {
                if (closed.get()) return@execute
                runSafely(operation)
            }
        } catch (_: RejectedExecutionException) {
            // A concurrent session close won the race with this input event.
        }
    }

    private fun writeToChannel(data: ByteArray) {
        channel.outputStream.write(data, 0, data.size)
        channel.outputStream.flush()
    }

    private fun runSafely(operation: () -> Unit) {
        try {
            operation()
        } catch (_: Exception) {
            // The session is closing or the SSH channel has disconnected.
        }
    }
}
