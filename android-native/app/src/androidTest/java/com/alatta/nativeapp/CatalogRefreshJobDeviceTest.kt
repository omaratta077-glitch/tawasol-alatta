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
        assertTrue(CatalogRefreshJob.schedule(context, "synthetic-owner", true))
        assertTrue(CatalogRefreshJob.schedule(context, "synthetic-owner", true))
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job = requireNotNull(scheduler.getPendingJob(CatalogRefreshJob.JOB_ID))
        assertTrue(job.isPeriodic); assertTrue(job.isPersisted)
        assertEquals(15 * 60_000L, job.intervalMillis)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertEquals(setOf("owner"), job.extras.keySet())
        val info = context.packageManager.getServiceInfo(ComponentName(context, CatalogRefreshJob::class.java), 0)
        assertEquals("android.permission.BIND_JOB_SERVICE", info.permission)
        assertEquals(1, scheduler.allPendingJobs.count { it.id == CatalogRefreshJob.JOB_ID })
        assertTrue(CatalogRefreshJob.schedule(context, "synthetic-owner", false))
        assertNull(scheduler.getPendingJob(CatalogRefreshJob.JOB_ID))
    }
}
