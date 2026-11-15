package com.modo.drive.domain.port

import com.modo.domain.model.GeoPoint
import com.modo.domain.model.MovementStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class LocationSample(
    val point: GeoPoint,
    val movementStatus: MovementStatus,
    val measuredAt: Long,
)

sealed interface LocationAvailability {
    data object Idle : LocationAvailability
    data object Available : LocationAvailability
    /** 실행 환경이 위치 SDK를 지원하지 않음. */
    data object NotSupported : LocationAvailability
    /** 권한이 없거나 차량 개인정보 보호 설정으로 차단됨. */
    data class Blocked(val message: String) : LocationAvailability
    data class Error(val message: String) : LocationAvailability
}

/** 내 차량 위치를 제공한다. 실제 구현은 Pleos FusedLocation SDK, 데모 구현은 사전 정의된 경로를 사용한다. */
interface LocationProvider {
    val isSimulated: Boolean
    val availability: StateFlow<LocationAvailability>
    /** 구독하는 동안 위치 업데이트를 받는다. 구독이 끝나면 업데이트를 중지한다. */
    fun updates(): Flow<LocationSample>
    /** 위치 접근이 차단되었을 때 플랫폼 안내를 표시한다 (지원되는 경우). */
    fun showAccessBlockedNotice() {}
}
