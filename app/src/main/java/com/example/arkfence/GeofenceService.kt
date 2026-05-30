package com.example.arkfence

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
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
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class GeofenceService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var trackingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var alarmManager: AlarmManager
    private var alarmPendingIntent: PendingIntent? = null
    private var deviceId: String = "unknown-device"
    private lateinit var dbManager: DBManager

    private var lastValidLocation: Location? = null
    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null

    private var geofenceCenter: GeofenceCenter? = null
    private var isOutsideGeofence = false
    private var mediaPlayer: MediaPlayer? = null
    private var hasSentAlertForCurrentExit = false

    companion object {
        private const val TAG = "GeofenceService"
        private const val CHANNEL_ID = "GeofenceServiceChannel"
        private const val NOTIFICATION_ID = 9001
        private const val TRACKING_INTERVAL_MS = 30_000L
        private const val ALARM_INTERVAL_MS = 300_000L
        private const val ALARM_REQUEST_CODE = 7001

        private const val MAX_ACCURACY_METERS = 100f
        private const val MAX_SPEED_MPS = 55.0f
        private const val MAX_LOCATION_AGE_MS = 120_000L
        private const val MIN_DISPLACEMENT_METERS = 5f

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

    @SuppressLint("HardwareIds", "WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        deviceId = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
        } catch (e: Exception) {
            "unknown-device"
        }

        dbManager = DBManager(applicationContext)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GeofenceService::WakeLock"
        ).apply { setReferenceCounted(false) }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        registerAlarmReceiver()
        startLocationUpdates()
        startTrackingLoop()
        scheduleNextAlarm()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("")
        .setContentText("")
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .setOnlyAlertOnce(true)
        .build()

    private fun isLocationFresh(location: Location): Boolean {
        val ageMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            SystemClock.elapsedRealtimeNanos() / 1_000_000L - location.elapsedRealtimeNanos / 1_000_000L
        } else {
            System.currentTimeMillis() - location.time
        }
        return ageMs < MAX_LOCATION_AGE_MS
    }

    private fun isLocationValid(newLocation: Location): Boolean {
        if (!newLocation.hasAccuracy() || newLocation.accuracy > MAX_ACCURACY_METERS) {
//            Log.d(TAG, "Rejected: poor accuracy ${newLocation.accuracy}m")
            return false
        }

        if (!isLocationFresh(newLocation)) {
//            Log.d(TAG, "Rejected: stale location")
            return false
        }

        val previous = lastValidLocation
        if (previous != null) {
            val distance = previous.distanceTo(newLocation)
            val timeDeltaSec = (newLocation.time - previous.time) / 1000f
            if (timeDeltaSec > 0) {
                val speedMps = distance / timeDeltaSec
                if (speedMps > MAX_SPEED_MPS) {
//                    Log.d(TAG, "Rejected: impossible speed ${speedMps}m/s, distance=${distance}m")
                    return false
                }
            }
        }

        return true
    }

    private fun fetchGeofenceCenter(onComplete: (() -> Unit)? = null) {
        RetrofitClient.instance.getGeofenceRadius().enqueue(object : Callback<GeofenceRadiusResponse> {
            override fun onResponse(call: Call<GeofenceRadiusResponse>, response: Response<GeofenceRadiusResponse>) {
                if (response.isSuccessful && response.body()?.success == true) {
                    val newCenter = response.body()?.center
                    if (newCenter != null) {
                        dbManager.insertOrUpdateGeofenceCenter(newCenter)
                        if (newCenter != geofenceCenter) geofenceCenter = newCenter
                    }
                } else {
                    loadGeofenceFromLocalDB()
                }
                onComplete?.invoke()
            }
            override fun onFailure(call: Call<GeofenceRadiusResponse>, t: Throwable) {
                loadGeofenceFromLocalDB()
                onComplete?.invoke()
            }
        })
    }

    private fun loadGeofenceFromLocalDB() {
        val localCenter = dbManager.getGeofenceCenter()
        if (localCenter != null && localCenter != geofenceCenter) {
            geofenceCenter = localCenter
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    @RequiresPermission(Manifest.permission.USE_FULL_SCREEN_INTENT)
    private fun checkGeofence(lat: Double, lng: Double) {
        val center = geofenceCenter ?: return
        val distance = calculateDistance(lat, lng, center.centerLatitude, center.centerLongitude)
        val outside = distance > center.radiusMeters

        if (outside && !isOutsideGeofence) {
            isOutsideGeofence = true
            hasSentAlertForCurrentExit = false
            triggerAlarm()
            sendGeofenceAlert()
        } else if (!outside && isOutsideGeofence) {
            isOutsideGeofence = false
            hasSentAlertForCurrentExit = false
            stopAlarm()
        }
    }

    private fun sendGeofenceAlert() {
        if (hasSentAlertForCurrentExit) return
        hasSentAlertForCurrentExit = true
        RetrofitClient.instance.insertGeofenceAlert(deviceId).enqueue(object : Callback<BasicResponse> {
            override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                if (!response.isSuccessful || response.body()?.success != true) {
                    hasSentAlertForCurrentExit = false
                }
            }
            override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                hasSentAlertForCurrentExit = false
            }
        })
    }

    @RequiresPermission(Manifest.permission.USE_FULL_SCREEN_INTENT)
    private fun triggerAlarm() {
        startAlarmSound()
    }

    private fun stopAlarm() {
        stopAlarmSound()
    }

    private fun startAlarmSound() {
        try {
            stopAlarmSound()
            val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val ringtoneUri = prefs.getString("selected_ringtone_uri", null)

            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                if (!ringtoneUri.isNullOrEmpty()) {
                    setDataSource(applicationContext, Uri.parse(ringtoneUri))
                } else {
                    val defaultUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                    setDataSource(applicationContext, defaultUri)
                }
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
//            Log.e(TAG, "Error starting alarm sound", e)
        }
    }

    private fun stopAlarmSound() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
//            Log.e(TAG, "Error stopping alarm sound", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ALARM_TICK -> {
                if (trackingJob?.isActive != true) startTrackingLoop()
                scheduleNextAlarm()
            }
            else -> {
                if (trackingJob?.isActive != true) startTrackingLoop()
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                30_000L
            )
                .setMinUpdateIntervalMillis(20_000L)
                .setMaxUpdateDelayMillis(60_000L)
                .setMinUpdateDistanceMeters(MIN_DISPLACEMENT_METERS)
                .setWaitForAccurateLocation(false)
                .build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
//            Log.e(TAG, "Failed to start location updates", e)
        }
    }

    private val locationCallback = object : LocationCallback() {
        @RequiresPermission(Manifest.permission.USE_FULL_SCREEN_INTENT)
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return

            if (!isLocationValid(location)) {
//                Log.d(TAG, "Location fix rejected lat=${location.latitude} lng=${location.longitude} acc=${location.accuracy}")
                return
            }

//            Log.d(TAG, "Valid location accepted acc=${location.accuracy}m lat=${location.latitude} lng=${location.longitude}")
            lastValidLocation = location
            lastLatitude = location.latitude
            lastLongitude = location.longitude
            checkGeofence(location.latitude, location.longitude)
        }
    }

    private fun startTrackingLoop() {
        trackingJob?.cancel()
        trackingJob = serviceScope.launch {
            while (isActive) {
                try {
                    if (wakeLock?.isHeld != true) wakeLock?.acquire(60_000L)
                    fetchGeofenceCenterSuspend()
                    performInsert()
                } catch (e: Exception) {
                } finally {
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                }
                delay(TRACKING_INTERVAL_MS)
            }
        }
    }

    private suspend fun fetchGeofenceCenterSuspend() {
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
            fetchGeofenceCenter {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun performInsert() {
        val batteryPercent = getBatteryPercent()
        val isLocationOn = if (isLocationEnabled()) 1 else 0

        val lat = lastLatitude
        val lng = lastLongitude

        if (lat != null && lng != null) {
            checkGeofence(lat, lng)
            sendToServer(lat.toString(), lng.toString(), batteryPercent, isLocationOn)
            return
        }

        try {
            val lastKnown = fusedLocationClient.lastLocation
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                lastKnown.addOnSuccessListener { location ->
                    if (location != null && isLocationValid(location)) {
                        lastValidLocation = location
                        lastLatitude = location.latitude
                        lastLongitude = location.longitude
                        checkGeofence(location.latitude, location.longitude)
                        sendToServer(location.latitude.toString(), location.longitude.toString(), batteryPercent, isLocationOn)
                    } else {
//                        Log.d(TAG, "lastLocation invalid or null, skipping insert")
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                }
                lastKnown.addOnFailureListener {
//                    Log.e(TAG, "lastLocation fetch failed")
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        } catch (e: Exception) {
//            Log.e(TAG, "Last location fetch failed", e)
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
            override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {}
            override fun onFailure(call: Call<BasicResponse>, t: Throwable) {}
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
                if (trackingJob?.isActive != true) startTrackingLoop()
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
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarmPendingIntent!!)
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
        GeofenceMonitoringManager.getInstance(applicationContext).startMonitoring()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        trackingJob?.cancel()
        cancelAlarm()
        stopAlarmSound()
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
//            Log.w(TAG, "Error removing location updates", e)
        }
        try {
            unregisterReceiver(alarmTickReceiver)
        } catch (e: Exception) {
//            Log.w(TAG, "Receiver not registered", e)
        }
        if (wakeLock?.isHeld == true) wakeLock?.release()

        GeofenceMonitoringManager.getInstance(applicationContext).startMonitoring()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}