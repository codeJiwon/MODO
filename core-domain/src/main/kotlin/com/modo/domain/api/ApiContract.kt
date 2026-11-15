package com.modo.domain.api

import com.modo.domain.engine.DomainError
import com.modo.domain.model.JourneyCommand
import com.modo.domain.model.JourneyEvent
import com.modo.domain.model.JourneyState
import com.modo.domain.model.Place
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 앱(RemoteGroupRepository)과 그룹 동기화 서버(backend)가 공유하는 REST 계약.
 *
 * POST /api/groups                      그룹 생성
 * POST /api/groups/join                 초대 코드로 참여
 * GET  /api/groups/{id}                 그룹 상태 조회 (참가자 포함)
 * GET  /api/groups/{id}/state?since=v&member=m  상태 동기화 (폴링, 변경 없으면 204). member는 연결 상태 감지용
 * POST /api/groups/{id}/commands        모든 상태 변경 명령 (JourneyCommand)
 * WS   /api/groups/{id}/events          상태 변경 시 JourneyState 푸시
 */
object ApiPaths {
    const val GROUPS = "/api/groups"
    const val JOIN = "/api/groups/join"
    fun group(id: String) = "/api/groups/$id"
    fun state(id: String, since: Long, memberId: String) = "/api/groups/$id/state?since=$since&member=$memberId"
    fun commands(id: String) = "/api/groups/$id/commands"
    fun events(id: String) = "/api/groups/$id/events"
}

@Serializable
data class CreateGroupRequest(
    val name: String,
    val destination: Place,
    val creatorId: String,
    val displayName: String,
    val vehicleLabel: String,
    val passengerCount: Int = 1,
)

@Serializable
data class JoinGroupRequest(val command: JourneyCommand.Join)

@Serializable
enum class CommandOutcome { APPLIED, DUPLICATE, REJECTED }

@Serializable
data class CommandResponse(
    val outcome: CommandOutcome,
    val state: JourneyState,
    val events: List<JourneyEvent> = emptyList(),
    val error: DomainError? = null,
)

@Serializable
data class ErrorResponse(val error: DomainError)

val ModoJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "@type"
}
