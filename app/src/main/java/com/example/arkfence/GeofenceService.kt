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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import kotlin.coroutines.resume

class GeofenceService : Service() {

    private val supervisorJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + supervisorJob)
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

    private var geofencePolygon: GeofencePolygon? = null
    private var isOutsideGeofence = false
    private var mediaPlayer: MediaPlayer? = null
    private var hasSentAlertForCurrentExit = false
    private var isServiceActive = true
    private var isHighAccuracyMode = false
    private var lastPolygonFetchMs: Long = 0L

    private var cachedBatteryPercent: Int = 0
    private var lastBatteryCheckMs: Long = 0L

    companion object {
        private const val CHANNEL_ID = "GeofenceServiceChannel"
        private const val NOTIFICATION_ID = 9001
        private const val NORMAL_INTERVAL_MS = 60_000L
        private const val HIACC_INTERVAL_MS = 15_000L
        private const val MIN_UPDATE_INTERVAL_MS = 10_000L
        private const val MIN_DISPLACEMENT_METERS = 3f
        private const val POLYGON_REFRESH_INTERVAL_MS = 3_600_000L
        private const val ALARM_INTERVAL_MS = 300_000L
        private const val ALARM_REQUEST_CODE = 7001
        private const val MAX_ACCURACY_METERS = 50f
        private const val MAX_SPEED_MPS = 55.0f
        private const val MAX_LOCATION_AGE_MS = 120_000L
        private const val BATTERY_CACHE_INTERVAL_MS = 30_000L
        private const val WAKELOCK_TIMEOUT_MS = 65_000L

        const val ACTION_START = "ACTION_START_GEOFENCE"
        const val ACTION_STOP = "ACTION_STOP_GEOFENCE"
        const val ACTION_RESTART = "ACTION_RESTART_GEOFENCE"
        const val ACTION_ALARM_TICK = "ACTION_GEOFENCE_ALARM_TICK"

        @Volatile
        var isRunning = false
            private set

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
            context.stopService(Intent(context, GeofenceService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    @SuppressLint("HardwareIds")
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        isServiceActive = true

        deviceId = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
        } catch (_: Exception) { "unknown-device" }

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
        loadGeofenceFromLocalDB()
        registerAlarmReceiver()
        startLocationUpdates()
        startTrackingLoop()
        scheduleNextAlarm()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                if (trackingJob?.isActive != true) startTrackingLoop()
                scheduleNextAlarm()
            }
            ACTION_ALARM_TICK -> {
                if (trackingJob?.isActive != true) startTrackingLoop()
                scheduleNextAlarm()
            }
            else -> {
                if (trackingJob?.isActive != true) startTrackingLoop()
                scheduleNextAlarm()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        GeofenceMonitoringManager.getInstance(applicationContext).startMonitoring()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        isServiceActive = false
        trackingJob?.cancel()
        trackingJob = null
        serviceScope.cancel()
        cancelAlarm()
        stopAlarmSound()
        try { fusedLocationClient.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
        try { unregisterReceiver(alarmTickReceiver) } catch (_: Exception) {}
        releaseWakeLock()
        wakeLock = null
        GeofenceMonitoringManager.getInstance(applicationContext).startMonitoring()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Location Service", NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (_: Exception) {}

        val (priority, interval) = if (isHighAccuracyMode) {
            Priority.PRIORITY_HIGH_ACCURACY to HIACC_INTERVAL_MS
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY to NORMAL_INTERVAL_MS
        }

        val locationRequest = LocationRequest.Builder(priority, interval)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
            .setMaxUpdateDelayMillis(interval + 30_000L)
            .setMinUpdateDistanceMeters(MIN_DISPLACEMENT_METERS)
            .setWaitForAccurateLocation(isHighAccuracyMode)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private val locationCallback = object : LocationCallback() {
        @RequiresPermission(Manifest.permission.USE_FULL_SCREEN_INTENT)
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            if (!isLocationValid(location)) return
            lastValidLocation = location
            lastLatitude = location.latitude
            lastLongitude = location.longitude
            checkGeofence(location.latitude, location.longitude)
        }
    }

    private fun startTrackingLoop() {
        trackingJob?.cancel()
        val interval = if (isHighAccuracyMode) HIACC_INTERVAL_MS else NORMAL_INTERVAL_MS

        trackingJob = serviceScope.launch {
            try {
                maybeRefreshPolygon()
                while (isActive && isServiceActive) {
                    try {
                        performInsert()
                    } catch (_: Exception) {}
                    delay(interval)
                }
            } finally {
                withContext(NonCancellable) { }
            }
        }
    }

    private fun stopTrackingLoop() {
        trackingJob?.cancel()
        trackingJob = null
    }

    private suspend fun maybeRefreshPolygon() {
        val now = System.currentTimeMillis()
        if (geofencePolygon != null && (now - lastPolygonFetchMs) < POLYGON_REFRESH_INTERVAL_MS) return
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            fetchGeofencePolygon { if (cont.isActive) cont.resume(Unit) }
        }
    }

    private fun fetchGeofencePolygon(onComplete: (() -> Unit)? = null) {
        RetrofitClient.instance.getGeofenceRadius()
            .enqueue(object : Callback<GeofenceRadiusResponse> {
                override fun onResponse(
                    call: Call<GeofenceRadiusResponse>,
                    response: Response<GeofenceRadiusResponse>
                ) {
                    if (response.isSuccessful && response.body()?.success == true) {
                        val newPolygon = response.body()?.polygon
                        if (newPolygon != null) {
                            dbManager.insertOrUpdateGeofencePolygon(newPolygon)
                            geofencePolygon = newPolygon
                            lastPolygonFetchMs = System.currentTimeMillis()
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
        dbManager.getGeofencePolygon()?.let { geofencePolygon = it }
    }

    @RequiresPermission(Manifest.permission.USE_FULL_SCREEN_INTENT)
    private fun checkGeofence(lat: Double, lng: Double) {
        val polygon = geofencePolygon ?: return
        val outside = !isPointInPolygon(lat, lng, polygon)

        if (outside && !isOutsideGeofence) {
            isOutsideGeofence = true
            hasSentAlertForCurrentExit = false
            switchToHighAccuracyMode()
            triggerAlarm()
            sendGeofenceAlert()
        } else if (!outside && isOutsideGeofence) {
            isOutsideGeofence = false
            hasSentAlertForCurrentExit = false
            switchToNormalMode()
            stopAlarm()
        }
    }

    private fun switchToHighAccuracyMode() {
        if (isHighAccuracyMode) return
        isHighAccuracyMode = true
        startLocationUpdates()
        stopTrackingLoop()
        startTrackingLoop()
        wakeLock?.let { if (!it.isHeld) it.acquire(5 * 60 * 1000L) }
    }

    private fun switchToNormalMode() {
        if (!isHighAccuracyMode) return
        isHighAccuracyMode = false
        startLocationUpdates()
        stopTrackingLoop()
        startTrackingLoop()
        releaseWakeLock()
    }

    @SuppressLint("MissingPermission")
    private fun performInsert() {
        val batteryPercent = getCachedBatteryPercent()
        val isLocationOn = if (isLocationEnabled()) 1 else 0
        val lat = lastLatitude
        val lng = lastLongitude

        if (lat != null && lng != null) {
            sendToServer(lat.toString(), lng.toString(), batteryPercent, isLocationOn)
            return
        }

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null && isLocationValid(location)) {
                    lastValidLocation = location
                    lastLatitude = location.latitude
                    lastLongitude = location.longitude
                    checkGeofence(location.latitude, location.longitude)
                    sendToServer(
                        location.latitude.toString(), location.longitude.toString(),
                        batteryPercent, isLocationOn
                    )
                }
            }
        } catch (_: Exception) {}
    }

    private fun sendGeofenceAlert() {
        if (hasSentAlertForCurrentExit) return
        hasSentAlertForCurrentExit = true
        RetrofitClient.instance.insertGeofenceAlert(deviceId)
            .enqueue(object : Callback<BasicResponse> {
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

    @RequiresPermission(Manifest.permission.USE_FULL_SCREEN_INTENT)
    private fun triggerAlarm() { startAlarmSound() }
    private fun stopAlarm() { stopAlarmSound() }

    private fun startAlarmSound() {
        try {
            stopAlarmSound()
            val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val ringtoneUri = prefs.getString("selected_ringtone_uri", null)
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )
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
                    setDataSource(
                        applicationContext,
                        android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                    )
                }
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {}
    }

    private fun stopAlarmSound() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
            mediaPlayer = null
        } catch (_: Exception) {}
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
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

    @SuppressLint("ScheduleExactAlarm")
    private fun scheduleNextAlarm() {
        val pendingIntent = PendingIntent.getBroadcast(
            this, ALARM_REQUEST_CODE,
            Intent(ACTION_ALARM_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ).also { alarmPendingIntent = it }

        val triggerAt = System.currentTimeMillis() + ALARM_INTERVAL_MS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (_: Exception) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun cancelAlarm() {
        alarmPendingIntent?.let { alarmManager.cancel(it); alarmPendingIntent = null }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
    }

    private fun getCachedBatteryPercent(): Int {
        val now = System.currentTimeMillis()
        if (now - lastBatteryCheckMs > BATTERY_CACHE_INTERVAL_MS) {
            val bIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            cachedBatteryPercent = if (level != -1 && scale != -1) (level * 100 / scale) else 0
            lastBatteryCheckMs = now
        }
        return cachedBatteryPercent
    }

    private fun isLocationFresh(location: Location): Boolean {
        val ageMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            SystemClock.elapsedRealtimeNanos() / 1_000_000L -
                    location.elapsedRealtimeNanos / 1_000_000L
        } else {
            System.currentTimeMillis() - location.time
        }
        return ageMs < MAX_LOCATION_AGE_MS
    }

    private fun isLocationValid(newLocation: Location): Boolean {
        if (!newLocation.hasAccuracy() || newLocation.accuracy > MAX_ACCURACY_METERS) return false
        if (!isLocationFresh(newLocation)) return false
        val previous = lastValidLocation
        if (previous != null) {
            val distance = previous.distanceTo(newLocation)
            val timeDeltaSec = (newLocation.time - previous.time) / 1000f
            if (timeDeltaSec > 0 && (distance / timeDeltaSec) > MAX_SPEED_MPS) return false
        }
        return true
    }

    private fun isPointInPolygon(lat: Double, lng: Double, polygon: GeofencePolygon): Boolean {
        val points = polygon.points
        if (points.size < 3) return false
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val xi = points[i].pointLongitude; val yi = points[i].pointLatitude
            val xj = points[j].pointLongitude; val yj = points[j].pointLatitude
            if (((yi > lat) != (yj > lat)) && (lng < (xj - xi) * (lat - yi) / (yj - yi) + xi)) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun isLocationEnabled(): Boolean {
        return try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
            else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { false }
    }
}