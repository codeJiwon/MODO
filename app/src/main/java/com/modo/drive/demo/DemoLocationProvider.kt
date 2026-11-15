package com.modo.drive.demo

import com.modo.drive.domain.port.LocationAvailability
import com.modo.drive.domain.port.LocationProvider
import com.modo.drive.domain.port.LocationSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** 데모 시나리오가 지정한 위치를 내 차량 위치로 내보내는 시뮬레이션 위치 제공자. */
class DemoLocationProvider : LocationProvider {
    override val isSimulated: Boolean = true
    override val availability: StateFlow<LocationAvailability> =
        MutableStateFlow<LocationAvailability>(LocationAvailability.Available).asStateFlow()

    private val samples = MutableSharedFlow<LocationSample>(replay = 1, extraBufferCapacity = 8)

    override fun updates(): Flow<LocationSample> = samples.asSharedFlow()

    fun moveTo(sample: LocationSample) {
        samples.tryEmit(sample)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun reset() = samples.resetReplayCache()
}
