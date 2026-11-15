package com.modo.drive.data.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.modo.domain.model.Place
import com.modo.drive.domain.port.NavigationController
import com.modo.drive.domain.port.NavigationResult
import com.modo.drive.domain.port.NavigationServiceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pleos NaviHelper SDK v3.0.5 연동.
 *
 * 문서 기준 lifecycle: NaviHelper(context) → initialize(onConnected, onFailed) → addListener()
 * → requestRoute(RouteInfo) → 이벤트 콜백 → removeListener() → release().
 *
 * requestRoute() 호출 성공은 경로 탐색 완료를 의미하지 않으므로(문서 Destinations > 정보),
 * onRoutingCompleted() 콜백을 받은 경우에만 [NavigationResult.Applied]로 보고한다.
 * onError()는 실패, 제한 시간 내 콜백이 없으면 시간 초과(UNKNOWN)로 보고한다.
 */
class PleosNavigationController(
    context: Context,
    private val connectTimeoutMs: Long = 10_000,
    private val routeTimeoutMs: Long = 20_000,
) : NavigationController {

    override val isSimulated: Boolean = false

    private val appContext = context.applicationContext
    private var naviHelper: NaviHelper? = null
    private val _serviceState = MutableStateFlow<NavigationServiceState>(NavigationServiceState.NotInitialized)
    override val serviceState: StateFlow<NavigationServiceState> = _serviceState.asStateFlow()

    private val events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 32)
    private val requestMutex = Mutex()
    private val connectMutex = Mutex()

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            Log.d(TAG, "onRouteStarted ${routeStartInfo.name}")
            events.tryEmit(NaviEvent.RouteStarted)
        }

        override fun onRoutingCompleted() {
            Log.d(TAG, "onRoutingCompleted")
            events.tryEmit(NaviEvent.RoutingCompleted)
        }

        override fun onRouteCancelled() {
            Log.d(TAG, "onRouteCancelled")
            events.tryEmit(NaviEvent.RouteCancelled)
        }

        override fun onDestinationChanged() {
            Log.d(TAG, "onDestinationChanged")
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "onError $errorCode")
            events.tryEmit(NaviEvent.Error(errorCode))
        }
    }

    private suspend fun ensureConnected(): Boolean = connectMutex.withLock {
        if (_serviceState.value == NavigationServiceState.Connected) return true
        _serviceState.value = NavigationServiceState.Connecting
        val result = CompletableDeferred<Throwable?>()
        val helper = try {
            NaviHelper(appContext).also { naviHelper = it }
        } catch (t: Throwable) {
            _serviceState.value = NavigationServiceState.Unavailable("내비게이션 SDK를 사용할 수 없는 환경이에요.")
            return false
        }
        try {
            helper.initialize(
                onConnected = { result.complete(null) },
                onFailed = { throwable -> result.complete(throwable) },
            )
        } catch (t: Throwable) {
            result.complete(t)
        }
        val failure = withTimeoutOrNull(connectTimeoutMs) { result.await() }
            ?: if (result.isCompleted) null else TimeoutConnect
        return if (failure == null) {
            helper.addListener(listener)
            _serviceState.value = NavigationServiceState.Connected
            true
        } else {
            Log.w(TAG, "NaviHelper connection failed", failure)
            runCatching { helper.release() }
            naviHelper = null
            _serviceState.value = NavigationServiceState.Unavailable(
                if (failure === TimeoutConnect) "내비게이션 서비스 연결 시간이 초과되었어요."
                else "내비게이션 서비스에 연결하지 못했어요.",
            )
            false
        }
    }

    override suspend fun requestRoute(destination: Place): NavigationResult {
        if (!ensureConnected()) {
            val state = _serviceState.value
            return NavigationResult.Failed((state as? NavigationServiceState.Unavailable)?.message ?: "내비게이션 서비스에 연결하지 못했어요.")
        }
        val helper = naviHelper ?: return NavigationResult.Failed("내비게이션 서비스에 연결하지 못했어요.")
        return requestMutex.withLock {
            coroutineScope {
                val outcome = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(routeTimeoutMs) {
                        events.first { it is NaviEvent.RoutingCompleted || it is NaviEvent.Error || it is NaviEvent.RouteCancelled }
                    }
                }
                try {
                    helper.requestRoute(
                        RouteInfo(
                            latitude = destination.location.latitude,
                            longitude = destination.location.longitude,
                            displayLongitude = null,
                            displayLatitude = null,
                            poiName = destination.name,
                            // MODO 후보지는 Pleos POI DB id가 없으므로 좌표 기반으로 요청한다 (poiId는 non-null 필수).
                            poiId = "",
                            poiSubId = "0",
                            address = destination.address,
                        ),
                    )
                } catch (t: Throwable) {
                    outcome.cancel()
                    Log.w(TAG, "requestRoute threw", t)
                    return@coroutineScope NavigationResult.Failed(
                        if (t is SecurityException) "내비게이션 권한(NAVI_ROUTE_SEARCH)이 허용되지 않았어요."
                        else "내비게이션에 경로를 요청하지 못했어요.",
                    )
                }
                when (val event = outcome.await()) {
                    null -> NavigationResult.Timeout("내비게이션 응답 시간이 초과되었어요. 차량 화면에서 경로를 확인해 주세요.")
                    NaviEvent.RoutingCompleted -> NavigationResult.Applied
                    NaviEvent.RouteCancelled -> NavigationResult.Failed("내비게이션에서 경로 안내가 취소되었어요.")
                    is NaviEvent.Error -> NavigationResult.Failed(event.code.toKoreanMessage())
                    NaviEvent.RouteStarted -> NavigationResult.Timeout("경로 탐색 완료를 확인하지 못했어요.")
                }
            }
        }
    }

    override fun release() {
        naviHelper?.let { helper ->
            runCatching { helper.removeListener(listener) }
            runCatching { helper.release() }
        }
        naviHelper = null
        _serviceState.value = NavigationServiceState.NotInitialized
    }

    private sealed interface NaviEvent {
        data object RouteStarted : NaviEvent
        data object RoutingCompleted : NaviEvent
        data object RouteCancelled : NaviEvent
        data class Error(val code: NaviErrorCode) : NaviEvent
    }

    private object TimeoutConnect : Throwable() {
        private fun readResolve(): Any = TimeoutConnect
    }

    companion object {
        private const val TAG = "MODO/NaviHelper"

        fun NaviErrorCode.toKoreanMessage(): String = when (this) {
            NaviErrorCode.CAUSE_LOW_BATTERY -> "차량 배터리가 부족해 경로를 탐색할 수 없어요."
            NaviErrorCode.CAUSE_SEARCH_ROUTE_OVER_DISTANCE -> "경로 탐색 가능 거리를 초과했어요."
            NaviErrorCode.CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE -> "현재 위치와 합류 장소가 너무 가까워요."
            NaviErrorCode.CAUSE_SEARCH_ROUTE -> "경로 탐색 서버에서 오류가 발생했어요."
            else -> "내비게이션 오류가 발생했어요. ($name)"
        }
    }
}
