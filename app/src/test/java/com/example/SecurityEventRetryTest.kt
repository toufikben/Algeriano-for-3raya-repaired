package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SecurityEventStatus
import com.example.data.SecurityEventComponentState
import com.example.data.IntruderLog
import com.example.data.SecurityPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecurityEventRetryTest {
    private lateinit var prefs: SecurityPrefs

    @Before
    fun setUp() {
        prefs = SecurityPrefs.getInstance(ApplicationProvider.getApplicationContext<Context>())
        prefs.resetFailedUnlockAttempts()
        prefs.clearLogs()
    }

    @Test
    fun `capture result is persisted before sending`() {
        val event = prefs.enqueueSecurityEvent()
        assertTrue(prefs.claimSecurityEvent(event.id))

        prefs.recordCaptureResult(
            id = event.id,
            photoPath = "/tmp/capture-${event.id}.jpg",
            latitude = 36.7,
            longitude = 3.0,
            locationTimestamp = 1234L
        )

        val saved = prefs.getSecurityEvent(event.id)
        assertEquals(SecurityEventStatus.CAPTURED, saved?.status)
        assertEquals("/tmp/capture-${event.id}.jpg", saved?.photoPath)
        assertEquals(36.7, saved?.latitude)
        assertEquals(3.0, saved?.longitude)
        assertEquals(SecurityEventComponentState.SUCCEEDED, saved?.photoState)
        assertEquals(SecurityEventComponentState.SUCCEEDED, saved?.locationState)
        assertTrue(prefs.moveCapturedEventToSendPending(event.id))
        assertEquals(SecurityEventStatus.SEND_PENDING, prefs.getSecurityEvent(event.id)?.status)
    }

    @Test
    fun `send retry increments independently and stops after three attempts`() {
        val event = prefs.enqueueSecurityEvent()
        assertTrue(prefs.claimSecurityEvent(event.id))
        prefs.recordCaptureResult(event.id, null, null, null, null)
        prefs.moveCapturedEventToSendPending(event.id)

        repeat(2) {
            assertTrue(prefs.recordSendRetry(event.id))
            assertTrue(prefs.markSendPendingForRetry(event.id))
        }
        assertTrue(!prefs.recordSendRetry(event.id))
        assertEquals(SecurityEventStatus.FAILED_FINAL, prefs.getSecurityEvent(event.id)?.status)
        assertTrue(prefs.getPendingSecurityEvents().none { it.id == event.id })
    }

    @Test
    fun `retry updates one intruder log for the same event`() {
        val first = IntruderLog(
            id = "first-log",
            eventId = "event-1",
            photoCaptured = true,
            locationCaptured = false,
            timestamp = 100L,
            photoPath = "/tmp/event-1.jpg",
            latitude = 36.7,
            longitude = 3.0,
            address = "36.7, 3.0",
            emailSent = false,
            statusMessage = "retry pending"
        )
        val retry = first.copy(
            id = "second-log-must-not-replace-id",
            emailSent = true,
            statusMessage = "sent"
        )

        prefs.addLog(first)
        prefs.addLog(retry)

        val logs = prefs.getLogs()
        assertEquals(1, logs.size)
        assertEquals("first-log", logs.single().id)
        assertEquals("event-1", logs.single().eventId)
        assertTrue(logs.single().photoCaptured)
        assertTrue(!logs.single().locationCaptured)
        assertTrue(logs.single().emailSent)
        assertEquals(1, prefs.totalAttempts)
    }
}
