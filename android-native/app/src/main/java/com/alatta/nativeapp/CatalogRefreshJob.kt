package com.alatta.nativeapp

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import kotlinx.coroutines.*

/** Periodic owner-only catalog work. No credentials or response masks in JobScheduler extras. */
class CatalogRefreshJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var task: Job? = null
    private var active: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val session = getSharedPreferences("alatta_native", Context.MODE_PRIVATE)
        val owner = session.getString("username", "").orEmpty()
        val token = session.getString("token", "").orEmpty()
        if (owner.isBlank() || token.isBlank() || owner != params.extras.getString("owner") ||
            !CatalogLinkStore.automatic(this, owner)) { schedule(this, owner, false); return false }
        active = params
        task = scope.launch {
            try {
                // The existing owner API is checked again before the source is requested.
                withTimeoutOrNull(60_000) { CatalogAutoUpdater.refresh(applicationContext, token, owner) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { /* Keep the cached catalog for the next periodic window. */
            } finally {
                if (active === params) { active = null; jobFinished(params, false) }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        active = null; task?.cancel(); task = null
        return false // The next periodic window handles retries, without an immediate loop.
    }
    override fun onDestroy() { active = null; scope.cancel(); super.onDestroy() }

    companion object {
        internal const val JOB_ID = 48185
        internal fun definition(context: Context, owner: String): JobInfo {
            require(owner.isNotBlank())
            val extras = PersistableBundle().apply { putString("owner", owner) }
            return JobInfo.Builder(JOB_ID, ComponentName(context, CatalogRefreshJob::class.java))
                .setExtras(extras).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60_000L).setPersisted(true).build()
        }
        internal fun schedule(context: Context, owner: String, enabled: Boolean): Boolean = runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            if (!enabled || owner.isBlank()) { scheduler.cancel(JOB_ID); return true }
            val existing = scheduler.getPendingJob(JOB_ID)
            if (existing?.extras?.getString("owner") == owner) return true
            scheduler.schedule(definition(context, owner)) == JobScheduler.RESULT_SUCCESS
        }.getOrDefault(false)
    }
}
