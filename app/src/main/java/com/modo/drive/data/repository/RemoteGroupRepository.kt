package com.modo.drive.data.repository

import com.modo.domain.api.ApiPaths
import com.modo.domain.api.CommandOutcome
import com.modo.domain.api.CommandResponse
import com.modo.domain.api.CreateGroupRequest
import com.modo.domain.api.ErrorResponse
import com.modo.domain.api.JoinGroupRequest
import com.modo.domain.api.ModoJson
import com.modo.domain.engine.DomainError
import com.modo.domain.engine.RandomIdGenerator
import com.modo.domain.model.JourneyCommand
import com.modo.domain.model.JourneyState
import com.modo.domain.model.Place
import com.modo.drive.data.remote.HttpResponse
import com.modo.drive.data.remote.HttpTransport
import com.modo.drive.domain.repository.CommandResult
import com.modo.drive.domain.repository.GroupRepository
import com.modo.drive.domain.repository.MemberProfile
import com.modo.drive.domain.repository.SessionResult
import com.modo.drive.domain.repository.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 그룹 동기화 서버(backend/)와 통신하는 저장소. 상태 변경은 REST 명령으로 보내고,
 * 다른 차량의 변경은 상태 폴링으로 받는다 (서버는 WebSocket 푸시도 제공).
 *
 * 네트워크 오류는 성공으로 처리하지 않으며, 명령은 같은 commandId로 재전송해도 서버에서 한 번만 반영된다.
 */
class RemoteGroupRepository(
    private val transport: HttpTransport,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 2_000,
    private val maxBackoffMs: Long = 10_000,
) : GroupRepository {

    override val isSimulated: Boolean = false

    private val _state = MutableStateFlow<JourneyState?>(null)
    override val state: StateFlow<JourneyState?> = _state.asStateFlow()
    private val _syncStatus = MutableStateFlow(SyncStatus.IDLE)
    override val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private var memberId: String? = null
    private var pollJob: Job? = null

    override suspend fun createJourney(name: String, destination: Place, me: MemberProfile): SessionResult {
        val body = ModoJson.encodeToString(
            CreateGroupRequest(name, destination, me.vehicleId, me.displayName, me.vehicleLabel, me.passengerCount),
        )
        return session(me, call("POST", ApiPaths.GROUPS, body))
    }

    override suspend fun joinJourney(inviteCode: String, me: MemberProfile): SessionResult {
        val command = JourneyCommand.Join(
            commandId = RandomIdGenerator.next("join"),
            actorId = me.vehicleId,
            inviteCode = inviteCode.trim(),
            displayName = me.displayName,
            vehicleLabel = me.vehicleLabel,
            passengerCount = me.passengerCount,
        )
        return session(me, call("POST", ApiPaths.JOIN, ModoJson.encodeToString(JoinGroupRequest(command))))
    }

    private fun session(me: MemberProfile, result: CallResult): SessionResult = when (result) {
        is CallResult.Failure -> SessionResult.Failure(result.error)
        is CallResult.Success -> {
            val response = decodeCommandResponse(result.response)
            when {
                response == null -> SessionResult.Failure(DomainError.server("응답 형식 오류"))
                response.outcome == CommandOutcome.REJECTED ->
                    SessionResult.Failure(response.error ?: DomainError.server())
                else -> {
                    memberId = me.vehicleId
                    _state.value = response.state
                    _syncStatus.value = SyncStatus.CONNECTED
                    startPolling(response.state.group.id)
                    SessionResult.Success(response.state)
                }
            }
        }
    }

    override suspend fun send(command: JourneyCommand): CommandResult {
        val groupId = _state.value?.group?.id ?: return CommandResult.Failed(DomainError.groupNotFound())
        val body = ModoJson.encodeToString(JourneyCommand.serializer(), command)
        return when (val result = call("POST", ApiPaths.commands(groupId), body)) {
            is CallResult.Failure -> CommandResult.Failed(result.error)
            is CallResult.Success -> {
                val response = decodeCommandResponse(result.response)
                    ?: return CommandResult.Failed(DomainError.server("응답 형식 오류"))
                mergeState(response.state)
                when (response.outcome) {
                    CommandOutcome.APPLIED -> CommandResult.Applied(response.state, response.events)
                    CommandOutcome.DUPLICATE -> CommandResult.Duplicate(response.error)
                    CommandOutcome.REJECTED -> CommandResult.Failed(response.error ?: DomainError.server())
                }
            }
        }
    }

    override suspend fun leave() {
        pollJob?.cancel()
        pollJob = null
        memberId = null
        _state.value = null
        _syncStatus.value = SyncStatus.IDLE
    }

    /** 오래된 상태로 덮어쓰지 않도록 버전을 비교한다. */
    private fun mergeState(incoming: JourneyState) {
        val current = _state.value
        if (current == null || current.group.id != incoming.group.id || incoming.version >= current.version) {
            _state.value = incoming
        }
    }

    private fun startPolling(groupId: String) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var backoff = pollIntervalMs
            while (isActive) {
                delay(backoff)
                val since = _state.value?.version ?: 0
                val member = memberId ?: break
                when (val result = call("GET", ApiPaths.state(groupId, since, member), null)) {
                    is CallResult.Success -> {
                        backoff = pollIntervalMs
                        if (result.response.code == 200) {
                            runCatching { ModoJson.decodeFromString(JourneyState.serializer(), result.response.body) }
                                .getOrNull()?.let(::mergeState)
                        }
                    }
                    is CallResult.Failure -> backoff = (backoff * 2).coerceAtMost(maxBackoffMs)
                }
            }
        }
    }

    private sealed interface CallResult {
        data class Success(val response: HttpResponse) : CallResult
        data class Failure(val error: DomainError) : CallResult
    }

    private suspend fun call(method: String, path: String, body: String?): CallResult {
        val response = try {
            transport.request(method, path, body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            _syncStatus.value = SyncStatus.OFFLINE
            return CallResult.Failure(DomainError.timeout())
        } catch (e: IOException) {
            _syncStatus.value = SyncStatus.OFFLINE
            return CallResult.Failure(DomainError.network())
        }
        if (_state.value != null || response.code < 500) _syncStatus.value = SyncStatus.CONNECTED
        return when {
            response.code in 200..299 -> CallResult.Success(response)
            response.code in 400..499 -> {
                // 규칙 위반(409/422)은 CommandResponse로, 그 밖의 클라이언트 오류는 ErrorResponse로 내려온다.
                decodeCommandResponse(response)?.let { return CallResult.Success(response) }
                val error = runCatching { ModoJson.decodeFromString(ErrorResponse.serializer(), response.body).error }.getOrNull()
                CallResult.Failure(error ?: DomainError.server("HTTP ${response.code}"))
            }
            else -> {
                _syncStatus.value = SyncStatus.OFFLINE
                CallResult.Failure(DomainError.server("HTTP ${response.code}"))
            }
        }
    }

    private fun decodeCommandResponse(response: HttpResponse): CommandResponse? = try {
        ModoJson.decodeFromString(CommandResponse.serializer(), response.body)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
