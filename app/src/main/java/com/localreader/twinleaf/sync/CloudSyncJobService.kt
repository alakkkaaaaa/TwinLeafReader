package com.localreader.twinleaf.sync

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CloudSyncJobService:JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val running=mutableMapOf<Int,Job>()

    override fun onStartJob(params:JobParameters):Boolean {
        val manager=OneDriveSyncManager(applicationContext)
        if(!manager.preferences.enabled || !manager.auth.isSignedIn || manager.preferences.selectedBookIds().isEmpty()) return false
        if(running[params.jobId]?.isActive==true) return true
        val job=scope.launch(start=CoroutineStart.LAZY) {
            var retry=false
            try {
                // The manager owns the process-wide mutex shared with manual sync.
                val report=manager.synchronize()
                retry=report.errors.isNotEmpty()
            } catch(cancelled:CancellationException) {
                throw cancelled
            } catch(error:Exception) {
                manager.preferences.lastStatus="后台同步未完成：${error.message ?: "请稍后重试"}"
                retry=manager.preferences.enabled && manager.auth.isSignedIn
            } finally {
                val current=currentCoroutineContext()
                if(running[params.jobId]===current[Job]) running.remove(params.jobId)
                // onStopJob owns rescheduling after cancellation; do not finish it twice.
                if(current.isActive) jobFinished(params,retry && manager.preferences.enabled && manager.auth.isSignedIn)
            }
        }
        running[params.jobId]=job
        job.start()
        return true
    }

    override fun onStopJob(params:JobParameters):Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        running.clear()
        super.onDestroy()
    }
}
