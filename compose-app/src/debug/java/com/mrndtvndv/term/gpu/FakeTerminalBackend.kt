package com.mrndtvndv.term.gpu

import com.termux.terminal.compose.TerminalBackend
import com.termux.terminal.compose.TerminalBackendListener
import com.termux.terminal.compose.TerminalCommand
import com.termux.terminal.compose.TerminalCommandResult
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalSelection
import com.termux.terminal.compose.TerminalSize

/** State displayed by the laboratory controls and invariant panel. */
internal data class GpuLabBackendState(
    val sceneIndex: Int,
    val sceneId: String,
    val sceneTitle: String,
    val expectedInvariants: List<String>,
    val frameIndex: Int,
    val sequence: Long,
    val topRow: Int,
    val size: TerminalSize,
    val selection: TerminalSelection,
    val checksum: String,
    val expectedChecksum: String,
    val sentinel: String
)

/**
 * Main-thread-only terminal backend for the debug renderer laboratory.
 *
 * It never starts a process and never exposes mutable row storage. Every
 * publication constructs a complete [TerminalFrame] with a new sequence.
 */
internal class FakeTerminalBackend(
    scenes: List<GpuLabScene> = GpuLabScenes.all
) : TerminalBackend {

    private val scenes = scenes.toList().also { require(it.isNotEmpty()) }

    private var released = false
    private var listener: TerminalBackendListener? = null
    private var stateObserver: (() -> Unit)? = null
    private var sceneIndex = 0
    private var frameIndex = 0
    private var sequence = 0L
    private var viewportTopRow = 0
    private var size = DEFAULT_SIZE
    private var frame: TerminalFrame? = null
    private var selection = TerminalSelection.EMPTY
    private var expectedChecksum = ""

    init {
        publishFrame(notify = false)
    }

    val sceneCount: Int
        get() = scenes.size

    val currentScene: GpuLabScene
        get() = scenes[sceneIndex]

    /** Registers the Compose-side state bridge; calls are still main-thread-only. */
    fun observe(observer: (() -> Unit)?) {
        stateObserver = observer
        observer?.invoke()
    }

    fun snapshot(): GpuLabBackendState {
        val currentFrame = frame
        val checksum = currentFrame?.let(::checksumForFrame) ?: EMPTY_FRAME_CHECKSUM
        if (currentFrame != null) {
            check(checksum == expectedChecksum) {
                "Fake backend snapshot checksum drifted from its published expectation"
            }
        }
        return GpuLabBackendState(
            sceneIndex = sceneIndex,
            sceneId = currentScene.id,
            sceneTitle = currentScene.title,
            expectedInvariants = currentScene.expectedInvariants,
            frameIndex = frameIndex,
            sequence = currentFrame?.sequence ?: sequence,
            topRow = currentFrame?.topRow ?: viewportTopRow,
            size = size,
            selection = selection,
            checksum = checksum,
            expectedChecksum = expectedChecksum,
            sentinel = currentFrame?.row(0)?.textString().orEmpty()
        )
    }

    fun previousScene() {
        if (released) return
        sceneIndex = (sceneIndex - 1 + scenes.size) % scenes.size
        resetSceneState()
    }

    fun nextScene() {
        if (released) return
        sceneIndex = (sceneIndex + 1) % scenes.size
        resetSceneState()
    }

    fun selectScene(index: Int) {
        if (released) return
        require(index in scenes.indices) { "Unknown fake-terminal scene index: $index" }
        if (sceneIndex == index) return
        sceneIndex = index
        resetSceneState()
    }

    /** Advances exactly one deterministic content revision. */
    fun step() {
        if (released) return
        frameIndex = if (frameIndex == Int.MAX_VALUE) 0 else frameIndex + 1
        if (currentScene.scrollsViewport) advanceScrollPosition()
        publishFrame(notify = true)
    }

    override fun attach(listener: TerminalBackendListener) {
        if (released) return
        this.listener = listener
        listener.onFrameInvalidated()
    }

    override fun detach() {
        listener = null
    }

    override fun refresh() {
        if (released) return
        if (frame == null) publishFrame(notify = false)
        listener?.onFrameInvalidated()
    }

    override fun resize(size: TerminalSize) {
        if (released || this.size == size) return
        this.size = size
        viewportTopRow = viewportTopRow.coerceAtMost(maxTopRow())
        publishFrame(notify = true)
    }

    override fun submit(command: TerminalCommand): TerminalCommandResult {
        if (released) return TerminalCommandResult.Failure("Fake backend released")
        when (command) {
            is TerminalCommand.Text -> if (command.text.isNotEmpty()) step()
            is TerminalCommand.Key -> if (command.down) step()
            is TerminalCommand.CursorMove -> step()
            is TerminalCommand.Mouse -> Unit
            is TerminalCommand.Scroll -> {
                viewportTopRow = (viewportTopRow + command.rowsDown).coerceIn(0, maxTopRow())
                publishFrame(notify = true)
            }
            is TerminalCommand.SetViewportTopRow -> {
                viewportTopRow = command.topRow.coerceIn(0, maxTopRow())
                publishFrame(notify = true)
            }
        }
        return TerminalCommandResult.Success
    }

    override fun currentFrame(): TerminalFrame? = if (released) null else frame

    override fun release() {
        if (released) return
        released = true
        listener = null
        stateObserver = null
        frame = null
        expectedChecksum = EMPTY_FRAME_CHECKSUM
        selection = TerminalSelection.EMPTY
    }

    private fun resetSceneState() {
        frameIndex = 0
        viewportTopRow = 0
        publishFrame(notify = true)
    }

    private fun advanceScrollPosition() {
        val maximum = maxTopRow()
        viewportTopRow = if (maximum == 0) 0 else (viewportTopRow + 1) % (maximum + 1)
    }

    private fun maxTopRow(): Int =
        (currentScene.transcriptRows - size.rows).coerceAtLeast(0)

    private fun publishFrame(notify: Boolean) {
        val nextSequence = sequence + 1L
        viewportTopRow = viewportTopRow.coerceIn(0, maxTopRow())
        val activeScene = currentScene
        val context = GpuLabSceneContext(
            sceneId = activeScene.id,
            sceneIndex = sceneIndex,
            size = size,
            frameIndex = frameIndex,
            topRow = viewportTopRow,
            sequence = nextSequence
        )
        val content = activeScene.render(context)
        val nextFrame = content.toTerminalFrame(
            sequence = nextSequence,
            topRow = viewportTopRow,
            size = size,
            transcriptRows = maxOf(size.rows, activeScene.transcriptRows)
        )
        sequence = nextSequence
        expectedChecksum = checksumForFrame(nextFrame)
        selection = content.selection
        frame = nextFrame
        check(expectedChecksum == checksumForFrame(nextFrame)) {
            "Fake frame checksum must use the final immutable frame definition"
        }
        stateObserver?.invoke()
        if (notify) listener?.onFrameInvalidated()
    }

    private companion object {
        val DEFAULT_SIZE = TerminalSize(
            widthPx = 1280,
            heightPx = 768,
            columns = 80,
            rows = 24,
            cellWidthPx = 16,
            cellHeightPx = 32,
            contentTopPx = 0
        )
    }
}
