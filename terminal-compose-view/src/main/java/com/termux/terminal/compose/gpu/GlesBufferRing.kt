package com.termux.terminal.compose.gpu

import android.opengl.GLES30
import java.nio.FloatBuffer

private const val DefaultRingSlotCount = 3
private const val MaxFenceWaitAttempts = 4
private const val FenceWaitTimeoutNanos = 1_000_000L

/** GL-free slot ledger used to test the bounded in-flight policy. */
internal class GlesBufferRingLedger(slotCount: Int = DefaultRingSlotCount) {
    private val inFlight = BooleanArray(slotCount)
    private var nextSlot = 0

    init {
        require(slotCount >= 2) { "slotCount must provide at least two slots" }
    }

    fun acquire(): Int {
        repeat(inFlight.size) { offset ->
            val slot = (nextSlot + offset) % inFlight.size
            if (inFlight[slot]) return@repeat
            inFlight[slot] = true
            nextSlot = (slot + 1) % inFlight.size
            return slot
        }
        return -1
    }

    fun retire(slot: Int) {
        require(slot in inFlight.indices) { "slot is outside the ring" }
        check(inFlight[slot]) { "slot is not in flight" }
        inFlight[slot] = false
    }

    fun reset() {
        inFlight.fill(false)
        nextSlot = 0
    }

    fun inFlightCount(): Int = inFlight.count { it }
}

/**
 * A bounded GPU-buffer ring. Each draw fences the slot it submitted, and a
 * later reuse performs only bounded client waits before overwriting it.
 */
@Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
internal class GlesBufferRing private constructor(
    private val capacityBytes: Int,
    private val slotCount: Int
) {
    private class Slot(val bufferId: Int) {
        var fence: Long = 0L
        var poisoned = false
    }

    private var slots = emptyArray<Slot>()
    private var nextSlot = 0
    private var activeSlot: Slot? = null
    private var released = false

    companion object {
        fun create(
            capacityBytes: Int,
            slotCount: Int = DefaultRingSlotCount
        ): GlesBufferRing {
            require(capacityBytes > 0) { "capacityBytes must be positive" }
            require(slotCount >= 2) { "slotCount must provide at least two slots" }
            return GlesBufferRing(capacityBytes, slotCount).also { it.initialize() }
        }
    }

    fun uploadAndDraw(
        vertexBuffer: FloatBuffer,
        vertexCount: Int,
        configureAttributes: () -> Unit
    ) {
        check(vertexCount > 0) { "vertexCount must be positive" }
        check(vertexBuffer.remaining() * Float.SIZE_BYTES <= capacityBytes) {
            "vertex data exceeds the bounded ring slot"
        }
        val slot = acquireSlot()
        activeSlot = slot
        try {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, slot.bufferId)
            checkGlError("buffer-bind")
            configureAttributes()
            checkGlError("vertex-attributes")
            GLES30.glBufferSubData(
                GLES30.GL_ARRAY_BUFFER,
                0,
                vertexBuffer.remaining() * Float.SIZE_BYTES,
                vertexBuffer
            )
            checkGlError("buffer-upload")
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)
            checkGlError("buffer-draw")
            val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            checkGlError("fence-create")
            if (fence == 0L) throw GlesResourceException("buffer-ring: glFenceSync returned 0")
            slot.fence = fence
            activeSlot = null
        } catch (error: RuntimeException) {
            slot.poisoned = true
            activeSlot = null
            throw if (error is GlesResourceException) {
                error
            } else {
                GlesResourceException("buffer-ring: submission failed", error)
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        activeSlot = null
        var failure: RuntimeException? = null
        slots.forEach { slot ->
            if (slot.fence != 0L) {
                try {
                    GLES30.glDeleteSync(slot.fence)
                    checkGlError("fence-delete")
                } catch (error: RuntimeException) {
                    failure = failure ?: error
                } finally {
                    slot.fence = 0L
                }
            }
            try {
                GLES30.glDeleteBuffers(1, intArrayOf(slot.bufferId), 0)
                checkGlError("buffer-delete")
            } catch (error: RuntimeException) {
                failure = failure ?: error
            }
        }
        slots = emptyArray()
        failure?.let { throw it }
    }

    private fun initialize() {
        val bufferIds = IntArray(slotCount)
        try {
            GLES30.glGenBuffers(slotCount, bufferIds, 0)
            checkGlError("buffer-generate")
            if (bufferIds.any { it == 0 }) {
                throw GlesResourceException("buffer-ring: glGenBuffers returned 0")
            }
            bufferIds.forEach { bufferId ->
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufferId)
                checkGlError("buffer-bind")
                GLES30.glBufferData(
                    GLES30.GL_ARRAY_BUFFER,
                    capacityBytes,
                    null,
                    GLES30.GL_DYNAMIC_DRAW
                )
                checkGlError("buffer-allocate")
            }
            slots = bufferIds.map(::Slot).toTypedArray()
        } catch (error: RuntimeException) {
            if (bufferIds.any { it != 0 }) {
                GLES30.glDeleteBuffers(slotCount, bufferIds, 0)
            }
            throw if (error is GlesResourceException) {
                error
            } else {
                GlesResourceException("buffer-ring: initialization failed", error)
            }
        }
    }

    private fun acquireSlot(): Slot {
        check(!released) { "buffer ring is released" }
        check(activeSlot == null) { "buffer ring already has an active slot" }
        var timedOut = false
        var lastFailure: RuntimeException? = null
        repeat(slots.size) {
            val slot = slots[nextSlot]
            nextSlot = (nextSlot + 1) % slots.size
            if (slot.poisoned) return@repeat
            try {
                if (waitForFence(slot)) return slot
                timedOut = true
            } catch (error: RuntimeException) {
                slot.poisoned = true
                lastFailure = error
            }
        }
        throw lastFailure ?: GlesResourceException(
            if (timedOut) {
                "buffer-ring: all slots remained in flight after bounded waits"
            } else {
                "buffer-ring: no reusable slots"
            }
        )
    }

    @Suppress("ReturnCount")
    private fun waitForFence(slot: Slot): Boolean {
        if (slot.fence == 0L) return true
        var status = GLES30.glClientWaitSync(slot.fence, 0, 0L)
        checkGlError("fence-check")
        if (status.isSignaled()) {
            deleteFence(slot)
            return true
        }
        if (status != GLES30.GL_TIMEOUT_EXPIRED) {
            throw GlesResourceException("buffer-ring: glClientWaitSync failed: $status")
        }
        repeat(MaxFenceWaitAttempts) {
            status = GLES30.glClientWaitSync(
                slot.fence,
                GLES30.GL_SYNC_FLUSH_COMMANDS_BIT,
                FenceWaitTimeoutNanos
            )
            checkGlError("fence-wait")
            if (status.isSignaled()) {
                deleteFence(slot)
                return true
            }
            if (status != GLES30.GL_TIMEOUT_EXPIRED) {
                throw GlesResourceException("buffer-ring: glClientWaitSync failed: $status")
            }
        }
        return false
    }

    private fun deleteFence(slot: Slot) {
        GLES30.glDeleteSync(slot.fence)
        checkGlError("fence-delete")
        slot.fence = 0L
    }

    private fun Int.isSignaled(): Boolean =
        this == GLES30.GL_ALREADY_SIGNALED || this == GLES30.GL_CONDITION_SATISFIED

    private fun checkGlError(stage: String) {
        repeat(8) {
            val error = GLES30.glGetError()
            if (error == GLES30.GL_NO_ERROR) return
            throw GlesResourceException("$stage: 0x${error.toString(16)}")
        }
    }
}
