package com.modo.drive.presentation

import com.modo.domain.engine.activePlan
import com.modo.domain.engine.activeProposal
import com.modo.domain.model.JourneyState
import com.modo.domain.model.JourneyStatus
import com.modo.domain.model.MobilityRequest
import com.modo.domain.model.MobilityRequestStatus
import com.modo.domain.model.MobilityRequestType
import com.modo.domain.model.Place
import com.modo.domain.model.RecoverySession
import com.modo.domain.model.RecoveryStatus
import com.modo.drive.core.AppMode
import com.modo.drive.demo.DemoScene
import com.modo.drive.domain.port.SpokenLine
import com.modo.drive.domain.repository.SyncStatus

enum class AppScreen { WELCOME, CREATE, JOIN, JOURNEY, COMPLETED }

/** Journey 화면 오른쪽 영역에 표시할 "지금 필요한 행동". 상태에서 자동으로 결정된다. */
enum class Stage { ROOM, INTENT, RENDEZVOUS, ROUTE_ACTION, RECOVERY }

data class VoiceUi(
    val phase: Phase = Phase.IDLE,
    val speakerId: String? = null,
    val transcript: String = "",
    val error: String? = null,
    val simulated: Boolean = true,
) {
    enum class Phase { IDLE, LISTENING, ERROR }
}

data class Banner(val id: Long, val message: String, val tone: Tone, val retryable: Boolean = false) {
    enum class Tone { INFO, SUCCESS, WARNING, ERROR }
}

data class DemoUi(
    val scenes: List<DemoScene>,
    val currentIndex: Int,
    val running: Boolean,
    val panelOpen: Boolean,
    val offline: Boolean,
    val useRealNavigation: Boolean,
)

data class SdkStatusUi(
    val navigation: String,
    val location: String,
    val voice: String,
    val speech: String,
)

data class ModoUiState(
    val screen: AppScreen = AppScreen.WELCOME,
    val mode: AppMode? = null,
    val selfId: String = "",
    val journey: JourneyState? = null,
    val now: Long = 0,
    val stage: Stage = Stage.ROOM,
    val syncStatus: SyncStatus = SyncStatus.IDLE,
    val voice: VoiceUi = VoiceUi(),
    val banner: Banner? = null,
    val spoken: SpokenLine? = null,
    val demo: DemoUi? = null,
    val sdk: SdkStatusUi? = null,
    val pickerOpen: Boolean = false,
    val candidates: List<Place> = emptyList(),
    val busy: Boolean = false,
    val settingsOpen: Boolean = false,
    val serverUrl: String = "",
    val selfActionInFlight: Boolean = false,
) {
    val isDemo: Boolean get() = mode == AppMode.DEMO
}

/** 현재 상태에서 보여줄 단계를 결정한다 (주행 중 화면 전환과 조작을 최소화하기 위해 자동 결정). */
fun deriveStage(
    state: JourneyState?,
    voice: VoiceUi,
    pickerOpen: Boolean,
    dismissedRequestIds: Set<String>,
    dismissedRecoveryIds: Set<String>,
): Stage {
    state ?: return Stage.ROOM
    if (state.group.status != JourneyStatus.ACTIVE) return Stage.ROOM
    if (activeRecovery(state, dismissedRecoveryIds) != null) return Stage.RECOVERY
    if (state.activeProposal() != null || pickerOpen) return Stage.RENDEZVOUS
    if (state.activePlan() != null) return Stage.ROUTE_ACTION
    if (voice.phase != VoiceUi.Phase.IDLE) return Stage.INTENT
    if (openIntentRequest(state, dismissedRequestIds) != null) return Stage.INTENT
    return Stage.ROOM
}

fun activeRecovery(state: JourneyState, dismissed: Set<String> = emptySet()): RecoverySession? =
    state.recoverySessions.lastOrNull {
        (it.status == RecoveryStatus.OPEN || it.status == RecoveryStatus.REPLANNING) && it.id !in dismissed
    }

fun deferredRecovery(state: JourneyState): RecoverySession? =
    state.recoverySessions.lastOrNull { it.status == RecoveryStatus.DEFERRED }

/** 합류 장소 결정이 필요한 열린 요청. */
fun openIntentRequest(state: JourneyState, dismissed: Set<String> = emptySet()): MobilityRequest? =
    state.requests.lastOrNull {
        it.status == MobilityRequestStatus.OPEN && it.id !in dismissed &&
            (it.type == MobilityRequestType.REST_REQUEST || it.type == MobilityRequestType.RENDEZVOUS_REQUEST ||
                it.type == MobilityRequestType.DESTINATION_CHANGE)
    }
