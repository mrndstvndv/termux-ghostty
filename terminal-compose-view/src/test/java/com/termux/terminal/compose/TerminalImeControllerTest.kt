package com.termux.terminal.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalImeControllerTest {
    @Test
    fun visibilityStartsUnknownUntilInsetsAreObserved() {
        val controller = TerminalImeController()

        assertEquals(TerminalImeVisibility.UNKNOWN, controller.visibility)
        controller.updateVisibility(isVisible = false)
        assertEquals(TerminalImeVisibility.HIDDEN, controller.visibility)
        controller.onCanvasDetached()
        assertEquals(TerminalImeVisibility.UNKNOWN, controller.visibility)
    }

    @Test
    fun requestsAreSequencedSoRepeatedActionsRemainDeliverable() {
        val controller = TerminalImeController()

        controller.show()
        val showRequest = controller.pendingRequest
        controller.show()
        val repeatedShowRequest = controller.pendingRequest

        assertEquals(TerminalImeAction.SHOW, showRequest?.action)
        assertEquals(TerminalImeAction.SHOW, repeatedShowRequest?.action)
        check(showRequest != null)
        check(repeatedShowRequest != null)
        assertEquals(showRequest.sequence + 1, repeatedShowRequest.sequence)

        controller.consumeRequest(showRequest.sequence)
        assertEquals(repeatedShowRequest, controller.pendingRequest)
        controller.consumeRequest(repeatedShowRequest.sequence)
        assertEquals(null, controller.pendingRequest)
    }

    @Test
    fun toggleUsesObservedVisibilityAndExplicitVisibility() {
        val controller = TerminalImeController()

        controller.toggle()
        assertEquals(TerminalImeAction.SHOW, controller.pendingRequest?.action)

        controller.updateVisibility(isVisible = true)
        controller.toggle()
        assertEquals(TerminalImeAction.HIDE, controller.pendingRequest?.action)

        controller.toggle(isVisible = false)
        assertEquals(TerminalImeAction.SHOW, controller.pendingRequest?.action)
    }
}
