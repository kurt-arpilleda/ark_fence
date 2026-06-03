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
import kotlinx.coroutines.delay
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
                INSTANCE ?: GeofenceMonitoringManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    private val workManager = WorkManager.getInstance(context)

    fun startMonitoring() {
        startGeofenceService()
        scheduleJobScheduler()
        scheduleWorkManager()
        schedulePeriodicRestart()
    }

    fun stopMonitoring() {
        jobScheduler.cancel(JOB_ID)
        workManager.cancelUniqueWork(WORK_NAME)
        workManager.cancelUniqueWork("${WORK_NAME}_Restart")
        GeofenceService.stop(context)
    }

    private fun startGeofenceService() {
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
                    setMinimumLatency(4 * 60 * 1000L)
                    setOverrideDeadline(5 * 60 * 1000L)
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
            .setInitialDelay(1, TimeUnit.MINUTES)
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

    private fun schedulePeriodicRestart() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val restartRequest = PeriodicWorkRequestBuilder<GeofenceServiceRestartWorker>(
            30, TimeUnit.MINUTES,
            10, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setInitialDelay(5, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            "${WORK_NAME}_Restart",
            ExistingPeriodicWorkPolicy.KEEP,
            restartRequest
        )
    }
}

object GeofenceServiceUtils {
    fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val services = activityManager.getRunningServices(Integer.MAX_VALUE)
            services.any { it.service.className == serviceClass.name }
        } catch (e: Exception) {
            false
        }
    }

    fun getServiceInfo(context: Context, serviceClass: Class<*>): ActivityManager.RunningServiceInfo? {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val services = activityManager.getRunningServices(Integer.MAX_VALUE)
            services.firstOrNull { it.service.className == serviceClass.name }
        } catch (e: Exception) {
            null
        }
    }
}

@SuppressLint("SpecifyJobSchedulerIdRange")
class GeofenceMonitoringJobService : JobService() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        scope.launch {
            try {
                if (!GeofenceServiceUtils.isServiceRunning(this@GeofenceMonitoringJobService, GeofenceService::class.java)) {
                    val intent = Intent(this@GeofenceMonitoringJobService, GeofenceService::class.java).apply {
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
                    val componentName = ComponentName(this@GeofenceMonitoringJobService, GeofenceMonitoringJobService::class.java)
                    val jobInfo = JobInfo.Builder(2001, componentName)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setMinimumLatency(4 * 60 * 1000L)
                        .setOverrideDeadline(5 * 60 * 1000L)
                        .setPersisted(true)
                        .build()
                    val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                    jobScheduler.schedule(jobInfo)
                }
            } catch (e: Exception) {
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return false
    }
}

class GeofenceMonitoringWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val serviceInfo = GeofenceServiceUtils.getServiceInfo(applicationContext, GeofenceService::class.java)

            if (serviceInfo == null) {
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
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

class GeofenceServiceRestartWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val restartIntent = Intent(applicationContext, GeofenceService::class.java).apply {
                action = GeofenceService.ACTION_RESTART
                putExtra("periodic_restart", true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(restartIntent)
            } else {
                applicationContext.startService(restartIntent)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}