package com.example.arkfence

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class GeofenceService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var trackingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var alarmManager: AlarmManager
    private var alarmPendingIntent: PendingIntent? = null
    private var deviceId: String = "unknown-device"

    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null

    companion object {
        private const val TAG = "GeofenceService"
        private const val TRACKING_INTERVAL_MS = 60_000L
        private const val ALARM_INTERVAL_MS = 300_000L
        private const val ALARM_REQUEST_CODE = 7001
        const val ACTION_START = "ACTION_START_GEOFENCE"
        const val ACTION_STOP = "ACTION_STOP_GEOFENCE"
        const val ACTION_ALARM_TICK = "ACTION_GEOFENCE_ALARM_TICK"

        fun start(context: Context) {
            val intent = Intent(context, GeofenceService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GeofenceService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }

    @SuppressLint("HardwareIds")
    override fun onCreate() {
        super.onCreate()
        deviceId = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
        } catch (e: Exception) {
            "unknown-device"
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GeofenceService::WakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val notification = android.app.Notification.Builder(this, createSilentChannel())
                .setContentTitle("")
                .setContentText("")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setVisibility(android.app.Notification.VISIBILITY_SECRET)
                .build()
            startForeground(9001, notification)
        } else {
            startForeground(9001, createLegacyNotification())
        }

        registerAlarmReceiver()
        startLocationUpdates()
        startTrackingLoop()
        scheduleNextAlarm()

        Log.d(TAG, "Service created for device: $deviceId")
    }

    private fun createSilentChannel(): String {
        val channelId = "GeofenceServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Location Service",
                android.app.NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        return channelId
    }

    private fun createLegacyNotification(): android.app.Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, createSilentChannel())
                .setOngoing(true)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
                .setOngoing(true)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(android.app.Notification.PRIORITY_MIN)
                .build()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ALARM_TICK -> {
                if (trackingJob?.isActive != true) {
                    startTrackingLoop()
                }
                scheduleNextAlarm()
            }
            else -> {
                if (trackingJob?.isActive != true) {
                    startTrackingLoop()
                }
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                55_000L
            )
                .setMinUpdateIntervalMillis(50_000L)
                .setMaxUpdateDelayMillis(65_000L)
                .build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start location updates", e)
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            lastLatitude = location.latitude
            lastLongitude = location.longitude
        }
    }

    private fun startTrackingLoop() {
        trackingJob?.cancel()
        trackingJob = serviceScope.launch {
            while (isActive) {
                try {
                    performInsert()
                } catch (e: Exception) {
                    Log.e(TAG, "Tracking loop error", e)
                }
                delay(TRACKING_INTERVAL_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun performInsert() {
        val batteryPercent = getBatteryPercent()
        val isLocationOn = if (isLocationEnabled()) 1 else 0
        val lat = lastLatitude
        val lng = lastLongitude

        if (lat != null && lng != null && NetworkUtils.isNetworkAvailable(this)) {
            sendToServer(lat.toString(), lng.toString(), batteryPercent, isLocationOn)
        } else if (lat == null || lng == null) {
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null) {
                        lastLatitude = location.latitude
                        lastLongitude = location.longitude
                        if (NetworkUtils.isNetworkAvailable(this)) {
                            sendToServer(
                                location.latitude.toString(),
                                location.longitude.toString(),
                                batteryPercent,
                                isLocationOn
                            )
                        }
                    } else {
                        if (NetworkUtils.isNetworkAvailable(this)) {
                            sendToServer("0", "0", batteryPercent, isLocationOn)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Last location fetch failed", e)
            }
        }
    }

    private fun sendToServer(lat: String, lng: String, battery: Int, isLocationOn: Int) {
        RetrofitClient.instance.insertPhoneLocation(
            latitude = lat,
            longitude = lng,
            deviceId = deviceId,
            batteryPercent = battery,
            isLocationOn = isLocationOn
        ).enqueue(object : Callback<BasicResponse> {
            override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                if (response.isSuccessful && response.body()?.success == true) {
                    Log.d(TAG, "Location inserted: lat=$lat, lng=$lng, battery=$battery")
                } else {
                    Log.w(TAG, "Server rejected insert: ${response.code()}")
                }
            }

            override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                Log.w(TAG, "Insert failed, will retry next cycle: ${t.message}")
            }
        })
    }

    private fun getBatteryPercent(): Int {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level != -1 && scale != -1) (level * 100 / scale) else 0
    }

    private fun isLocationEnabled(): Boolean {
        return try {
            val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                locationManager.isLocationEnabled
            } else {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag", "ScheduleExactAlarm")
    private fun registerAlarmReceiver() {
        val filter = IntentFilter(ACTION_ALARM_TICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(alarmTickReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(alarmTickReceiver, filter)
        }
    }

    private val alarmTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_ALARM_TICK) {
                if (trackingJob?.isActive != true) {
                    startTrackingLoop()
                }
                scheduleNextAlarm()
            }
        }
    }

    private fun scheduleNextAlarm() {
        val intent = Intent(ACTION_ALARM_TICK)
        alarmPendingIntent = PendingIntent.getBroadcast(
            this,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAt = System.currentTimeMillis() + ALARM_INTERVAL_MS

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    alarmPendingIntent!!
                )
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, alarmPendingIntent!!)
            }
        } catch (e: Exception) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, alarmPendingIntent!!)
        }
    }

    private fun cancelAlarm() {
        alarmPendingIntent?.let { alarmManager.cancel(it) }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(applicationContext, GeofenceService::class.java).apply {
            action = ACTION_START
        }
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            8001,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
        alarm.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 2000, pendingIntent)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        trackingJob?.cancel()
        cancelAlarm()
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing location updates", e)
        }
        try {
            unregisterReceiver(alarmTickReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver not registered", e)
        }
        if (wakeLock?.isHeld == true) wakeLock?.release()

        val restartIntent = Intent(applicationContext, GeofenceService::class.java).apply {
            action = ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(restartIntent)
        } else {
            applicationContext.startService(restartIntent)
        }

        super.onDestroy()
        Log.d(TAG, "Service destroyed, restarting...")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}