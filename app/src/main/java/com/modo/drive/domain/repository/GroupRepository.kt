package com.modo.drive.domain.repository

import com.modo.domain.engine.DomainError
import com.modo.domain.model.JourneyCommand
import com.modo.domain.model.JourneyEvent
import com.modo.domain.model.JourneyState
import com.modo.domain.model.Place
import kotlinx.coroutines.flow.StateFlow

enum class SyncStatus { IDLE, CONNECTING, CONNECTED, OFFLINE }

sealed interface CommandResult {
    data class Applied(val state: JourneyState, val events: List<JourneyEvent>) : CommandResult
    data class Duplicate(val notice: DomainError?) : CommandResult
    data class Failed(val error: DomainError) : CommandResult
}

sealed interface SessionResult {
    data class Success(val state: JourneyState) : SessionResult
    data class Failure(val error: DomainError) : SessionResult
}

data class MemberProfile(
    val vehicleId: String,
    val displayName: String,
    val vehicleLabel: String,
    val passengerCount: Int,
)

/**
 * 그룹 여행 상태의 단일 진입점. RemoteGroupRepository(서버)와 DemoGroupRepository(로컬 시뮬레이션)가
 * 같은 인터페이스와 같은 도메인 엔진 규칙을 따른다.
 */
interface GroupRepository {
    val isSimulated: Boolean
    val state: StateFlow<JourneyState?>
    val syncStatus: StateFlow<SyncStatus>

    suspend fun createJourney(name: String, destination: Place, me: MemberProfile): SessionResult
    suspend fun joinJourney(inviteCode: String, me: MemberProfile): SessionResult
    suspend fun send(command: JourneyCommand): CommandResult
    /** 로컬 세션을 정리한다 (서버의 여행은 EndJourney 명령으로 종료). */
    suspend fun leave()
}
