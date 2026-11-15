package com.modo.drive.demo

import com.modo.domain.engine.DomainError
import com.modo.domain.engine.RendezvousSuggester
import com.modo.domain.engine.activePlan
import com.modo.domain.engine.activeProposal
import com.modo.domain.engine.actionsFor
import com.modo.domain.engine.member
import com.modo.domain.engine.openRecoveryFor
import com.modo.domain.model.ApprovalDecision
import com.modo.domain.model.ConnectionStatus
import com.modo.domain.model.GeoPoint
import com.modo.domain.model.JourneyCommand
import com.modo.domain.model.JourneyState
import com.modo.domain.model.MobilityRequestStatus
import com.modo.domain.model.MobilityRequestType
import com.modo.domain.model.MovementStatus
import com.modo.domain.model.NavigationActionStatus
import com.modo.domain.model.RecoveryReason
import com.modo.domain.model.RecoveryStatus
import com.modo.drive.domain.port.LocationSample
import com.modo.drive.domain.repository.CommandResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

data class DemoScene(val number: Int, val part: String?, val title: String, val description: String) {
    val label: String get() = "Scene $number" + (part?.let { " ($it)" } ?: "")
}

/**
 * 공모전 시연용 Demo Scenario를 단계별로 실행한다.
 *
 * 각 장면은 실제 도메인 명령(JourneyCommand)을 보내 상태를 바꾼다. 화면 텍스트만 바꾸지 않는다.
 * 장면은 멱등적으로 작성되어, 발표자가 차량 A의 행동을 화면에서 직접 수행한 뒤 "다음 장면"을 눌러도
 * 남은 단계만 실행된다. [jumpTo]는 Reset 후 처음부터 순서대로 실행하므로 항상 같은 결과를 재현한다.
 */
class DemoScenarioController(
    private val repository: DemoGroupRepository,
    private val clock: DemoClock,
    private val location: DemoLocationProvider,
    private val speech: DemoSpeechOutputController,
    /** 내 차량(A)의 경로 적용 처리가 끝날 때까지 기다린다. */
    private val awaitSelfIdle: suspend () -> Unit,
    /** 차량 [actorId]의 음성 요청을 화면에 재현하고 요청을 만든다. */
    private val runVoiceRequest: suspend (actorId: String, utterance: String) -> Unit,
) {
    val scenes: List<DemoScene> = listOf(
        DemoScene(1, null, "출발", "친구 8명, 차량 3대가 각자 강릉으로 이동 중"),
        DemoScene(2, null, "휴식 요청", "차량 B: \"우리 잠깐 쉬었다 가자.\" → REST_REQUEST"),
        DemoScene(3, null, "합류 제안", "차량 A가 횡성휴게소 제안 · A, B 동의 · C 대기"),
        DemoScene(4, null, "합의", "차량 C 동의 → 제안 APPROVED"),
        DemoScene(5, null, "경로 적용", "A·B 적용 완료, C 운전자 확인 대기"),
        DemoScene(6, null, "합류 실패", "차량 C가 횡성휴게소를 지나침 → RecoverySession"),
        DemoScene(7, null, "재합류", "평창휴게소로 새 합류 계획 #2 합의 · 경로 갱신"),
        DemoScene(8, "1/2", "합류", "세 차량이 평창휴게소에서 합류 확인"),
        DemoScene(8, "2/2", "도착", "세 차량 모두 강릉역 도착 확인 → 완료"),
    )

    private val _currentIndex = MutableStateFlow(-1)
    /** 마지막으로 실행한 장면 index (-1: 시작 전). */
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val errors = mutableListOf<DomainError>()

    private val state: JourneyState get() = repository.state.value ?: error("demo not started")

    suspend fun reset(): JourneyState {
        location.reset()
        speech.clear()
        val seeded = repository.reset()
        _currentIndex.value = 0
        return seeded
    }

    /** 다음 장면을 실행하고, 실행 중 거절된 명령이 있으면 반환한다. */
    suspend fun next(): List<DomainError> {
        val target = (_currentIndex.value + 1).coerceAtMost(scenes.lastIndex)
        if (_currentIndex.value < 0) {
            reset()
            return emptyList()
        }
        if (target == _currentIndex.value) return emptyList()
        return runScene(target)
    }

    /** Reset 후 [index]까지 순서대로 실행한다. */
    suspend fun jumpTo(index: Int): List<DomainError> {
        reset()
        val all = mutableListOf<DomainError>()
        for (i in 1..index.coerceIn(0, scenes.lastIndex)) all += runScene(i)
        return all
    }

    private suspend fun runScene(index: Int): List<DomainError> {
        errors.clear()
        when (index) {
            1 -> sceneRestRequest()
            2 -> sceneProposal()
            3 -> sceneAgreement()
            4 -> sceneRouteAction()
            5 -> sceneRecovery()
            6 -> sceneReplan()
            7 -> sceneMeet()
            8 -> sceneArrive()
        }
        _currentIndex.value = index
        return errors.toList()
    }

    // region scenes

    private suspend fun sceneRestRequest() {
        val exists = state.requests.any {
            it.requesterId == DemoData.B && it.type == MobilityRequestType.REST_REQUEST &&
                (it.status == MobilityRequestStatus.OPEN || it.status == MobilityRequestStatus.IN_PROGRESS)
        }
        if (!exists) runVoiceRequest(DemoData.B, DemoVoiceCommandController.DEFAULT_UTTERANCE)
    }

    private suspend fun sceneProposal() {
        if (state.activeProposal() == null && state.activePlan() == null) {
            send(JourneyCommand.ProposeRendezvous(id(), DemoData.SELF, DemoData.hoengseong))
        }
        respondIfPending(DemoData.B)
    }

    private suspend fun sceneAgreement() {
        respondIfPending(DemoData.B)
        respondIfPending(DemoData.C)
    }

    private suspend fun sceneRouteAction() {
        val plan = state.activePlan() ?: return
        if (state.actionsFor(plan.id).isEmpty()) send(JourneyCommand.DispatchNavigation(id(), DemoData.SELF, plan.id))
        awaitSelfIdle()
        moveAll(
            self = DemoData.between(DemoData.munmak, DemoData.hoengseong, 0.55),
            b = DemoData.between(DemoData.munmak, DemoData.hoengseong, 0.45),
            c = DemoData.between(DemoData.munmak, DemoData.hoengseong, 0.85),
        )
    }

    private suspend fun sceneRecovery() {
        val plan = state.activePlan() ?: return
        // A, B는 횡성휴게소에 도착, C는 경로가 바뀌지 않아 휴게소를 지나침 (시뮬레이션 이벤트)
        moveSelf(plan.location.location, MovementStatus.STOPPED)
        moveOther(DemoData.B, plan.location.location, MovementStatus.STOPPED)
        val cPoint = DemoData.between(DemoData.hoengseong, DemoData.pyeongchang, 0.3)
        moveOther(DemoData.C, cPoint, MovementStatus.MOVING)
        for (v in listOf(DemoData.SELF, DemoData.B)) {
            if (v !in plan.arrivedVehicleIds) send(JourneyCommand.ConfirmRendezvousArrival(id(), v, plan.id))
        }
        if (state.openRecoveryFor(DemoData.C) == null) missedRendezvous(DemoData.C)
    }

    private suspend fun sceneReplan() {
        val session = state.openRecoveryFor(DemoData.C) ?: state.recoverySessions.lastOrNull()
        if (session != null && (session.status == RecoveryStatus.OPEN || session.status == RecoveryStatus.DEFERRED) && state.activeProposal() == null) {
            val place = session.proposedMeetingPoint ?: DemoData.pyeongchang
            send(JourneyCommand.ProposeRendezvous(id(), DemoData.SELF, place, recoverySessionId = session.id))
        }
        respondIfPending(DemoData.B)
        respondIfPending(DemoData.C)
        val plan = state.activePlan() ?: return
        if (state.actionsFor(plan.id).isEmpty()) send(JourneyCommand.DispatchNavigation(id(), DemoData.SELF, plan.id))
        awaitSelfIdle()
        moveAll(
            self = DemoData.between(DemoData.hoengseong, DemoData.pyeongchang, 0.15),
            b = DemoData.between(DemoData.hoengseong, DemoData.pyeongchang, 0.12),
            c = DemoData.between(DemoData.hoengseong, DemoData.pyeongchang, 0.6),
        )
    }

    private suspend fun sceneMeet() {
        val plan = state.activePlan() ?: return
        moveAll(plan.location.location, plan.location.location, plan.location.location, MovementStatus.STOPPED)
        for (v in listOf(DemoData.SELF, DemoData.B, DemoData.C)) {
            if (v !in (state.activePlan()?.arrivedVehicleIds ?: emptySet()) && state.activePlan()?.id == plan.id) {
                send(JourneyCommand.ConfirmRendezvousArrival(id(), v, plan.id))
            }
        }
    }

    private suspend fun sceneArrive() {
        val destination = state.group.destination.location
        moveAll(destination, destination, destination, MovementStatus.STOPPED)
        for (v in listOf(DemoData.SELF, DemoData.B, DemoData.C)) {
            if (state.member(v)?.arrivalConfirmed == false) send(JourneyCommand.ConfirmDestinationArrival(id(), v))
        }
    }

    // endregion

    // region 데모 패널의 테스트 조작 (다른 차량의 행동을 시뮬레이션)

    suspend fun respondAs(vehicleId: String, approve: Boolean): List<DomainError> = collect {
        val proposal = state.activeProposal() ?: run {
            errors += DomainError.proposalNotFound()
            return@collect
        }
        send(JourneyCommand.RespondToProposal(id(), vehicleId, proposal.id, approve))
    }

    /** 차량 C 내비게이션 상태 변경: REQUESTED(운전자 확인 → 적용), FAILED, UNKNOWN(응답 없음). */
    suspend fun setVehicleNavigation(vehicleId: String, status: NavigationActionStatus): List<DomainError> = collect {
        val plan = state.activePlan() ?: run {
            errors += DomainError.noActivePlan()
            return@collect
        }
        val action = state.navigationActions.lastOrNull { it.proposalId == plan.id && it.vehicleId == vehicleId } ?: run {
            errors += DomainError.actionNotFound()
            return@collect
        }
        val message = when (status) {
            NavigationActionStatus.FAILED -> "경로 탐색 서버에서 오류가 발생했어요. (시뮬레이션)"
            NavigationActionStatus.UNKNOWN -> "응답 시간이 초과되었어요. (시뮬레이션)"
            else -> null
        }
        send(JourneyCommand.ReportNavigationResult(id(), vehicleId, action.id, status, message))
    }

    suspend fun missedRendezvous(vehicleId: String = DemoData.C): List<DomainError> = collect {
        val plan = state.activePlan()
        val affected = state.member(vehicleId)?.location?.let(::listOf) ?: emptyList()
        val suggestion = RendezvousSuggester.suggest(
            candidatesInRouteOrder = DemoData.restAreas,
            destination = state.group.destination,
            affectedLocations = affected,
            exclude = setOfNotNull(plan?.location?.id),
        )
        send(JourneyCommand.OpenRecovery(id(), vehicleId, listOf(vehicleId), RecoveryReason.MISSED_RENDEZVOUS, suggestion))
    }

    suspend fun toggleConnection(vehicleId: String): List<DomainError> = collect {
        val current = state.member(vehicleId)?.connectionStatus ?: return@collect
        val next = if (current == ConnectionStatus.CONNECTED) ConnectionStatus.DISCONNECTED else ConnectionStatus.CONNECTED
        send(JourneyCommand.SetConnection(id(), "server", vehicleId, next))
    }

    /** [vehicleId]의 위치 갱신만 멈춘 채 2분이 지난 상황을 만든다. */
    suspend fun simulateLocationLoss(vehicleId: String): List<DomainError> = collect {
        clock.tick(120_000)
        for (member in state.group.members) {
            if (member.id == vehicleId) continue
            val point = member.location ?: continue
            if (member.id == DemoData.SELF) moveSelf(point, member.movementStatus) else moveOther(member.id, point, member.movementStatus)
        }
    }

    // endregion

    // region helpers

    private suspend fun collect(block: suspend () -> Unit): List<DomainError> {
        errors.clear()
        block()
        return errors.toList()
    }

    private fun id() = repository.nextCommandId()

    private suspend fun send(command: JourneyCommand) {
        val result = repository.send(command)
        if (result is CommandResult.Failed) errors += result.error
    }

    private suspend fun respondIfPending(vehicleId: String) {
        val proposal = state.activeProposal() ?: return
        if (proposal.approvals[vehicleId] == ApprovalDecision.PENDING) {
            send(JourneyCommand.RespondToProposal(id(), vehicleId, proposal.id, true))
        }
    }

    private suspend fun moveAll(self: GeoPoint, b: GeoPoint, c: GeoPoint, status: MovementStatus = MovementStatus.MOVING) {
        moveSelf(self, status)
        moveOther(DemoData.B, b, status)
        moveOther(DemoData.C, c, status)
    }

    private suspend fun moveOther(vehicleId: String, point: GeoPoint, status: MovementStatus) {
        if (state.member(vehicleId)?.locationSharingEnabled != true) return
        send(JourneyCommand.UpdateLocation(id(), vehicleId, point, status, clock.tick(1)))
    }

    /** 내 차량 위치는 DemoLocationProvider → LocationSync 경로로 보낸다 (실제 위치 SDK와 같은 경로). */
    private suspend fun moveSelf(point: GeoPoint, status: MovementStatus) {
        if (state.member(DemoData.SELF)?.locationSharingEnabled != true) return
        val at = clock.tick(1)
        location.moveTo(LocationSample(point, status, at))
        withTimeoutOrNull(1_500) {
            repository.state.filterNotNull().first { (it.member(DemoData.SELF)?.lastUpdatedAt ?: 0) >= at }
        }
    }

    // endregion
}
