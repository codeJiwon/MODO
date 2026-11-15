package com.modo.drive

import com.modo.drive.core.AppMode
import com.modo.drive.core.DemoTools
import com.modo.drive.core.EnvironmentFactory
import com.modo.drive.core.InMemorySettings
import com.modo.drive.core.ModeEnvironment
import com.modo.drive.core.SettingsStore
import com.modo.drive.demo.DemoClock
import com.modo.drive.demo.DemoGroupRepository
import com.modo.drive.demo.DemoLocationProvider
import com.modo.drive.demo.DemoNavigationController
import com.modo.drive.demo.DemoSpeechOutputController
import com.modo.drive.demo.DemoVoiceCommandController
import com.modo.drive.domain.port.NavigationController

/** 테스트용: 지연 없는 데모 구현체. Pleos SDK 객체는 만들지 않는다. */
class TestEnvironmentFactory(
    private val liveFactory: ((String) -> ModeEnvironment)? = null,
) : EnvironmentFactory {
    override val settings: SettingsStore = InMemorySettings()

    val clock = DemoClock()
    val tools = DemoTools(
        repository = DemoGroupRepository(clock),
        clock = clock,
        navigation = DemoNavigationController(latencyMs = 0),
        voice = DemoVoiceCommandController(stepDelayMs = 0),
        speech = DemoSpeechOutputController(clock),
        location = DemoLocationProvider(),
    )

    private val demoEnv = ModeEnvironment(
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

    override fun demo(): ModeEnvironment = demoEnv
    override fun live(serverUrl: String): ModeEnvironment = liveFactory?.invoke(serverUrl) ?: error("live not available in tests")
    override fun pleosNavigation(): NavigationController = tools.navigation
}
