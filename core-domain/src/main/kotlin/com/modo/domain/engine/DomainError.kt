package com.modo.domain.engine

import kotlinx.serialization.Serializable

/**
 * 도메인 규칙 위반 또는 처리 실패. [message]는 사용자에게 그대로 보여줄 수 있는 한국어 문장이다.
 */
@Serializable
data class DomainError(val code: Code, val message: String) {

    @Serializable
    enum class Code {
        GROUP_NOT_FOUND,
        INVALID_INVITE_CODE,
        ALREADY_JOINED,
        VEHICLE_LABEL_TAKEN,
        GROUP_FULL,
        UNKNOWN_MEMBER,
        SESSION_ENDED,
        UNRECOGNIZED_COMMAND,
        DUPLICATE_REQUEST,
        PROPOSAL_NOT_FOUND,
        PROPOSAL_ALREADY_ACTIVE,
        PROPOSAL_CLOSED,
        ALREADY_RESPONDED,
        NOT_A_VOTER,
        NOT_PROPOSER,
        NOT_APPROVED,
        APPROVAL_INCOMPLETE,
        ALREADY_DISPATCHED,
        ACTION_NOT_FOUND,
        NOT_ACTION_OWNER,
        INVALID_TRANSITION,
        NO_ACTIVE_PLAN,
        RECOVERY_NOT_FOUND,
        RECOVERY_CLOSED,
        RECOVERY_ALREADY_OPEN,
        LOCATION_SHARING_OFF,
        NOT_ALL_ARRIVED,
        NETWORK,
        TIMEOUT,
        SERVER,
    }

    companion object {
        fun groupNotFound() = DomainError(Code.GROUP_NOT_FOUND, "여행 그룹을 찾을 수 없어요.")
        fun invalidInviteCode() = DomainError(Code.INVALID_INVITE_CODE, "초대 코드가 올바르지 않아요. 코드를 다시 확인해 주세요.")
        fun alreadyJoined() = DomainError(Code.ALREADY_JOINED, "이미 이 여행에 참여하고 있어요.")
        fun vehicleLabelTaken(label: String) = DomainError(Code.VEHICLE_LABEL_TAKEN, "차량 $label 이름은 이미 사용 중이에요.")
        fun groupFull(max: Int) = DomainError(Code.GROUP_FULL, "한 여행에는 최대 ${max}대까지 참여할 수 있어요.")
        fun unknownMember() = DomainError(Code.UNKNOWN_MEMBER, "이 여행의 참가 차량이 아니에요.")
        fun sessionEnded() = DomainError(Code.SESSION_ENDED, "이미 종료된 여행이에요. 새로운 요청을 처리할 수 없어요.")
        fun unrecognized(text: String) = DomainError(Code.UNRECOGNIZED_COMMAND, "\"$text\"\n어떤 요청인지 이해하지 못했어요. 다시 말씀해 주세요.")
        fun duplicateRequest() = DomainError(Code.DUPLICATE_REQUEST, "같은 요청이 이미 그룹에 공유되어 있어요.")
        fun proposalNotFound() = DomainError(Code.PROPOSAL_NOT_FOUND, "합류 제안을 찾을 수 없어요.")
        fun proposalAlreadyActive() = DomainError(Code.PROPOSAL_ALREADY_ACTIVE, "진행 중인 합류 제안이 있어요. 먼저 결정을 마쳐 주세요.")
        fun proposalClosed() = DomainError(Code.PROPOSAL_CLOSED, "이미 결정이 끝난 제안이에요. 늦게 도착한 응답은 반영하지 않았어요.")
        fun alreadyResponded() = DomainError(Code.ALREADY_RESPONDED, "이미 이 제안에 응답했어요.")
        fun notAVoter() = DomainError(Code.NOT_A_VOTER, "이 제안에 응답할 수 있는 차량이 아니에요.")
        fun notProposer() = DomainError(Code.NOT_PROPOSER, "제안한 차량만 취소할 수 있어요.")
        fun notApproved() = DomainError(Code.NOT_APPROVED, "모든 차량이 동의하기 전에는 경로를 변경할 수 없어요.")
        fun approvalIncomplete(labels: List<String>) = DomainError(
            Code.APPROVAL_INCOMPLETE,
            "아직 응답하지 않은 차량이 있어요: ${labels.joinToString(", ") { "차량 $it" }}",
        )
        fun alreadyDispatched() = DomainError(Code.ALREADY_DISPATCHED, "이미 각 차량에 경로 변경을 요청했어요.")
        fun actionNotFound() = DomainError(Code.ACTION_NOT_FOUND, "경로 변경 요청을 찾을 수 없어요.")
        fun notActionOwner() = DomainError(Code.NOT_ACTION_OWNER, "다른 차량의 경로 적용 결과는 보고할 수 없어요.")
        fun invalidTransition(from: String, to: String) =
            DomainError(Code.INVALID_TRANSITION, "현재 상태($from)에서는 $to 상태로 바꿀 수 없어요.")
        fun noActivePlan() = DomainError(Code.NO_ACTIVE_PLAN, "확정된 합류 계획이 없어요.")
        fun recoveryNotFound() = DomainError(Code.RECOVERY_NOT_FOUND, "재조율 요청을 찾을 수 없어요.")
        fun recoveryClosed() = DomainError(Code.RECOVERY_CLOSED, "이미 처리된 재조율 요청이에요.")
        fun recoveryAlreadyOpen() = DomainError(Code.RECOVERY_ALREADY_OPEN, "같은 차량에 대한 재조율이 이미 진행 중이에요.")
        fun locationSharingOff() = DomainError(Code.LOCATION_SHARING_OFF, "위치 공유에 동의하지 않아 위치를 보낼 수 없어요.")
        fun notAllArrived(labels: List<String>) = DomainError(
            Code.NOT_ALL_ARRIVED,
            "아직 도착을 확인하지 않은 차량이 있어요: ${labels.joinToString(", ") { "차량 $it" }}",
        )
        fun network() = DomainError(Code.NETWORK, "네트워크에 연결할 수 없어요. 연결 상태를 확인한 뒤 다시 시도해 주세요.")
        fun timeout() = DomainError(Code.TIMEOUT, "응답 시간이 초과되었어요. 잠시 후 다시 시도해 주세요.")
        fun server(detail: String? = null) =
            DomainError(Code.SERVER, "그룹 서버에서 오류가 발생했어요." + (detail?.let { " ($it)" } ?: ""))
    }
}
