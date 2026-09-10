package com.example

import com.example.data.SecurityEventStateMachine
import com.example.data.SecurityEventStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityEventStateMachineTest {
    @Test
    fun `capture and send flow is allowed`() {
        assertTrue(SecurityEventStateMachine.canTransition(SecurityEventStatus.PENDING, SecurityEventStatus.IN_PROGRESS))
        assertTrue(SecurityEventStateMachine.canTransition(SecurityEventStatus.IN_PROGRESS, SecurityEventStatus.CAPTURED))
        assertTrue(SecurityEventStateMachine.canTransition(SecurityEventStatus.CAPTURED, SecurityEventStatus.SEND_PENDING))
        assertTrue(SecurityEventStateMachine.canTransition(SecurityEventStatus.SEND_PENDING, SecurityEventStatus.SENT))
    }

    @Test
    fun `retry flow is bounded by explicit transitions`() {
        assertTrue(SecurityEventStateMachine.canTransition(SecurityEventStatus.SEND_PENDING, SecurityEventStatus.FAILED_RETRYABLE))
        assertTrue(SecurityEventStateMachine.canTransition(SecurityEventStatus.FAILED_RETRYABLE, SecurityEventStatus.SEND_PENDING))
        assertTrue(SecurityEventStateMachine.canTransition(SecurityEventStatus.FAILED_RETRYABLE, SecurityEventStatus.FAILED_FINAL))
    }

    @Test
    fun `terminal events cannot be resurrected`() {
        for (terminal in listOf(
            SecurityEventStatus.SENT,
            SecurityEventStatus.FAILED,
            SecurityEventStatus.FAILED_FINAL,
            SecurityEventStatus.CANCELLED
        )) {
            assertFalse(SecurityEventStateMachine.canTransition(terminal, SecurityEventStatus.PENDING))
            assertFalse(SecurityEventStateMachine.canTransition(terminal, SecurityEventStatus.SEND_PENDING))
        }
    }

    @Test
    fun `background restriction defers event and recovery can resume it`() {
        assertTrue(SecurityEventStateMachine.canTransition(SecurityEventStatus.PENDING, SecurityEventStatus.DEFERRED))
        assertTrue(SecurityEventStateMachine.canTransition(SecurityEventStatus.DEFERRED, SecurityEventStatus.IN_PROGRESS))
    }
}
