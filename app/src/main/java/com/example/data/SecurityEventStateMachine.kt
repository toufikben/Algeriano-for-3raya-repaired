package com.example.data

/**
 * Explicit state-transition policy for a security event.
 * Terminal states cannot be changed, which prevents retries from resurrecting
 * already completed or permanently failed events.
 */
object SecurityEventStateMachine {
    fun canTransition(from: SecurityEventStatus, to: SecurityEventStatus): Boolean {
        if (from == to) return true
        return when (from) {
            SecurityEventStatus.PENDING -> to in setOf(
                SecurityEventStatus.IN_PROGRESS,
                SecurityEventStatus.FAILED,
                SecurityEventStatus.CANCELLED
            )
            SecurityEventStatus.IN_PROGRESS -> to in setOf(
                SecurityEventStatus.PENDING,
                SecurityEventStatus.CAPTURED,
                SecurityEventStatus.SEND_PENDING,
                SecurityEventStatus.FAILED_RETRYABLE,
                SecurityEventStatus.FAILED_FINAL,
                SecurityEventStatus.CANCELLED
            )
            SecurityEventStatus.SEND_PENDING -> to in setOf(
                SecurityEventStatus.PENDING,
                SecurityEventStatus.SENT,
                SecurityEventStatus.FAILED_RETRYABLE,
                SecurityEventStatus.FAILED_FINAL,
                SecurityEventStatus.CANCELLED
            )
            SecurityEventStatus.FAILED_RETRYABLE -> to in setOf(
                SecurityEventStatus.SEND_PENDING,
                SecurityEventStatus.FAILED_FINAL,
                SecurityEventStatus.CANCELLED
            )
            SecurityEventStatus.CAPTURED -> to == SecurityEventStatus.SEND_PENDING
            SecurityEventStatus.SENT,
            SecurityEventStatus.FAILED,
            SecurityEventStatus.FAILED_FINAL,
            SecurityEventStatus.CANCELLED -> false
        }
    }
}
