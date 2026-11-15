package com.modo.domain.model

import kotlinx.serialization.Serializable

/** WGS84 좌표. */
@Serializable
data class GeoPoint(val latitude: Double, val longitude: Double)

/** 목적지 또는 합류 장소 후보. */
@Serializable
data class Place(
    val id: String,
    val name: String,
    val address: String,
    val location: GeoPoint,
    /** 화면에 표시할 보조 설명 (예: "영동고속도로 · 강릉방향"). */
    val description: String = "",
)

@Serializable
enum class JourneyStatus { ACTIVE, COMPLETED, ENDED }

@Serializable
enum class MovementStatus { MOVING, STOPPED, UNKNOWN }

/** 차량 단말과 그룹 세션 간 연결 상태. */
@Serializable
enum class ConnectionStatus { CONNECTED, DISCONNECTED }

/** 차량별 내비게이션 상태 요약. 그룹 승인 상태와는 별개로 관리된다. */
@Serializable
enum class MemberNavigationStatus {
    /** 원래 공동 목적지로 안내 중. */
    ORIGINAL_DESTINATION,
    /** 합의된 합류 장소로의 경로 변경이 아직 적용되지 않음. */
    CHANGE_PENDING,
    /** 합류 장소로의 경로 변경이 실제로 적용됨 (내비게이션 결과로 확인됨). */
    RENDEZVOUS_APPLIED,
    /** 경로 변경 요청이 실패함. */
    CHANGE_FAILED,
    /** 결과를 확인하지 못함 (응답 시간 초과 등). */
    UNKNOWN,
}

@Serializable
data class VehicleMember(
    val id: String,
    val displayName: String,
    /** "A", "B", "C" 와 같은 차량 식별 라벨. */
    val vehicleLabel: String,
    val passengerCount: Int = 1,
    val location: GeoPoint? = null,
    val movementStatus: MovementStatus = MovementStatus.UNKNOWN,
    /** 참가자의 명시적인 동의가 있을 때만 true. 기본값은 false. */
    val locationSharingEnabled: Boolean = false,
    val lastUpdatedAt: Long? = null,
    val navigationStatus: MemberNavigationStatus = MemberNavigationStatus.ORIGINAL_DESTINATION,
    val connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTED,
    /** 사용자가 직접 확인한 최종 목적지 도착 여부 (GPS만으로 판단하지 않음). */
    val arrivalConfirmed: Boolean = false,
)

@Serializable
data class JourneyGroup(
    val id: String,
    val name: String,
    val destination: Place,
    val inviteCode: String,
    val members: List<VehicleMember>,
    val status: JourneyStatus,
    val createdAt: Long,
    val endedAt: Long? = null,
)

@Serializable
enum class MobilityRequestType {
    REST_REQUEST,
    RENDEZVOUS_REQUEST,
    DESTINATION_CHANGE,
    STATUS_REQUEST,
    MANUAL_RECOVERY_REQUEST,
}

@Serializable
enum class MobilityRequestStatus {
    /** 그룹에 공유되었고 후속 조치를 기다림. */
    OPEN,
    /** 합류 제안 등 후속 조치가 진행 중. */
    IN_PROGRESS,
    /** 후속 조치가 완료됨. */
    RESOLVED,
    /** 요청자가 취소했거나 세션 종료로 닫힘. */
    CLOSED,
}

@Serializable
data class MobilityRequest(
    val id: String,
    val requesterId: String,
    val type: MobilityRequestType,
    val originalText: String,
    val createdAt: Long,
    val status: MobilityRequestStatus,
)

@Serializable
enum class ApprovalDecision { PENDING, APPROVED, REJECTED }

@Serializable
enum class ProposalStatus { PROPOSED, VOTING, APPROVED, REJECTED, CANCELLED }

@Serializable
data class RendezvousProposal(
    val id: String,
    val groupId: String,
    val proposedBy: String,
    val location: Place,
    /** vehicleId → 응답. 모든 차량이 APPROVED일 때만 APPROVED가 된다. */
    val approvals: Map<String, ApprovalDecision>,
    val status: ProposalStatus,
    val createdAt: Long,
    /** 그룹 안에서 몇 번째 합류 계획인지 (1, 2, ...). 이전 계획과 구분하는 데 사용. */
    val planNumber: Int,
    val requestId: String? = null,
    val recoverySessionId: String? = null,
    /** 재조율로 대체한 이전 제안 id. */
    val replacesProposalId: String? = null,
    /** 이 제안을 대체한 새 제안 id. 값이 있으면 더 이상 현재 계획이 아니다. */
    val supersededByProposalId: String? = null,
    val decidedAt: Long? = null,
    /** 합류 장소 도착을 사용자가 확인한 차량 id. */
    val arrivedVehicleIds: Set<String> = emptySet(),
)

@Serializable
enum class NavigationActionStatus {
    PENDING,
    USER_CONFIRMATION_REQUIRED,
    REQUESTED,
    APPLIED,
    FAILED,
    UNKNOWN,
}

@Serializable
data class NavigationAction(
    val id: String,
    val proposalId: String,
    val vehicleId: String,
    val destination: Place,
    val status: NavigationActionStatus,
    val errorMessage: String? = null,
    val requestedAt: Long,
    val completedAt: Long? = null,
    val attempt: Int = 1,
)

@Serializable
enum class RecoveryReason {
    NAVIGATION_NOT_APPLIED,
    MISSED_RENDEZVOUS,
    ROUTE_DEVIATION,
    MANUAL_HELP_REQUEST,
}

@Serializable
enum class RecoveryStatus {
    /** 재조율이 필요함을 그룹에 알림. */
    OPEN,
    /** 새로운 합류 제안이 투표 중. */
    REPLANNING,
    /** 새로운 합류 계획이 확정됨. */
    RESOLVED,
    /** 그룹이 현재 계획을 유지하기로 함. */
    KEPT_CURRENT_PLAN,
    /** 나중에 확인하기로 보류함. */
    DEFERRED,
}

@Serializable
data class RecoverySession(
    val id: String,
    val groupId: String,
    val affectedVehicleIds: List<String>,
    val reason: RecoveryReason,
    /** 시스템이 추천한 새로운 합류 장소 (아직 제안 전일 수 있음). */
    val proposedMeetingPoint: Place?,
    val status: RecoveryStatus,
    val createdAt: Long,
    val previousProposalId: String? = null,
    val newProposalId: String? = null,
    val resolvedAt: Long? = null,
)

/** 그룹 타임라인에 기록되는 사건. UI 알림과 감사 로그 용도. */
@Serializable
data class JourneyEvent(
    val sequence: Long,
    val at: Long,
    val actorId: String?,
    val kind: JourneyEventKind,
    val message: String,
    val refId: String? = null,
)

@Serializable
enum class JourneyEventKind {
    GROUP_CREATED,
    MEMBER_JOINED,
    LOCATION_SHARING_CHANGED,
    MEMBER_CONNECTION_CHANGED,
    REQUEST_CREATED,
    PROPOSAL_CREATED,
    PROPOSAL_RESPONSE,
    PROPOSAL_APPROVED,
    PROPOSAL_REJECTED,
    PROPOSAL_CANCELLED,
    NAVIGATION_DISPATCHED,
    NAVIGATION_UPDATED,
    RECOVERY_OPENED,
    RECOVERY_UPDATED,
    RENDEZVOUS_ARRIVAL,
    DESTINATION_ARRIVAL,
    JOURNEY_COMPLETED,
    JOURNEY_ENDED,
}

/**
 * 하나의 그룹 여행에 대한 전체 상태.
 * 서버(backend), 데모 저장소, 앱이 모두 같은 구조를 사용한다.
 */
@Serializable
data class JourneyState(
    val group: JourneyGroup,
    val requests: List<MobilityRequest> = emptyList(),
    val proposals: List<RendezvousProposal> = emptyList(),
    val navigationActions: List<NavigationAction> = emptyList(),
    val recoverySessions: List<RecoverySession> = emptyList(),
    val events: List<JourneyEvent> = emptyList(),
    /** 상태가 바뀔 때마다 1씩 증가. 동기화/폴링에 사용. */
    val version: Long = 0,
    /** 이미 처리한 명령 id (중복 이벤트 처리용). 최근 값만 유지. */
    val processedCommandIds: List<String> = emptyList(),
)
