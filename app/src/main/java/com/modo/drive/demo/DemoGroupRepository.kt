package com.modo.drive.demo

import com.modo.domain.engine.DomainError
import com.modo.domain.engine.EngineResult
import com.modo.domain.engine.JourneyEngine
import com.modo.domain.engine.SequentialIdGenerator
import com.modo.domain.engine.activePlan
import com.modo.domain.model.JourneyCommand
import com.modo.domain.model.JourneyEvent
import com.modo.domain.model.JourneyState
import com.modo.domain.model.NavigationActionStatus
import com.modo.domain.model.Place
import com.modo.drive.domain.repository.CommandResult
import com.modo.drive.domain.repository.GroupRepository
import com.modo.drive.domain.repository.MemberProfile
import com.modo.drive.domain.repository.SessionResult
import com.modo.drive.domain.repository.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 서버 없이 동작하는 In-Memory 그룹 저장소. [RemoteGroupRepository][com.modo.drive.data.repository.RemoteGroupRepository]와
 * 같은 인터페이스, 같은 [JourneyEngine] 규칙을 사용한다.
 *
 * 다른 차량(B, C)의 단말 동작은 [DemoFleetReactor]가 시뮬레이션한다.
 */
class DemoGroupRepository(
    private val clock: DemoClock,
    private val ids: SequentialIdGenerator = SequentialIdGenerator(),
    private val reactor: DemoFleetReactor = DemoFleetReactor(selfId = DemoData.SELF),
) : GroupRepository {

    override val isSimulated: Boolean = true

    private val engine = JourneyEngine(ids)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<JourneyState?>(null)
    override val state: StateFlow<JourneyState?> = _state.asStateFlow()
    private val _syncStatus = MutableStateFlow(SyncStatus.IDLE)
    override val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    /** 테스트 조작: 네트워크 연결 실패를 시뮬레이션한다. */
    @Volatile var simulateOffline: Boolean = false
        set(value) {
            field = value
            if (_state.value != null) _syncStatus.value = if (value) SyncStatus.OFFLINE else SyncStatus.CONNECTED
        }

    fun nextCommandId(): String = ids.next("cmd")

    /** Reset Demo: 모든 상태, id 순서, 시계를 최초 상태로 되돌린다. */
    suspend fun reset(): JourneyState = mutex.withLock {
        ids.reset()
        clock.reset()
        simulateOffline = false
        val seeded = DemoData.seed(engine, clock)
        _state.value = seeded
        _syncStatus.value = SyncStatus.CONNECTED
        seeded
    }

    override suspend fun createJourney(name: String, destination: Place, me: MemberProfile): SessionResult =
        SessionResult.Success(reset())

    override suspend fun joinJourney(inviteCode: String, me: MemberProfile): SessionResult {
        if (!inviteCode.trim().equals(DemoData.INVITE_CODE, ignoreCase = true)) {
            return SessionResult.Failure(DomainError.invalidInviteCode())
        }
        return SessionResult.Success(reset())
    }

    override suspend fun send(command: JourneyCommand): CommandResult = mutex.withLock {
        val current = _state.value ?: return CommandResult.Failed(DomainError.groupNotFound())
        if (simulateOffline) return CommandResult.Failed(DomainError.network())

        val result = engine.handle(current, command, clock.tick())
        when (result) {
            is EngineResult.Rejected -> return CommandResult.Failed(result.error)
            is EngineResult.Duplicate -> {
                _state.value = result.state
                return CommandResult.Duplicate(result.notice)
            }
            is EngineResult.Applied -> {
                val events = result.events.toMutableList()
                var next = result.state
                // 다른 차량 단말의 자동 반응 (경로 적용 결과 보고 등)
                repeat(MAX_REACTIONS) {
                    val reaction = reactor.react(next, ::nextCommandId) ?: return@repeat
                    val r = engine.handle(next, reaction, clock.tick())
                    if (r is EngineResult.Applied) {
                        next = r.state
                        events += r.events
                    }
                }
                _state.value = next
                return CommandResult.Applied(next, events)
            }
        }
    }

    override suspend fun leave() {
        mutex.withLock {
            _state.value = null
            _syncStatus.value = SyncStatus.IDLE
        }
    }

    private companion object {
        const val MAX_REACTIONS = 8
    }
}

/**
 * 다른 차량(B, C) 단말의 내비게이션 처리를 시뮬레이션한다.
 *
 * - 차량 B: 요청을 받으면 REQUESTED → APPLIED
 * - 차량 C: 첫 번째 합류 계획에서는 운전자 확인 대기(USER_CONFIRMATION_REQUIRED)에 머문다.
 *   데모 패널에서 C의 운전자 확인/실패/응답 없음을 조작할 수 있다.
 *   재조율된 두 번째 계획부터는 B와 같이 적용된다.
 *
 * 투표(동의/거절)는 사람의 결정이므로 여기서 자동으로 처리하지 않고, 데모 장면 또는 패널 조작으로만 일어난다.
 */
class DemoFleetReactor(private val selfId: String) {
    fun react(state: JourneyState, nextId: () -> String): JourneyCommand? {
        val plan = state.activePlan() ?: return null
        val actions = state.navigationActions.filter { it.proposalId == plan.id && it.vehicleId != selfId }
        for (action in actions) {
            when (action.status) {
                NavigationActionStatus.PENDING -> {
                    val waitForDriver = action.vehicleId == DemoData.C && plan.planNumber == 1
                    return JourneyCommand.ReportNavigationResult(
                        commandId = nextId(),
                        actorId = action.vehicleId,
                        actionId = action.id,
                        status = if (waitForDriver) NavigationActionStatus.USER_CONFIRMATION_REQUIRED else NavigationActionStatus.REQUESTED,
                    )
                }
                NavigationActionStatus.REQUESTED -> return JourneyCommand.ReportNavigationResult(
                    commandId = nextId(),
                    actorId = action.vehicleId,
                    actionId = action.id,
                    status = NavigationActionStatus.APPLIED,
                )
                else -> Unit
            }
        }
        return null
    }
}
