package com.example.arkfence

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class GeofenceMonitoringManager private constructor(private val context: Context) {

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: GeofenceMonitoringManager? = null

        private const val JOB_ID = 2001
        private const val WORK_NAME = "GeofenceMonitoringWork"

        fun getInstance(context: Context): GeofenceMonitoringManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GeofenceMonitoringManager(context.applicationContext)
                    .also { INSTANCE = it }
            }
        }
    }

    private val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    private val workManager = WorkManager.getInstance(context)

    fun startMonitoring() {
        startGeofenceService()
        scheduleJobScheduler()
        scheduleWorkManager()
    }

    fun stopMonitoring() {
        jobScheduler.cancel(JOB_ID)
        workManager.cancelUniqueWork(WORK_NAME)
        GeofenceService.stop(context)
    }

    private fun startGeofenceService() {
        if (GeofenceServiceUtils.isServiceRunning(context, GeofenceService::class.java)) return
        val intent = Intent(context, GeofenceService::class.java).apply {
            action = GeofenceService.ACTION_START
            putExtra("restart_from_manager", true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun scheduleJobScheduler() {
        val componentName = ComponentName(context, GeofenceMonitoringJobService::class.java)
        val jobInfo = JobInfo.Builder(JOB_ID, componentName)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setMinimumLatency(14 * 60 * 1000L)
                    setOverrideDeadline(16 * 60 * 1000L)
                } else {
                    setPeriodic(15 * 60 * 1000L)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setRequiresBatteryNotLow(false)
                    setRequiresStorageNotLow(false)
                }
            }
            .build()
        jobScheduler.schedule(jobInfo)
    }

    private fun scheduleWorkManager() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false)
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)
            .setRequiresStorageNotLow(false)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<GeofenceMonitoringWorker>(
            15, TimeUnit.MINUTES,
            5, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setInitialDelay(2, TimeUnit.MINUTES)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}

object GeofenceServiceUtils {
    fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getRunningServices(Integer.MAX_VALUE).any {
                it.service.className == serviceClass.name
            }
        } catch (_: Exception) { false }
    }

    fun getServiceInfo(
        context: Context,
        serviceClass: Class<*>
    ): ActivityManager.RunningServiceInfo? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getRunningServices(Integer.MAX_VALUE).firstOrNull {
                it.service.className == serviceClass.name
            }
        } catch (_: Exception) { null }
    }
}

@SuppressLint("SpecifyJobSchedulerIdRange")
class GeofenceMonitoringJobService : JobService() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        scope.launch {
            try {
                if (!GeofenceServiceUtils.isServiceRunning(
                        this@GeofenceMonitoringJobService, GeofenceService::class.java
                    )
                ) {
                    val intent = Intent(
                        this@GeofenceMonitoringJobService, GeofenceService::class.java
                    ).apply {
                        action = GeofenceService.ACTION_START
                        putExtra("restart_from_job", true)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val componentName = ComponentName(
                        this@GeofenceMonitoringJobService,
                        GeofenceMonitoringJobService::class.java
                    )
                    val jobInfo = JobInfo.Builder(2001, componentName)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setMinimumLatency(14 * 60 * 1000L)
                        .setOverrideDeadline(16 * 60 * 1000L)
                        .setPersisted(true)
                        .build()
                    (getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).schedule(jobInfo)
                }
            } catch (_: Exception) {
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}

class GeofenceMonitoringWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            if (GeofenceServiceUtils.getServiceInfo(
                    applicationContext, GeofenceService::class.java
                ) == null
            ) {
                val intent = Intent(applicationContext, GeofenceService::class.java).apply {
                    action = GeofenceService.ACTION_START
                    putExtra("restart_from_worker", true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent)
                } else {
                    applicationContext.startService(intent)
                }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}