package com.modo.drive

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.modo.drive.data.pleos.PleosNavigationController
import com.modo.drive.data.pleos.PleosSpeechOutputController
import com.modo.drive.data.pleos.PleosVoiceCommandController
import com.modo.drive.data.pleos.RealLocationProvider
import com.modo.drive.demo.DemoData
import com.modo.drive.domain.port.LocationAvailability
import com.modo.drive.domain.port.NavigationResult
import com.modo.drive.domain.port.NavigationServiceState
import com.modo.drive.domain.port.VoiceEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Pleos Connect Emulator에서 실제 SDK 동작을 검증한다. Pleos 서비스가 없는 기기에서는 건너뛴다(assume).
 * 실행: ./gradlew :app:connectedDebugAndroidTest
 * 결과는 logcat 태그 MODO/SdkTest 로도 남긴다.
 */
@RunWith(AndroidJUnit4::class)
class PleosSdkIntegrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun isPleos(): Boolean = runCatching {
        context.packageManager.getPermissionInfo("pleos.car.permission.NAVI_ROUTE_SEARCH", 0)
    }.isSuccess

    @Test
    fun naviHelper_requestRoute_appliesVerifiedDestination() = runBlocking<Unit> {
        assumeTrue("Pleos Connect 환경이 아님", isPleos())
        // 소프트웨어 렌더링 에뮬레이터에서는 경로 탐색 콜백이 느리므로 제한 시간을 넉넉히 둔다.
        val navigation = PleosNavigationController(context, routeTimeoutMs = 90_000)
        try {
            val result = navigation.requestRoute(DemoData.pyeongchang)
            Log.i(TAG, "NaviHelper requestRoute(평창휴게소) → $result, service=${navigation.serviceState.value}")
            assertEquals(NavigationServiceState.Connected, navigation.serviceState.value)
            assertIs<NavigationResult.Applied>(result)
        } finally {
            navigation.release()
        }
    }

    @Test
    fun fusedLocation_deliversVehicleLocationAfterPermission() = runBlocking<Unit> {
        assumeTrue("Pleos Connect 환경이 아님", isPleos())
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, "pleos.car.permission.FUSED_LOCATION")
        val provider = RealLocationProvider(context)
        try {
            val sample = withTimeoutOrNull(30_000) { provider.updates().first() }
            Log.i(TAG, "FusedLocation first sample=$sample availability=${provider.availability.value}")
            assumeTrue("FusedLocation 미지원 환경", provider.availability.value != LocationAvailability.NotSupported)
            assertNotNull(sample)
        } finally {
            provider.release()
        }
    }

    @Test
    fun speechToText_onDevice_respondsWithinTimeout() = runBlocking<Unit> {
        assumeTrue("Pleos Connect 환경이 아님", isPleos())
        val voice = PleosVoiceCommandController(context, listenTimeoutMs = 10_000)
        try {
            // 에뮬레이터 마이크 입력이 없으면 오류/시간 초과가 정상 응답이다. 서비스가 응답하는지(멈추지 않는지)를 확인한다.
            val events = mutableListOf<VoiceEvent>()
            withTimeoutOrNull(25_000) { voice.listen().collect { events += it } }
            Log.i(TAG, "SpeechToText events=$events")
            assert(events.isNotEmpty())
            assert(events.last() is VoiceEvent.Final || events.last() is VoiceEvent.Error)
        } finally {
            voice.release()
        }
    }

    @Test
    fun textToSpeech_onDevice_initializes() = runBlocking<Unit> {
        assumeTrue("Pleos Connect 환경이 아님", isPleos())
        val speech = PleosSpeechOutputController(context)
        try {
            speech.speak("MODO 음성 안내 테스트입니다.")
            val status = withTimeoutOrNull(15_000) { speech.status.first { it != "초기화 전" } }
            Log.i(TAG, "TextToSpeech status=$status")
            assertEquals("연결됨", status)
        } finally {
            speech.release()
        }
    }

    private companion object {
        const val TAG = "MODO/SdkTest"
    }
}
