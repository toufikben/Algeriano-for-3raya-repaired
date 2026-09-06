package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SecurityEventStatus
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
        assertEquals(SecurityEventStatus.SEND_PENDING, saved?.status)
        assertEquals("/tmp/capture-${event.id}.jpg", saved?.photoPath)
        assertEquals(36.7, saved?.latitude)
        assertEquals(3.0, saved?.longitude)
    }

    @Test
    fun `send retry increments independently and stops after three attempts`() {
        val event = prefs.enqueueSecurityEvent()
        assertTrue(prefs.claimSecurityEvent(event.id))
        prefs.recordCaptureResult(event.id, null, null, null, null)

        repeat(2) {
            assertTrue(prefs.recordSendRetry(event.id))
            assertTrue(prefs.markSendPendingForRetry(event.id))
        }
        assertTrue(!prefs.recordSendRetry(event.id))
        assertEquals(SecurityEventStatus.FAILED_FINAL, prefs.getSecurityEvent(event.id)?.status)
        assertTrue(prefs.getPendingSecurityEvents().none { it.id == event.id })
    }
}
