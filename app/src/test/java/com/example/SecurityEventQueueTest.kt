package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SecurityEventStatus
import com.example.data.SecurityPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecurityEventQueueTest {
    private lateinit var prefs: SecurityPrefs

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = SecurityPrefs.getInstance(context)
        prefs.resetFailedUnlockAttempts()
    }

    @Test
    fun `event is pending then can be claimed only once`() {
        val event = prefs.enqueueSecurityEvent()

        assertTrue(prefs.getPendingSecurityEvents().any { it.id == event.id })
        assertTrue(prefs.claimSecurityEvent(event.id))
        assertFalse(prefs.claimSecurityEvent(event.id))
    }

    @Test
    fun `completed event is not pending`() {
        val event = prefs.enqueueSecurityEvent()
        assertTrue(prefs.claimSecurityEvent(event.id))

        prefs.completeSecurityEvent(event.id, SecurityEventStatus.SENT)

        assertFalse(prefs.getPendingSecurityEvents().any { it.id == event.id })
    }

    @Test
    fun `stale in progress event is requeued with bounded recovery`() {
        val event = prefs.enqueueSecurityEvent()
        assertTrue(prefs.claimSecurityEvent(event.id))

        val recovered = prefs.recoverStaleSecurityEvents(
            now = event.timestamp + 2 * 60 * 1000L + 1L
        )

        assertEquals(1, recovered)
        assertTrue(prefs.getPendingSecurityEvents().any { it.id == event.id })
    }

    @Test
    fun `successful unlock cancels pending event and it cannot be completed later`() {
        val event = prefs.enqueueSecurityEvent()
        assertTrue(prefs.claimSecurityEvent(event.id))

        prefs.resetFailedUnlockAttempts()
        prefs.completeSecurityEvent(event.id, SecurityEventStatus.SENT)

        assertFalse(prefs.getPendingSecurityEvents().any { it.id == event.id })
    }

    @Test
    fun `failed attempt counter resets after successful unlock`() {
        assertEquals(1, prefs.registerFailedUnlockAttempt())
        assertEquals(2, prefs.registerFailedUnlockAttempt())

        prefs.resetFailedUnlockAttempts()

        assertEquals(1, prefs.registerFailedUnlockAttempt())
    }
}
