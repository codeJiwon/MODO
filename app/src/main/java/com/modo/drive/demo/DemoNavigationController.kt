package com.modo.drive.demo

import com.modo.domain.model.Place
import com.modo.drive.domain.port.NavigationController
import com.modo.drive.domain.port.NavigationResult
import com.modo.drive.domain.port.NavigationServiceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 시뮬레이션 내비게이션. 실제 차량 경로는 바뀌지 않는다. */
class DemoNavigationController(private val latencyMs: Long = 900) : NavigationController {
    override val isSimulated: Boolean = true
    override val serviceState: StateFlow<NavigationServiceState> =
        MutableStateFlow<NavigationServiceState>(NavigationServiceState.Connected).asStateFlow()

    /** 테스트 조작: 다음 요청을 실패로 처리한다. */
    @Volatile var failNextRequest: Boolean = false

    val requested = mutableListOf<Place>()

    override suspend fun requestRoute(destination: Place): NavigationResult {
        requested += destination
        if (latencyMs > 0) delay(latencyMs)
        if (failNextRequest) {
            failNextRequest = false
            return NavigationResult.Failed("경로 탐색 서버에서 오류가 발생했어요. (시뮬레이션)")
        }
        return NavigationResult.Applied
    }
}
