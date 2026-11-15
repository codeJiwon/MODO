package com.modo.drive.data.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.modo.domain.engine.distanceMeters
import com.modo.domain.model.GeoPoint
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
 * requestRoute() 호출 성공은 경로 탐색 완료를 의미하지 않으므로(문서 Destinations > 정보) 콜백으로 결과를 판단한다.
 * - onRoutingCompleted() → 적용
 * - onRouteStarted() → getDestinationInfo()로 Navigation 앱의 현재 목적지를 조회해 요청 좌표와 300m 이내일 때만 적용
 *   (Pleos Connect Emulator v2.1.0 / Navi service 3.0.4 실측: requestRoute 후 onRoutingCompleted 없이
 *   onRouteStarted → onRouteEnded → onNavigationStatus → onRouteStateInfo 순으로 전달됨)
 * - onError() → 실패, 제한 시간 내 확인 불가 → 시간 초과(UNKNOWN). 확인되지 않은 요청을 성공으로 보고하지 않는다.
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

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            Log.d(TAG, "onDestinationInfo ${destinationInfo.name} ${destinationInfo.latitude},${destinationInfo.longitude}")
            events.tryEmit(NaviEvent.Destination(GeoPoint(destinationInfo.latitude, destinationInfo.longitude), destinationInfo.name))
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
                        // 기존 경로가 있으면 새 요청 직후 기존 경로의 onRouteCancelled가 먼저 온다 (실측). 최종 결과로 보지 않는다.
                        events.first {
                            it is NaviEvent.RoutingCompleted || it is NaviEvent.RouteStarted || it is NaviEvent.Error
                        }
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
                    NaviEvent.RouteStarted -> verifyDestination(helper, destination)
                    is NaviEvent.Destination -> verifyDestination(helper, destination)
                }
            }
        }
    }

    /** Navigation 앱의 현재 목적지가 요청한 좌표와 일치하는지 확인한다. */
    private suspend fun verifyDestination(helper: NaviHelper, destination: Place): NavigationResult = coroutineScope {
        var lastSeen: NaviEvent.Destination? = null
        val matched = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(VERIFY_TIMEOUT_MS) {
                events.first { event ->
                    if (event !is NaviEvent.Destination) return@first false
                    // 목적지가 없을 때 (0,0)이 전달될 수 있으므로 일치하는 값만 성공으로 본다.
                    if (event.point.latitude != 0.0 || event.point.longitude != 0.0) lastSeen = event
                    distanceMeters(event.point, destination.location) <= MATCH_RADIUS_M
                }
            }
        }
        runCatching { helper.getDestinationInfo() }.onFailure { Log.w(TAG, "getDestinationInfo threw", it) }
        when {
            matched.await() != null -> NavigationResult.Applied
            lastSeen != null -> NavigationResult.Failed("내비게이션 목적지가 합류 장소와 달라요. (${lastSeen?.name})")
            else -> NavigationResult.Timeout("경로 탐색은 시작됐지만 목적지 적용을 확인하지 못했어요.")
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
        data class Destination(val point: GeoPoint, val name: String) : NaviEvent
    }

    private object TimeoutConnect : Throwable() {
        private fun readResolve(): Any = TimeoutConnect
    }

    companion object {
        private const val TAG = "MODO/NaviHelper"
        private const val VERIFY_TIMEOUT_MS = 8_000L
        private const val MATCH_RADIUS_M = 300.0

        fun NaviErrorCode.toKoreanMessage(): String = when (this) {
            NaviErrorCode.CAUSE_LOW_BATTERY -> "차량 배터리가 부족해 경로를 탐색할 수 없어요."
            NaviErrorCode.CAUSE_SEARCH_ROUTE_OVER_DISTANCE -> "경로 탐색 가능 거리를 초과했어요."
            NaviErrorCode.CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE -> "현재 위치와 합류 장소가 너무 가까워요."
            NaviErrorCode.CAUSE_SEARCH_ROUTE -> "경로 탐색 서버에서 오류가 발생했어요."
            else -> "내비게이션 오류가 발생했어요. ($name)"
        }
    }
}
