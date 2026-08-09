package com.mrndtvndv.term.server

import com.mrndtvndv.term.domain.SshShellChannel
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSessionIOBridgeTest {
    @Test
    fun writeReturnsWhileShellChannelIsBlocked() {
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val channel = FakeShellChannel(
            outputStream = object : OutputStream() {
                override fun write(byteArray: ByteArray, offset: Int, length: Int) {
                    writeStarted.countDown()
                    releaseWrite.await(1, TimeUnit.SECONDS)
                }

                override fun write(value: Int) = Unit
            }
        )
        val bridge = TerminalSessionIOBridge(channel)
        val writeReturned = CountDownLatch(1)
        val writeThread = thread(start = true) {
            bridge.write(byteArrayOf(0x02), 0, 1)
            writeReturned.countDown()
        }

        assertTrue(writeStarted.await(1, TimeUnit.SECONDS))
        assertTrue(writeReturned.await(100, TimeUnit.MILLISECONDS))

        releaseWrite.countDown()
        writeThread.join(1_000)
        bridge.onClose()
    }
    @Test
    fun queuedWritesShareOneFlush() {
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val flushCompleted = CountDownLatch(1)
        val allWritesCompleted = CountDownLatch(1)
        val flushCount = AtomicInteger()
        val writeCount = AtomicInteger()
        val channel = FakeShellChannel(
            outputStream = object : OutputStream() {
                override fun write(byteArray: ByteArray, offset: Int, length: Int) {
                    when (writeCount.incrementAndGet()) {
                        1 -> {
                            writeStarted.countDown()
                            releaseWrite.await(1, TimeUnit.SECONDS)
                        }
                        2 -> allWritesCompleted.countDown()
                    }
                }

                override fun write(value: Int) = Unit

                override fun flush() {
                    flushCount.incrementAndGet()
                    flushCompleted.countDown()
                }
            }
        )
        val bridge = TerminalSessionIOBridge(channel)
        bridge.write(byteArrayOf(0x01), 0, 1)
        assertTrue(writeStarted.await(1, TimeUnit.SECONDS))
        bridge.write(byteArrayOf(0x02), 0, 1)

        releaseWrite.countDown()
        assertTrue(allWritesCompleted.await(1, TimeUnit.SECONDS))
        assertTrue(flushCompleted.await(1, TimeUnit.SECONDS))
        assertEquals(1, flushCount.get())
        bridge.onClose()
    }

}

private class FakeShellChannel(
    override val outputStream: OutputStream
) : SshShellChannel {
    override val inputStream = ByteArrayInputStream(ByteArray(0))

    override fun resizeWindow(cols: Int, rows: Int, widthPx: Int, heightPx: Int) = Unit

    override fun close() = Unit
}
