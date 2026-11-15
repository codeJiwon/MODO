package com.modo.drive.data.pleos

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.util.Log
import com.modo.domain.engine.distanceMeters
import com.modo.domain.model.GeoPoint
import com.modo.domain.model.MovementStatus
import com.modo.drive.domain.port.LocationAvailability
import com.modo.drive.domain.port.LocationProvider
import com.modo.drive.domain.port.LocationSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Pleos FusedLocation SDK v1.1.3 연동.
 *
 * 문서 기준 lifecycle: FusedLocation(context) → isSupported() → initialize()
 * → registerFusedLocationCallback(listener) → unregisterFusedLocationCallback(listener) → release().
 * onChanged(LocationData?)의 null 값은 유효한 위치로 처리하지 않는다.
 * LocationData에는 속도 값이 없으므로 이동 여부는 연속된 두 위치 사이의 속도로 추정한다.
 */
class RealLocationProvider(context: Context) : LocationProvider {

    override val isSimulated: Boolean = false

    private val appContext = context.applicationContext
    private var fused: FusedLocation? = null
    private var initialized = false
    private val _availability = MutableStateFlow<LocationAvailability>(LocationAvailability.Idle)
    override val availability: StateFlow<LocationAvailability> = _availability.asStateFlow()

    private fun obtain(): FusedLocation? {
        fused?.let { return it }
        return try {
            val instance = FusedLocation(appContext)
            if (!instance.isSupported()) {
                _availability.value = LocationAvailability.NotSupported
                null
            } else {
                if (!initialized) {
                    instance.initialize()
                    initialized = true
                }
                fused = instance
                instance
            }
        } catch (t: Throwable) {
            Log.w(TAG, "FusedLocation unavailable", t)
            _availability.value = LocationAvailability.NotSupported
            null
        }
    }

    override fun updates(): Flow<LocationSample> = callbackFlow {
        val sdk = obtain()
        if (sdk == null) {
            close()
            return@callbackFlow
        }
        var previous: LocationSample? = null
        val listener = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                location ?: return
                val point = GeoPoint(location.latitude, location.longitude)
                val at = location.timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
                val movement = previous?.let { prev ->
                    val seconds = (at - prev.measuredAt) / 1000.0
                    if (seconds <= 0) prev.movementStatus
                    else if (distanceMeters(prev.point, point) / seconds >= MOVING_SPEED_MPS) MovementStatus.MOVING
                    else MovementStatus.STOPPED
                } ?: MovementStatus.UNKNOWN
                val sample = LocationSample(point, movement, at)
                previous = sample
                _availability.value = LocationAvailability.Available
                trySend(sample)
            }

            override fun onError(exception: Exception) {
                Log.w(TAG, "FusedLocation onError", exception)
                _availability.value = if (exception is SecurityException) {
                    LocationAvailability.Blocked("위치 접근이 차단되어 있어요. 차량 개인정보 보호 설정을 확인해 주세요.")
                } else {
                    LocationAvailability.Error("차량 위치를 받지 못하고 있어요.")
                }
            }
        }
        try {
            sdk.registerFusedLocationCallback(listener)
        } catch (e: SecurityException) {
            _availability.value = LocationAvailability.Blocked("위치 권한(FUSED_LOCATION)이 허용되지 않았어요.")
            close()
            return@callbackFlow
        }
        awaitClose { runCatching { sdk.unregisterFusedLocationCallback(listener) } }
    }

    override fun showAccessBlockedNotice() {
        runCatching { fused?.showLocationAccessBlockedPopup() }
    }

    fun release() {
        fused?.let { runCatching { it.release() } }
        fused = null
        initialized = false
    }

    private companion object {
        const val TAG = "MODO/FusedLocation"
        /** 약 5.4km/h 이상이면 이동 중으로 본다. */
        const val MOVING_SPEED_MPS = 1.5
    }
}
