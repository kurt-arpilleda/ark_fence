package com.example.arkfence

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val foregroundPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val requestForegroundPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.any { it.value }
        if (granted) {
            requestBackgroundIfNeededAndStart()
        } else {
            startDashboardOnly()
        }
    }

    private val requestBackgroundPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        startServiceAndDashboard()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (hasForegroundLocation()) {
            requestBackgroundIfNeededAndStart()
        } else {
            requestForegroundPermissions.launch(foregroundPermissions)
        }
    }

    private fun requestBackgroundIfNeededAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestBackgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            startServiceAndDashboard()
        }
    }

    private fun startServiceAndDashboard() {
        val serviceIntent = Intent(this, GeofenceService::class.java).apply {
            action = GeofenceService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        startActivity(Intent(this, Dashboard::class.java))
        finish()
    }

    private fun startDashboardOnly() {
        startActivity(Intent(this, Dashboard::class.java))
        finish()
    }

    private fun hasForegroundLocation(): Boolean {
        return foregroundPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}