package com.alatta.nativeapp

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CatalogRefreshJobDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @After fun cleanup() { CatalogRefreshJob.schedule(context, "synthetic-owner", false) }
    @Test fun backgroundScheduleIsPeriodicNetworkBoundPersistentAndContainsNoSessionCredentials() {
        // Validate the same definition passed to the OS, without enqueueing real network work.
        // Android may start a periodic job immediately and correctly cancel this fake session.
        val job = CatalogRefreshJob.definition(context, "synthetic-owner")
        val scheduler = context.getSystemService(JobScheduler::class.java)
        assertEquals(CatalogRefreshJob.JOB_ID, job.id)
        assertTrue(job.isPeriodic); assertTrue(job.isPersisted)
        assertEquals(15 * 60_000L, job.intervalMillis)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertEquals(setOf("owner"), job.extras.keySet())
        val info = context.packageManager.getServiceInfo(ComponentName(context, CatalogRefreshJob::class.java), 0)
        assertEquals("android.permission.BIND_JOB_SERVICE", info.permission)
        assertTrue(CatalogRefreshJob.schedule(context, "synthetic-owner", false))
        assertNull(scheduler.getPendingJob(CatalogRefreshJob.JOB_ID))
    }
}
