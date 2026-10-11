package com.fieldkit.app.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * GPS fixes straight from the platform LocationManager (no Google Play
 * services, so it works offline). Used only to measure the distance between
 * the transect start point and each stop; coordinates are never stored.
 * Callers must hold ACCESS_FINE_LOCATION before calling [start].
 */
class LocationSource(context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _fix = MutableStateFlow<Location?>(null)
    val fix: StateFlow<Location?> = _fix

    private var listening = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _fix.value = location
        }

        // Abstract before Android 11, so they must be implemented for minSdk 26.
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    val gpsEnabled: Boolean
        get() = try {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            false
        }

    /** Returns false if GPS is unavailable or switched off. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (listening) return true
        return try {
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return false
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            listening = true
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        if (!listening) return
        listening = false
        try {
            manager.removeUpdates(listener)
        } catch (e: Exception) {
            // already removed
        }
        _fix.value = null
    }
}
