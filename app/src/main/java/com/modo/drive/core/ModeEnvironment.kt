package com.modo.drive.core

import com.modo.drive.demo.DemoClock
import com.modo.drive.demo.DemoGroupRepository
import com.modo.drive.demo.DemoLocationProvider
import com.modo.drive.demo.DemoNavigationController
import com.modo.drive.demo.DemoSpeechOutputController
import com.modo.drive.demo.DemoVoiceCommandController
import com.modo.drive.domain.port.LocationProvider
import com.modo.drive.domain.port.NavigationController
import com.modo.drive.domain.port.SpeechOutputController
import com.modo.drive.domain.port.VoiceCommandController
import com.modo.drive.domain.repository.GroupRepository

/** 한 모드(DEMO/LIVE)에서 사용하는 구현체 묶음. ViewModel은 모드와 무관하게 이 인터페이스들만 사용한다. */
class ModeEnvironment(
    val mode: AppMode,
    val repository: GroupRepository,
    val navigation: NavigationController,
    val voice: VoiceCommandController,
    val speech: SpeechOutputController,
    val location: LocationProvider,
    val clock: Clock,
    /** 경로 변경 전에 운전자 확인을 받을지 여부. */
    val requireDriverConfirmation: Boolean,
    /** 위치 전송 최소 간격. */
    val locationIntervalMs: Long,
    val demo: DemoTools? = null,
)

class DemoTools(
    val repository: DemoGroupRepository,
    val clock: DemoClock,
    val navigation: DemoNavigationController,
    val voice: DemoVoiceCommandController,
    val speech: DemoSpeechOutputController,
    val location: DemoLocationProvider,
)

interface EnvironmentFactory {
    val settings: SettingsStore
    fun demo(): ModeEnvironment
    fun live(serverUrl: String): ModeEnvironment
    /** 데모 중 "내 차량에 실제 NaviHelper 사용" 옵션용. Pleos 환경이 아니면 연결 실패로 보고된다. */
    fun pleosNavigation(): NavigationController
}
