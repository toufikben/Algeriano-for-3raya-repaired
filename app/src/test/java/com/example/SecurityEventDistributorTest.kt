package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.ListenableWorker
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.SecurityPrefs
import com.example.worker.SecurityEventDistributor
import com.example.worker.SecurityEventWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecurityEventDistributorTest {
    private lateinit var context: Context
    private lateinit var prefs: SecurityPrefs
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get()
        prefs = SecurityPrefs.getInstance(context)
        prefs.resetFailedUnlockAttempts()
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork().result.get()
    }

    @Test
    fun `enqueue creates unique work for a pending event`() {
        val event = prefs.enqueueSecurityEvent()

        SecurityEventDistributor.enqueue(context, event.id)

        val work = workManager
            .getWorkInfosForUniqueWork(SecurityEventDistributor.workName(event.id))
            .get()
        assertEquals(1, work.size)
        assertEquals(WorkInfo.State.ENQUEUED, work.single().state)
    }

    @Test
    fun `duplicate enqueue keeps one work item`() {
        val event = prefs.enqueueSecurityEvent()

        SecurityEventDistributor.enqueue(context, event.id)
        SecurityEventDistributor.enqueue(context, event.id)

        val work = workManager
            .getWorkInfosForUniqueWork(SecurityEventDistributor.workName(event.id))
            .get()
        assertEquals(1, work.size)
    }

    @Test
    fun `enqueue ignores completed event`() {
        val event = prefs.enqueueSecurityEvent()
        assertTrue(prefs.claimSecurityEvent(event.id))
        prefs.completeSecurityEvent(event.id, com.example.data.SecurityEventStatus.SENT)

        SecurityEventDistributor.enqueue(context, event.id)

        val work = workManager
            .getWorkInfosForUniqueWork(SecurityEventDistributor.workName(event.id))
            .get()
        assertTrue(work.isEmpty())
    }

    @Test
    fun `worker rejects missing event id`() = runBlocking {
        val worker = androidx.work.testing.TestListenableWorkerBuilder
            .from(context, SecurityEventWorker::class.java)
            .build()

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
    }

    @Test
    fun `pending events are all distributed`() {
        val first = prefs.enqueueSecurityEvent()
        val second = prefs.enqueueSecurityEvent()

        SecurityEventDistributor.enqueuePending(context)

        val firstWork = workManager
            .getWorkInfosForUniqueWork(SecurityEventDistributor.workName(first.id))
            .get()
        val secondWork = workManager
            .getWorkInfosForUniqueWork(SecurityEventDistributor.workName(second.id))
            .get()

        assertFalse(firstWork.isEmpty())
        assertFalse(secondWork.isEmpty())
        assertNotNull(firstWork.single())
        assertNotNull(secondWork.single())
    }
}
