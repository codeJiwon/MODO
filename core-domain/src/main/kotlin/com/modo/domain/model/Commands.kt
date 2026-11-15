package com.modo.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 그룹 상태를 바꾸는 모든 요청.
 *
 * [commandId]는 호출자가 생성하는 고유 값이며, 같은 id의 명령이 다시 도착하면
 * 엔진은 상태를 바꾸지 않고 중복으로 처리한다 (네트워크 재전송, 중복 탭 대비).
 */
@Serializable
sealed interface JourneyCommand {
    val commandId: String
    val actorId: String

    @Serializable
    @SerialName("join")
    data class Join(
        override val commandId: String,
        override val actorId: String,
        val inviteCode: String,
        val displayName: String,
        val vehicleLabel: String,
        val passengerCount: Int = 1,
    ) : JourneyCommand

    @Serializable
    @SerialName("set_location_sharing")
    data class SetLocationSharing(
        override val commandId: String,
        override val actorId: String,
        val enabled: Boolean,
    ) : JourneyCommand

    @Serializable
    @SerialName("update_location")
    data class UpdateLocation(
        override val commandId: String,
        override val actorId: String,
        val location: GeoPoint,
        val movementStatus: MovementStatus,
        val measuredAt: Long,
    ) : JourneyCommand

    @Serializable
    @SerialName("set_connection")
    data class SetConnection(
        override val commandId: String,
        override val actorId: String,
        val vehicleId: String,
        val status: ConnectionStatus,
    ) : JourneyCommand

    @Serializable
    @SerialName("create_request")
    data class CreateRequest(
        override val commandId: String,
        override val actorId: String,
        val originalText: String,
        /** null이면 [com.modo.domain.engine.IntentClassifier]로 분류한다. */
        val type: MobilityRequestType? = null,
    ) : JourneyCommand

    @Serializable
    @SerialName("propose_rendezvous")
    data class ProposeRendezvous(
        override val commandId: String,
        override val actorId: String,
        val place: Place,
        val requestId: String? = null,
        val recoverySessionId: String? = null,
    ) : JourneyCommand

    @Serializable
    @SerialName("respond_proposal")
    data class RespondToProposal(
        override val commandId: String,
        override val actorId: String,
        val proposalId: String,
        val approve: Boolean,
    ) : JourneyCommand

    @Serializable
    @SerialName("cancel_proposal")
    data class CancelProposal(
        override val commandId: String,
        override val actorId: String,
        val proposalId: String,
    ) : JourneyCommand

    /** 승인된 합류 장소를 각 차량 내비게이션에 적용하도록 요청한다. */
    @Serializable
    @SerialName("dispatch_navigation")
    data class DispatchNavigation(
        override val commandId: String,
        override val actorId: String,
        val proposalId: String,
    ) : JourneyCommand

    /** 차량 단말이 자기 차량의 내비게이션 처리 결과를 보고한다. */
    @Serializable
    @SerialName("report_navigation")
    data class ReportNavigationResult(
        override val commandId: String,
        override val actorId: String,
        val actionId: String,
        val status: NavigationActionStatus,
        val errorMessage: String? = null,
    ) : JourneyCommand

    @Serializable
    @SerialName("retry_navigation")
    data class RetryNavigation(
        override val commandId: String,
        override val actorId: String,
        val actionId: String,
    ) : JourneyCommand

    /** 합류 실패, 경로 미적용 등 재조율이 필요한 상황을 보고한다. */
    @Serializable
    @SerialName("open_recovery")
    data class OpenRecovery(
        override val commandId: String,
        override val actorId: String,
        val affectedVehicleIds: List<String>,
        val reason: RecoveryReason,
        val suggestedMeetingPoint: Place? = null,
    ) : JourneyCommand

    @Serializable
    @SerialName("keep_plan")
    data class KeepCurrentPlan(
        override val commandId: String,
        override val actorId: String,
        val recoverySessionId: String,
    ) : JourneyCommand

    @Serializable
    @SerialName("defer_recovery")
    data class DeferRecovery(
        override val commandId: String,
        override val actorId: String,
        val recoverySessionId: String,
    ) : JourneyCommand

    /** 사용자가 합류 장소 도착을 직접 확인한다. */
    @Serializable
    @SerialName("confirm_rendezvous_arrival")
    data class ConfirmRendezvousArrival(
        override val commandId: String,
        override val actorId: String,
        val proposalId: String,
    ) : JourneyCommand

    /** 사용자가 최종 목적지 도착을 직접 확인한다. */
    @Serializable
    @SerialName("confirm_destination_arrival")
    data class ConfirmDestinationArrival(
        override val commandId: String,
        override val actorId: String,
    ) : JourneyCommand

    @Serializable
    @SerialName("end_journey")
    data class EndJourney(
        override val commandId: String,
        override val actorId: String,
    ) : JourneyCommand
}
