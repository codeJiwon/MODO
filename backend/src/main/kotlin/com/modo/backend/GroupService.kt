package com.modo.backend

import com.modo.domain.api.CommandOutcome
import com.modo.domain.api.CommandResponse
import com.modo.domain.api.CreateGroupRequest
import com.modo.domain.engine.DomainError
import com.modo.domain.engine.EngineResult
import com.modo.domain.engine.IdGenerator
import com.modo.domain.engine.JourneyEngine
import com.modo.domain.engine.RandomIdGenerator
import com.modo.domain.engine.member
import com.modo.domain.model.ConnectionStatus
import com.modo.domain.model.JourneyCommand
import com.modo.domain.model.JourneyState
import com.modo.domain.model.JourneyStatus
import com.modo.domain.model.VehicleMember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 그룹 상태를 보관하는 In-Memory 서비스. 상태 전이는 앱과 같은 [JourneyEngine]이 담당한다.
 * 운영 환경에서는 저장소를 DB/Redis로 교체해야 한다 (현재는 서버 재시작 시 상태가 사라짐).
 */
class GroupService(
    private val clock: () -> Long = System::currentTimeMillis,
    ids: IdGenerator = RandomIdGenerator,
    private val disconnectAfterMs: Long = 30_000,
) {
    private val engine = JourneyEngine(ids)
    private val groups = ConcurrentHashMap<String, MutableStateFlow<JourneyState>>()
    private val inviteIndex = ConcurrentHashMap<String, String>()
    /** groupId → (memberId → 마지막으로 상태를 조회한 시각) */
    private val presence = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val random = SecureRandom()

    fun create(request: CreateGroupRequest): JourneyState {
        val code = newInviteCode()
        val state = engine.createJourney(
            name = request.name.trim().ifBlank { "MODO 여행" },
            destination = request.destination,
            inviteCode = code,
            creator = VehicleMember(
                id = request.creatorId,
                displayName = request.displayName,
                vehicleLabel = request.vehicleLabel.uppercase(),
                passengerCount = request.passengerCount,
            ),
            now = clock(),
        )
        groups[state.group.id] = MutableStateFlow(state)
        inviteIndex[code] = state.group.id
        touch(state.group.id, request.creatorId)
        return state
    }

    fun state(groupId: String): StateFlow<JourneyState>? = groups[groupId]?.asStateFlow()

    fun findByInvite(code: String): String? = inviteIndex[code.trim().uppercase()]

    fun join(command: JourneyCommand.Join): CommandResponse? {
        val groupId = findByInvite(command.inviteCode) ?: return null
        val response = apply(groupId, command)
        if (response?.outcome != CommandOutcome.REJECTED) touch(groupId, command.actorId)
        return response
    }

    /** 명령을 적용한다. 같은 그룹에 대한 명령은 순서대로 처리된다. */
    fun apply(groupId: String, command: JourneyCommand): CommandResponse? {
        val flow = groups[groupId] ?: return null
        synchronized(flow) {
            val result = engine.handle(flow.value, command, clock())
            flow.value = result.state
            return when (result) {
                is EngineResult.Applied -> CommandResponse(CommandOutcome.APPLIED, result.state, result.events)
                is EngineResult.Duplicate -> CommandResponse(CommandOutcome.DUPLICATE, result.state, error = result.notice)
                is EngineResult.Rejected -> CommandResponse(CommandOutcome.REJECTED, result.state, error = result.error)
            }
        }
    }

    /** 상태 조회(폴링) 시 호출되어 차량의 연결 상태를 갱신한다. */
    fun touch(groupId: String, memberId: String) {
        presence.getOrPut(groupId) { ConcurrentHashMap() }[memberId] = clock()
        val state = groups[groupId]?.value ?: return
        if (state.member(memberId)?.connectionStatus == ConnectionStatus.DISCONNECTED) {
            apply(groupId, JourneyCommand.SetConnection(RandomIdGenerator.next("srv"), "server", memberId, ConnectionStatus.CONNECTED))
        }
    }

    /** 일정 시간 상태를 조회하지 않은 차량을 '연결 끊김'으로 표시한다. 주기적으로 호출된다. */
    fun sweepPresence() {
        val now = clock()
        for ((groupId, flow) in groups) {
            val state = flow.value
            if (state.group.status != JourneyStatus.ACTIVE) continue
            val seen = presence[groupId] ?: continue
            for (member in state.group.members) {
                val last = seen[member.id] ?: continue
                if (now - last > disconnectAfterMs && member.connectionStatus == ConnectionStatus.CONNECTED) {
                    apply(groupId, JourneyCommand.SetConnection(RandomIdGenerator.next("srv"), "server", member.id, ConnectionStatus.DISCONNECTED))
                }
            }
        }
    }

    private fun newInviteCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        while (true) {
            val code = (1..6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            if (!inviteIndex.containsKey(code)) return code
        }
    }

    companion object {
        fun notFound() = DomainError.groupNotFound()
    }
}
