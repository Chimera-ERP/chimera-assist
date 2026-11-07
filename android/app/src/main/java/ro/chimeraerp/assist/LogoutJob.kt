package ro.chimeraerp.assist

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** Network-constrained retry survives offline logout and reboot without enabling the helper. */
class LogoutJob: JobService() {
    private var worker:Thread?=null
    override fun onStartJob(params:JobParameters):Boolean {
        worker=Thread {
            val complete=try {SessionLogout(this).drain()} catch(_:Exception) {false}
            jobFinished(params,!complete)
        }.apply {start()}
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {worker?.interrupt();return true}
    companion object {
        internal const val ID=7001
        internal fun request(context:Context) {
            context.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(ID,ComponentName(context,LogoutJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                    .setBackoffCriteria(30_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        }
        fun schedule(context:Context) {
            if(SessionStore(context).revocations().isNotEmpty())request(context)
        }
    }
}
