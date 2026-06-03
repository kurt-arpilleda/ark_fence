package com.example.arkfence

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arkfence.ui.theme.ArkfenceTheme
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class GoogleMapView : ComponentActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setContent {
            ArkfenceTheme {
                MapScreen(
                    onBack = { finish() },
                    fusedLocationClient = fusedLocationClient
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun MapScreen(
    onBack: () -> Unit,
    fusedLocationClient: FusedLocationProviderClient
) {
    var currentLocation by remember { mutableStateOf<LatLng?>(null) }
    var geofencePoints by remember { mutableStateOf<List<LatLng>>(emptyList()) }
    var isLoadingLocation by remember { mutableStateOf(true) }
    var isLoadingGeofence by remember { mutableStateOf(true) }
    var isInsideGeofence by remember { mutableStateOf<Boolean?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var initialCameraMoved by remember { mutableStateOf(false) }
    var isSatellite by remember { mutableStateOf(false) }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(14.5995, 120.9842), 15f)
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        RetrofitClient.instance.getGeofenceRadius().enqueue(object : Callback<GeofenceRadiusResponse> {
            override fun onResponse(call: Call<GeofenceRadiusResponse>, response: Response<GeofenceRadiusResponse>) {
                isLoadingGeofence = false
                if (response.isSuccessful && response.body()?.success == true) {
                    response.body()?.polygon?.let { polygon ->
                        geofencePoints = polygon.points
                            .sortedBy { it.pointOrder }
                            .map { LatLng(it.pointLatitude, it.pointLongitude) }
                    }
                }
            }
            override fun onFailure(call: Call<GeofenceRadiusResponse>, t: Throwable) {
                isLoadingGeofence = false
            }
        })
    }

    LaunchedEffect(Unit) {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(3000L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    val newLatLng = LatLng(loc.latitude, loc.longitude)
                    currentLocation = newLatLng
                    isLoadingLocation = false
                    if (geofencePoints.size >= 3) {
                        isInsideGeofence = isPointInPolygon(newLatLng, geofencePoints)
                    }
                }
            }
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, callback, android.os.Looper.getMainLooper())
    }

    LaunchedEffect(currentLocation, geofencePoints) {
        val loc = currentLocation
        if (loc != null && geofencePoints.size >= 3) {
            isInsideGeofence = isPointInPolygon(loc, geofencePoints)
        }
    }

    LaunchedEffect(mapReady, currentLocation) {
        val loc = currentLocation
        if (mapReady && !initialCameraMoved && loc != null) {
            initialCameraMoved = true
            cameraPositionState.animate(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.fromLatLngZoom(loc, 18f)
                )
            )
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF3452B4))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = "ARK FENCE - Map View",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                    )
                    IconButton(onClick = { isSatellite = !isSatellite }) {
                        Icon(
                            imageVector = if (isSatellite) Icons.Default.Map else Icons.Default.Satellite,
                            contentDescription = if (isSatellite) "Switch to Normal" else "Switch to Satellite",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                isInsideGeofence?.let { inside ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (inside) Color(0xFF388E3C) else Color(0xFFC62828))
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (inside) "Inside Geofence" else "Outside Geofence",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(
                    mapType = if (isSatellite) MapType.SATELLITE else MapType.NORMAL,
                    isMyLocationEnabled = false
                ),
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = true,
                    compassEnabled = true,
                    myLocationButtonEnabled = false
                ),
                onMapLoaded = { mapReady = true }
            ) {
                if (geofencePoints.size >= 3) {
                    Polygon(
                        points = geofencePoints,
                        fillColor = Color(0x332053B3),
                        strokeColor = if (isSatellite) Color(0xFFFFFFFF) else Color(0xFF2053B3),
                        strokeWidth = 5f
                    )
                }
                currentLocation?.let { loc ->
                    Marker(
                        state = MarkerState(position = loc),
                        title = "Your Location",
                        snippet = "Lat: %.6f, Lng: %.6f".format(loc.latitude, loc.longitude),
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
                    )
                }
            }

            if (isLoadingLocation && isLoadingGeofence) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x99000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
                        Text(
                            text = "Loading map...",
                            color = Color.White,
                            modifier = Modifier.padding(top = 16.dp),
                            fontSize = 16.sp
                        )
                    }
                }
            }

            currentLocation?.let { loc ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 90.dp)
                        .background(Color(0xDD1A237E), shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Column {
                        Text(
                            text = "Lat:  %.6f".format(loc.latitude),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Lng: %.6f".format(loc.longitude),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            FloatingActionButton(
                onClick = {
                    currentLocation?.let { loc ->
                        if (mapReady) {
                            scope.launch {
                                cameraPositionState.animate(
                                    CameraUpdateFactory.newCameraPosition(
                                        CameraPosition.fromLatLngZoom(loc, 18f)
                                    )
                                )
                            }
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = Color(0xFF3452B4)
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = "Center on my location",
                    tint = Color.White
                )
            }
        }
    }
}

fun isPointInPolygon(point: LatLng, polygon: List<LatLng>): Boolean {
    var intersectCount = 0
    val x = point.longitude
    val y = point.latitude
    val n = polygon.size
    for (i in 0 until n) {
        val p1 = polygon[i]
        val p2 = polygon[(i + 1) % n]
        val x1 = p1.longitude
        val y1 = p1.latitude
        val x2 = p2.longitude
        val y2 = p2.latitude
        if (((y1 <= y && y < y2) || (y2 <= y && y < y1)) &&
            (x < (x2 - x1) * (y - y1) / (y2 - y1) + x1)
        ) {
            intersectCount++
        }
    }
    return (intersectCount % 2) != 0
}