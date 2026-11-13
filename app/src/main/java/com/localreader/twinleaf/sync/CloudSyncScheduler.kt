package com.localreader.twinleaf.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

/** Best-effort background sync: Android decides the run time, never a real-time promise. */
object CloudSyncScheduler {
    private const val PERIODIC_JOB=0x544C01
    private const val SOON_JOB=0x544C02
    private const val INTERVAL_MS=15L*60*1000

    fun schedule(context:Context) {
        val app=context.applicationContext
        val scheduler=app.getSystemService(JobScheduler::class.java) ?: return
        val preferences=OneDrivePreferences(app)
        if(!preferences.enabled || !OneDriveAuth(app).isSignedIn || preferences.selectedBookIds().isEmpty()) { cancel(app);return }
        val network=networkType(preferences)
        val pending=scheduler.getPendingJob(PERIODIC_JOB)
        if(pending==null || pending.networkType!=network || !pending.isPeriodic || !pending.isPersisted) {
            scheduler.schedule(base(app,PERIODIC_JOB,network).setPeriodic(INTERVAL_MS).setPersisted(true).build())
        }
        // Updating the connection preference must also update a queued one-shot request.
        val soon=scheduler.getPendingJob(SOON_JOB)
        if(soon!=null && soon.networkType!=network) {
            scheduler.cancel(SOON_JOB)
            requestSoon(app)
        }
    }

    fun requestSoon(context:Context) {
        val app=context.applicationContext
        val scheduler=app.getSystemService(JobScheduler::class.java) ?: return
        val preferences=OneDrivePreferences(app)
        if(!preferences.enabled || !OneDriveAuth(app).isSignedIn || preferences.selectedBookIds().isEmpty()) { cancel(app);return }
        // Coalesce repeated edits without restarting a running job or continually delaying it.
        if(scheduler.getPendingJob(SOON_JOB)!=null) return
        scheduler.schedule(base(app,SOON_JOB,networkType(preferences))
            .setMinimumLatency(5_000L).setPersisted(true).build())
    }

    fun cancel(context:Context) {
        context.applicationContext.getSystemService(JobScheduler::class.java)?.let {
            it.cancel(PERIODIC_JOB)
            it.cancel(SOON_JOB)
        }
    }

    private fun networkType(preferences:OneDrivePreferences):Int =
        if(preferences.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY

    private fun base(context:Context,id:Int,network:Int):JobInfo.Builder =
        JobInfo.Builder(id,ComponentName(context,CloudSyncJobService::class.java))
            .setRequiredNetworkType(network)
            .setBackoffCriteria(30_000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL)
}
