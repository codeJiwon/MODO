package com.modo.drive.core

import android.content.Context
import com.modo.drive.data.pleos.PleosNavigationController
import com.modo.drive.data.pleos.PleosSpeechOutputController
import com.modo.drive.data.pleos.PleosVoiceCommandController
import com.modo.drive.data.pleos.RealLocationProvider
import com.modo.drive.data.remote.UrlConnectionTransport
import com.modo.drive.data.repository.RemoteGroupRepository
import com.modo.drive.demo.DemoClock
import com.modo.drive.demo.DemoGroupRepository
import com.modo.drive.demo.DemoLocationProvider
import com.modo.drive.demo.DemoNavigationController
import com.modo.drive.demo.DemoSpeechOutputController
import com.modo.drive.demo.DemoVoiceCommandController
import com.modo.drive.domain.port.NavigationController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 수동 DI 컨테이너. Hilt 대신 사용한 이유: 의존 그래프가 작고, 빌드 설정(kapt/ksp)을 줄여
 * Pleos 환경에서의 빌드 위험을 낮추기 위함. Pleos SDK 객체는 LIVE 모드에서만 생성된다.
 */
class AppContainer(context: Context) : EnvironmentFactory {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val settings: SettingsStore = DataStoreSettings(appContext)

    private val pleosNavigation by lazy { PleosNavigationController(appContext) }
    private val pleosVoice by lazy { PleosVoiceCommandController(appContext) }
    private val pleosSpeech by lazy { PleosSpeechOutputController(appContext) }
    private val realLocation by lazy { RealLocationProvider(appContext) }

    private val demoEnvironment by lazy {
        val clock = DemoClock()
        val tools = DemoTools(
            repository = DemoGroupRepository(clock),
            clock = clock,
            navigation = DemoNavigationController(),
            voice = DemoVoiceCommandController(),
            speech = DemoSpeechOutputController(clock),
            location = DemoLocationProvider(),
        )
        ModeEnvironment(
            mode = AppMode.DEMO,
            repository = tools.repository,
            navigation = tools.navigation,
            voice = tools.voice,
            speech = tools.speech,
            location = tools.location,
            clock = clock,
            requireDriverConfirmation = false,
            locationIntervalMs = 0,
            demo = tools,
        )
    }

    override fun demo(): ModeEnvironment = demoEnvironment

    override fun live(serverUrl: String): ModeEnvironment = ModeEnvironment(
        mode = AppMode.LIVE,
        repository = RemoteGroupRepository(UrlConnectionTransport(serverUrl), appScope),
        navigation = pleosNavigation,
        voice = pleosVoice,
        speech = pleosSpeech,
        location = realLocation,
        clock = SystemClock,
        requireDriverConfirmation = true,
        locationIntervalMs = 10_000,
    )

    override fun pleosNavigation(): NavigationController = pleosNavigation
}
