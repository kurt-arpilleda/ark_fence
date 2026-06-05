package com.example.arkfence

import android.annotation.SuppressLint
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
import java.util.concurrent.TimeUnit

class GeofenceMonitoringManager private constructor(private val context: Context) {

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: GeofenceMonitoringManager? = null

        private const val WORK_NAME = "GeofenceMonitoringWork"

        fun getInstance(context: Context): GeofenceMonitoringManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GeofenceMonitoringManager(context.applicationContext)
                    .also { INSTANCE = it }
            }
        }
    }

    private val workManager = WorkManager.getInstance(context)

    fun startMonitoring() {
        startGeofenceService()
        scheduleWorkManager()
    }

    fun stopMonitoring() {
        workManager.cancelUniqueWork(WORK_NAME)
        GeofenceService.stop(context)
    }

    private fun startGeofenceService() {
        if (GeofenceService.isRunning) return
        val intent = Intent(context, GeofenceService::class.java).apply {
            action = GeofenceService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
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

class GeofenceMonitoringWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            if (!GeofenceService.isRunning) {
                val intent = Intent(applicationContext, GeofenceService::class.java).apply {
                    action = GeofenceService.ACTION_START
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