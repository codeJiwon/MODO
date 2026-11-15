package com.modo.drive.domain.port

import com.modo.domain.model.Place
import kotlinx.coroutines.flow.StateFlow

sealed interface NavigationResult {
    /** 내비게이션 서비스가 경로 탐색 완료를 알림. */
    data object Applied : NavigationResult
    data class Failed(val message: String) : NavigationResult
    /** 제한 시간 안에 결과를 받지 못함. 성공으로 간주하지 않는다. */
    data class Timeout(val message: String) : NavigationResult
}

sealed interface NavigationServiceState {
    data object NotInitialized : NavigationServiceState
    data object Connecting : NavigationServiceState
    data object Connected : NavigationServiceState
    data class Unavailable(val message: String) : NavigationServiceState
}

/**
 * 내 차량의 내비게이션에 목적지 변경을 요청한다. 차량 운행 자체를 제어하지 않는다.
 * 실제 구현은 Pleos NaviHelper SDK의 requestRoute()를 사용한다.
 */
interface NavigationController {
    val isSimulated: Boolean
    val serviceState: StateFlow<NavigationServiceState>
    suspend fun requestRoute(destination: Place): NavigationResult
    fun release() {}
}
