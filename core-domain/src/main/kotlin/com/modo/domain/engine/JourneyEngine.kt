package com.modo.domain.engine

import com.modo.domain.model.ApprovalDecision
import com.modo.domain.model.ConnectionStatus
import com.modo.domain.model.JourneyCommand
import com.modo.domain.model.JourneyEvent
import com.modo.domain.model.JourneyEventKind
import com.modo.domain.model.JourneyGroup
import com.modo.domain.model.JourneyState
import com.modo.domain.model.JourneyStatus
import com.modo.domain.model.MemberNavigationStatus
import com.modo.domain.model.MobilityRequestStatus
import com.modo.domain.model.MobilityRequest
import com.modo.domain.model.MobilityRequestType
import com.modo.domain.model.NavigationAction
import com.modo.domain.model.NavigationActionStatus
import com.modo.domain.model.Place
import com.modo.domain.model.ProposalStatus
import com.modo.domain.model.RecoveryReason
import com.modo.domain.model.RecoverySession
import com.modo.domain.model.RecoveryStatus
import com.modo.domain.model.RendezvousProposal
import com.modo.domain.model.VehicleMember

/** 명령 처리 결과. */
sealed interface EngineResult {
    val state: JourneyState

    /** 상태가 바뀜. [events]는 이번 명령으로 새로 기록된 사건. */
    data class Applied(override val state: JourneyState, val events: List<JourneyEvent>) : EngineResult

    /** 중복 명령/중복 응답이라 상태를 바꾸지 않음. [notice]는 사용자 안내용. */
    data class Duplicate(override val state: JourneyState, val notice: DomainError? = null) : EngineResult

    /** 규칙 위반으로 거절됨. 상태는 그대로. */
    data class Rejected(override val state: JourneyState, val error: DomainError) : EngineResult
}

/**
 * 그룹 여행의 상태 전이를 담당하는 순수 함수형 엔진.
 *
 * - 네트워크/SDK/시간에 의존하지 않는다. 현재 시각은 [handle]의 인자로 받는다.
 * - 서버(backend), 데모 저장소, 단위 테스트가 모두 이 엔진을 사용하므로
 *   Demo Mode와 실제 서버 모드의 비즈니스 규칙이 동일하다.
 * - "그룹 승인 완료(ProposalStatus.APPROVED)"와 "차량별 경로 적용 완료(NavigationActionStatus.APPLIED)"는
 *   서로 다른 엔티티로 관리하며, 승인되었다고 해서 경로가 적용된 것으로 간주하지 않는다.
 */
class JourneyEngine(private val ids: IdGenerator) {

    fun createJourney(
        groupId: String = ids.next("grp"),
        name: String,
        destination: Place,
        inviteCode: String,
        creator: VehicleMember,
        now: Long,
    ): JourneyState {
        val group = JourneyGroup(
            id = groupId,
            name = name,
            destination = destination,
            inviteCode = inviteCode.uppercase(),
            members = listOf(creator),
            status = JourneyStatus.ACTIVE,
            createdAt = now,
        )
        val state = JourneyState(group = group)
        return state.withEvent(now, creator.id, JourneyEventKind.GROUP_CREATED, "'${group.name}' 여행을 시작했어요.", group.id)
            .copy(version = 1)
    }

    fun handle(state: JourneyState, command: JourneyCommand, now: Long): EngineResult {
        if (command.commandId in state.processedCommandIds) return EngineResult.Duplicate(state)
        if (state.group.status != JourneyStatus.ACTIVE) return EngineResult.Rejected(state, DomainError.sessionEnded())
        if (command !is JourneyCommand.Join && command !is JourneyCommand.SetConnection &&
            state.member(command.actorId) == null
        ) {
            return EngineResult.Rejected(state, DomainError.unknownMember())
        }

        val outcome: Outcome = when (command) {
            is JourneyCommand.Join -> join(state, command, now)
            is JourneyCommand.SetLocationSharing -> setLocationSharing(state, command, now)
            is JourneyCommand.UpdateLocation -> updateLocation(state, command)
            is JourneyCommand.SetConnection -> setConnection(state, command, now)
            is JourneyCommand.CreateRequest -> createRequest(state, command, now)
            is JourneyCommand.ProposeRendezvous -> propose(state, command, now)
            is JourneyCommand.RespondToProposal -> respond(state, command, now)
            is JourneyCommand.CancelProposal -> cancel(state, command, now)
            is JourneyCommand.DispatchNavigation -> dispatch(state, command, now)
            is JourneyCommand.ReportNavigationResult -> reportNavigation(state, command, now)
            is JourneyCommand.RetryNavigation -> retryNavigation(state, command, now)
            is JourneyCommand.OpenRecovery -> openRecovery(state, command, now)
            is JourneyCommand.KeepCurrentPlan -> closeRecovery(state, command.recoverySessionId, command.actorId, keep = true, now)
            is JourneyCommand.DeferRecovery -> closeRecovery(state, command.recoverySessionId, command.actorId, keep = false, now)
            is JourneyCommand.ConfirmRendezvousArrival -> confirmRendezvousArrival(state, command, now)
            is JourneyCommand.ConfirmDestinationArrival -> confirmDestinationArrival(state, command, now)
            is JourneyCommand.EndJourney -> endJourney(state, command, now)
        }

        return when (outcome) {
            is Outcome.Error -> EngineResult.Rejected(state, outcome.error)
            is Outcome.NoChange -> EngineResult.Duplicate(state.markProcessed(command.commandId), outcome.notice)
            is Outcome.Changed -> {
                val previousSequence = state.events.lastOrNull()?.sequence ?: 0
                val next = normalize(outcome.state)
                    .markProcessed(command.commandId)
                    .copy(version = state.version + 1)
                EngineResult.Applied(next, next.events.filter { it.sequence > previousSequence })
            }
        }
    }

    // region commands

    private fun join(state: JourneyState, c: JourneyCommand.Join, now: Long): Outcome {
        val group = state.group
        if (!group.inviteCode.equals(c.inviteCode.trim(), ignoreCase = true)) return Outcome.Error(DomainError.invalidInviteCode())
        if (state.member(c.actorId) != null) return Outcome.Error(DomainError.alreadyJoined())
        if (group.members.any { it.vehicleLabel.equals(c.vehicleLabel, ignoreCase = true) }) {
            return Outcome.Error(DomainError.vehicleLabelTaken(c.vehicleLabel))
        }
        if (group.members.size >= MAX_VEHICLES) return Outcome.Error(DomainError.groupFull(MAX_VEHICLES))
        val member = VehicleMember(
            id = c.actorId,
            displayName = c.displayName,
            vehicleLabel = c.vehicleLabel.uppercase(),
            passengerCount = c.passengerCount,
        )
        return Outcome.Changed(
            state.copy(group = group.copy(members = group.members + member))
                .withEvent(now, c.actorId, JourneyEventKind.MEMBER_JOINED, "차량 ${member.vehicleLabel}가 여행에 참여했어요.", member.id),
        )
    }

    private fun setLocationSharing(state: JourneyState, c: JourneyCommand.SetLocationSharing, now: Long): Outcome {
        val member = state.member(c.actorId)!!
        if (member.locationSharingEnabled == c.enabled) return Outcome.NoChange()
        val updated = if (c.enabled) {
            member.copy(locationSharingEnabled = true)
        } else {
            // 공유를 중단하면 마지막 위치도 그룹에서 제거한다.
            member.copy(locationSharingEnabled = false, location = null, lastUpdatedAt = null)
        }
        val message = if (c.enabled) "차량 ${member.vehicleLabel}가 위치 공유에 동의했어요." else "차량 ${member.vehicleLabel}가 위치 공유를 중단했어요."
        return Outcome.Changed(
            state.replaceMember(updated).withEvent(now, c.actorId, JourneyEventKind.LOCATION_SHARING_CHANGED, message, member.id),
        )
    }

    private fun updateLocation(state: JourneyState, c: JourneyCommand.UpdateLocation): Outcome {
        val member = state.member(c.actorId)!!
        if (!member.locationSharingEnabled) return Outcome.Error(DomainError.locationSharingOff())
        // 순서가 뒤바뀌어 도착한 오래된 위치는 무시한다.
        if (member.lastUpdatedAt != null && c.measuredAt <= member.lastUpdatedAt) return Outcome.NoChange()
        return Outcome.Changed(
            state.replaceMember(
                member.copy(location = c.location, movementStatus = c.movementStatus, lastUpdatedAt = c.measuredAt),
            ),
        )
    }

    private fun setConnection(state: JourneyState, c: JourneyCommand.SetConnection, now: Long): Outcome {
        val member = state.member(c.vehicleId) ?: return Outcome.Error(DomainError.unknownMember())
        if (member.connectionStatus == c.status) return Outcome.NoChange()
        val message = if (c.status == ConnectionStatus.CONNECTED) {
            "차량 ${member.vehicleLabel}와 다시 연결되었어요."
        } else {
            "차량 ${member.vehicleLabel}와 연결이 끊겼어요. 마지막으로 받은 정보를 표시해요."
        }
        return Outcome.Changed(
            state.replaceMember(member.copy(connectionStatus = c.status))
                .withEvent(now, c.actorId, JourneyEventKind.MEMBER_CONNECTION_CHANGED, message, member.id),
        )
    }

    private fun createRequest(state: JourneyState, c: JourneyCommand.CreateRequest, now: Long): Outcome {
        val requester = state.member(c.actorId)!!
        val type = c.type ?: IntentClassifier.classify(c.originalText)
            ?: return Outcome.Error(DomainError.unrecognized(c.originalText.trim()))
        val duplicate = state.requests.any {
            it.requesterId == c.actorId && it.type == type &&
                (it.status == MobilityRequestStatus.OPEN || it.status == MobilityRequestStatus.IN_PROGRESS)
        }
        if (duplicate) return Outcome.Error(DomainError.duplicateRequest())

        val request = MobilityRequest(
            id = ids.next("req"),
            requesterId = c.actorId,
            type = type,
            originalText = c.originalText.trim(),
            createdAt = now,
            status = MobilityRequestStatus.OPEN,
        )
        val label = requester.vehicleLabel
        val message = when (type) {
            MobilityRequestType.REST_REQUEST -> "차량 ${label}가 휴식을 요청했어요."
            MobilityRequestType.RENDEZVOUS_REQUEST -> "차량 ${label}가 합류를 요청했어요."
            MobilityRequestType.DESTINATION_CHANGE -> "차량 ${label}가 목적지 변경을 요청했어요."
            MobilityRequestType.STATUS_REQUEST -> "차량 ${label}가 그룹 상태를 확인했어요."
            MobilityRequestType.MANUAL_RECOVERY_REQUEST -> "차량 ${label}가 도움을 요청했어요."
        }
        var next = state.copy(requests = state.requests + request)
            .withEvent(now, c.actorId, JourneyEventKind.REQUEST_CREATED, message, request.id)

        // 상태 확인 요청은 정보 제공으로 바로 처리된다.
        if (type == MobilityRequestType.STATUS_REQUEST) {
            next = next.updateRequest(request.id) { it.copy(status = MobilityRequestStatus.RESOLVED) }
        }
        // 도움 요청은 수동 재조율 세션을 함께 연다 (이미 열려 있으면 생략).
        if (type == MobilityRequestType.MANUAL_RECOVERY_REQUEST && state.openRecoveryFor(c.actorId) == null) {
            val session = RecoverySession(
                id = ids.next("rec"),
                groupId = state.group.id,
                affectedVehicleIds = listOf(c.actorId),
                reason = RecoveryReason.MANUAL_HELP_REQUEST,
                proposedMeetingPoint = null,
                status = RecoveryStatus.OPEN,
                createdAt = now,
                previousProposalId = state.activePlan()?.id,
            )
            next = next.copy(recoverySessions = next.recoverySessions + session)
                .withEvent(now, c.actorId, JourneyEventKind.RECOVERY_OPENED, "차량 ${label}에게 도움이 필요해요. 재조율을 시작할 수 있어요.", session.id)
        }
        return Outcome.Changed(next)
    }

    private fun propose(state: JourneyState, c: JourneyCommand.ProposeRendezvous, now: Long): Outcome {
        if (state.activeProposal() != null) return Outcome.Error(DomainError.proposalAlreadyActive())
        val proposer = state.member(c.actorId)!!

        val recovery = c.recoverySessionId?.let { id ->
            val session = state.recoverySessions.firstOrNull { it.id == id } ?: return Outcome.Error(DomainError.recoveryNotFound())
            if (session.status != RecoveryStatus.OPEN && session.status != RecoveryStatus.DEFERRED) {
                return Outcome.Error(DomainError.recoveryClosed())
            }
            session
        }
        val linkedRequest = c.requestId?.let { id -> state.requests.firstOrNull { it.id == id } }
            ?: state.requests.lastOrNull {
                it.status == MobilityRequestStatus.OPEN &&
                    (it.type == MobilityRequestType.REST_REQUEST || it.type == MobilityRequestType.RENDEZVOUS_REQUEST ||
                        it.type == MobilityRequestType.DESTINATION_CHANGE)
            }

        val approvals = state.group.members.associate { member ->
            member.id to if (member.id == proposer.id) ApprovalDecision.APPROVED else ApprovalDecision.PENDING
        }
        val previousPlan = state.activePlan()
        val proposal = RendezvousProposal(
            id = ids.next("prp"),
            groupId = state.group.id,
            proposedBy = proposer.id,
            location = c.place,
            approvals = approvals,
            status = ProposalStatus.PROPOSED,
            createdAt = now,
            planNumber = state.proposals.count { it.status == ProposalStatus.APPROVED } + 1,
            requestId = linkedRequest?.id,
            recoverySessionId = recovery?.id,
            replacesProposalId = previousPlan?.id,
        )

        var next = state.copy(proposals = state.proposals + proposal)
            .withEvent(
                now, c.actorId, JourneyEventKind.PROPOSAL_CREATED,
                if (recovery != null) "차량 ${proposer.vehicleLabel}가 새로운 합류 장소를 제안했어요 · ${c.place.name}"
                else "차량 ${proposer.vehicleLabel}가 합류 장소를 제안했어요 · ${c.place.name}",
                proposal.id,
            )
        if (linkedRequest != null && linkedRequest.status == MobilityRequestStatus.OPEN) {
            next = next.updateRequest(linkedRequest.id) { it.copy(status = MobilityRequestStatus.IN_PROGRESS) }
        }
        if (recovery != null) {
            next = next.updateRecovery(recovery.id) {
                it.copy(status = RecoveryStatus.REPLANNING, newProposalId = proposal.id, proposedMeetingPoint = c.place)
            }
        }
        // 차량이 한 대뿐이면 제안과 동시에 확정된다.
        if (approvals.values.all { it == ApprovalDecision.APPROVED }) {
            next = approve(next, proposal.id, now)
        }
        return Outcome.Changed(next)
    }

    private fun respond(state: JourneyState, c: JourneyCommand.RespondToProposal, now: Long): Outcome {
        val proposal = state.proposals.firstOrNull { it.id == c.proposalId } ?: return Outcome.Error(DomainError.proposalNotFound())
        val current = proposal.approvals[c.actorId] ?: return Outcome.Error(DomainError.notAVoter())
        val decision = if (c.approve) ApprovalDecision.APPROVED else ApprovalDecision.REJECTED
        if (current == decision) return Outcome.NoChange(DomainError.alreadyResponded())
        if (proposal.status != ProposalStatus.PROPOSED && proposal.status != ProposalStatus.VOTING) {
            return Outcome.Error(DomainError.proposalClosed())
        }
        if (current != ApprovalDecision.PENDING) return Outcome.Error(DomainError.alreadyResponded())

        val member = state.member(c.actorId)!!
        val approvals = proposal.approvals + (c.actorId to decision)
        var next = state.updateProposal(proposal.id) { it.copy(approvals = approvals, status = ProposalStatus.VOTING) }
            .withEvent(
                now, c.actorId, JourneyEventKind.PROPOSAL_RESPONSE,
                "차량 ${member.vehicleLabel} — ${if (c.approve) "동의" else "거절"}",
                proposal.id,
            )

        next = when {
            decision == ApprovalDecision.REJECTED -> reject(next, proposal.id, member, now)
            approvals.values.all { it == ApprovalDecision.APPROVED } -> approve(next, proposal.id, now)
            else -> next
        }
        return Outcome.Changed(next)
    }

    private fun cancel(state: JourneyState, c: JourneyCommand.CancelProposal, now: Long): Outcome {
        val proposal = state.proposals.firstOrNull { it.id == c.proposalId } ?: return Outcome.Error(DomainError.proposalNotFound())
        if (proposal.proposedBy != c.actorId) return Outcome.Error(DomainError.notProposer())
        if (proposal.status == ProposalStatus.CANCELLED) return Outcome.NoChange()
        if (proposal.status != ProposalStatus.PROPOSED && proposal.status != ProposalStatus.VOTING) {
            return Outcome.Error(DomainError.proposalClosed())
        }
        var next = state.updateProposal(proposal.id) { it.copy(status = ProposalStatus.CANCELLED, decidedAt = now) }
            .withEvent(now, c.actorId, JourneyEventKind.PROPOSAL_CANCELLED, "${proposal.location.name} 합류 제안이 취소되었어요.", proposal.id)
        next = reopenAfterFailedProposal(next, proposal)
        return Outcome.Changed(next)
    }

    private fun dispatch(state: JourneyState, c: JourneyCommand.DispatchNavigation, now: Long): Outcome {
        val proposal = state.proposals.firstOrNull { it.id == c.proposalId } ?: return Outcome.Error(DomainError.proposalNotFound())
        when (proposal.status) {
            ProposalStatus.PROPOSED, ProposalStatus.VOTING -> {
                val pending = proposal.approvals.filterValues { it == ApprovalDecision.PENDING }.keys
                    .mapNotNull { state.member(it)?.vehicleLabel }
                return Outcome.Error(if (pending.isEmpty()) DomainError.notApproved() else DomainError.approvalIncomplete(pending))
            }
            ProposalStatus.REJECTED, ProposalStatus.CANCELLED -> return Outcome.Error(DomainError.notApproved())
            ProposalStatus.APPROVED -> Unit
        }
        if (proposal.supersededByProposalId != null) return Outcome.Error(DomainError.noActivePlan())
        if (state.navigationActions.any { it.proposalId == proposal.id }) return Outcome.Error(DomainError.alreadyDispatched())

        val actions = state.group.members.map { member ->
            NavigationAction(
                id = ids.next("nav"),
                proposalId = proposal.id,
                vehicleId = member.id,
                destination = proposal.location,
                status = NavigationActionStatus.PENDING,
                requestedAt = now,
            )
        }
        return Outcome.Changed(
            state.copy(navigationActions = state.navigationActions + actions)
                .withEvent(now, c.actorId, JourneyEventKind.NAVIGATION_DISPATCHED, "각 차량에 경로 변경을 요청했어요 · ${proposal.location.name}", proposal.id),
        )
    }

    private fun reportNavigation(state: JourneyState, c: JourneyCommand.ReportNavigationResult, now: Long): Outcome {
        val action = state.navigationActions.firstOrNull { it.id == c.actionId } ?: return Outcome.Error(DomainError.actionNotFound())
        if (action.vehicleId != c.actorId) return Outcome.Error(DomainError.notActionOwner())
        if (action.status == c.status) return Outcome.NoChange()
        if (c.status !in allowedTransitions.getValue(action.status)) {
            return Outcome.Error(DomainError.invalidTransition(action.status.name, c.status.name))
        }
        val terminal = c.status == NavigationActionStatus.APPLIED || c.status == NavigationActionStatus.FAILED ||
            c.status == NavigationActionStatus.UNKNOWN
        val member = state.member(action.vehicleId)!!
        val message = when (c.status) {
            NavigationActionStatus.PENDING -> "차량 ${member.vehicleLabel} 경로 변경 대기"
            NavigationActionStatus.USER_CONFIRMATION_REQUIRED -> "차량 ${member.vehicleLabel} 운전자 확인을 기다리고 있어요."
            NavigationActionStatus.REQUESTED -> "차량 ${member.vehicleLabel} 내비게이션에 경로를 요청했어요."
            NavigationActionStatus.APPLIED -> "차량 ${member.vehicleLabel} 내비게이션에 경로가 적용되었어요."
            NavigationActionStatus.FAILED -> "차량 ${member.vehicleLabel} 경로 적용에 실패했어요." + (c.errorMessage?.let { " ($it)" } ?: "")
            NavigationActionStatus.UNKNOWN -> "차량 ${member.vehicleLabel} 경로 적용 결과를 확인하지 못했어요." + (c.errorMessage?.let { " ($it)" } ?: "")
        }
        return Outcome.Changed(
            state.updateAction(action.id) {
                it.copy(
                    status = c.status,
                    errorMessage = c.errorMessage,
                    completedAt = if (terminal) now else null,
                )
            }.withEvent(now, c.actorId, JourneyEventKind.NAVIGATION_UPDATED, message, action.id),
        )
    }

    private fun retryNavigation(state: JourneyState, c: JourneyCommand.RetryNavigation, now: Long): Outcome {
        val action = state.navigationActions.firstOrNull { it.id == c.actionId } ?: return Outcome.Error(DomainError.actionNotFound())
        if (action.status != NavigationActionStatus.FAILED && action.status != NavigationActionStatus.UNKNOWN) {
            return Outcome.Error(DomainError.invalidTransition(action.status.name, NavigationActionStatus.PENDING.name))
        }
        val member = state.member(action.vehicleId)!!
        return Outcome.Changed(
            state.updateAction(action.id) {
                it.copy(
                    status = NavigationActionStatus.PENDING,
                    errorMessage = null,
                    requestedAt = now,
                    completedAt = null,
                    attempt = it.attempt + 1,
                )
            }.withEvent(now, c.actorId, JourneyEventKind.NAVIGATION_UPDATED, "차량 ${member.vehicleLabel} 경로 변경을 다시 요청했어요.", action.id),
        )
    }

    private fun openRecovery(state: JourneyState, c: JourneyCommand.OpenRecovery, now: Long): Outcome {
        if (c.affectedVehicleIds.isEmpty() || c.affectedVehicleIds.any { state.member(it) == null }) {
            return Outcome.Error(DomainError.unknownMember())
        }
        if (c.affectedVehicleIds.any { state.openRecoveryFor(it) != null }) return Outcome.Error(DomainError.recoveryAlreadyOpen())
        val plan = state.activePlan()
        val needsPlan = c.reason == RecoveryReason.MISSED_RENDEZVOUS || c.reason == RecoveryReason.NAVIGATION_NOT_APPLIED
        if (needsPlan && plan == null) return Outcome.Error(DomainError.noActivePlan())

        val session = RecoverySession(
            id = ids.next("rec"),
            groupId = state.group.id,
            affectedVehicleIds = c.affectedVehicleIds,
            reason = c.reason,
            proposedMeetingPoint = c.suggestedMeetingPoint,
            status = RecoveryStatus.OPEN,
            createdAt = now,
            previousProposalId = plan?.id,
        )
        val labels = c.affectedVehicleIds.mapNotNull { state.member(it)?.vehicleLabel }.joinToString(", ") { "차량 $it" }
        val message = when (c.reason) {
            RecoveryReason.MISSED_RENDEZVOUS -> "${labels}가 기존 합류 장소에 합류하지 못했습니다."
            RecoveryReason.NAVIGATION_NOT_APPLIED -> "${labels}의 경로가 합류 장소로 변경되지 않았어요."
            RecoveryReason.ROUTE_DEVIATION -> "${labels}가 계획한 경로를 벗어났어요."
            RecoveryReason.MANUAL_HELP_REQUEST -> "${labels}가 도움을 요청했어요."
        }
        return Outcome.Changed(
            state.copy(recoverySessions = state.recoverySessions + session)
                .withEvent(now, c.actorId, JourneyEventKind.RECOVERY_OPENED, message, session.id),
        )
    }

    private fun closeRecovery(state: JourneyState, sessionId: String, actorId: String, keep: Boolean, now: Long): Outcome {
        val session = state.recoverySessions.firstOrNull { it.id == sessionId } ?: return Outcome.Error(DomainError.recoveryNotFound())
        val target = if (keep) RecoveryStatus.KEPT_CURRENT_PLAN else RecoveryStatus.DEFERRED
        if (session.status == target) return Outcome.NoChange()
        val allowed = if (keep) setOf(RecoveryStatus.OPEN, RecoveryStatus.DEFERRED) else setOf(RecoveryStatus.OPEN)
        if (session.status !in allowed) return Outcome.Error(DomainError.recoveryClosed())
        return Outcome.Changed(
            state.updateRecovery(session.id) {
                it.copy(status = target, resolvedAt = if (keep) now else null)
            }.withEvent(
                now, actorId, JourneyEventKind.RECOVERY_UPDATED,
                if (keep) "현재 합류 계획을 유지하기로 했어요." else "재조율을 나중에 확인하기로 했어요.",
                session.id,
            ),
        )
    }

    private fun confirmRendezvousArrival(state: JourneyState, c: JourneyCommand.ConfirmRendezvousArrival, now: Long): Outcome {
        val plan = state.activePlan()
        if (plan == null || plan.id != c.proposalId) return Outcome.Error(DomainError.noActivePlan())
        if (c.actorId in plan.arrivedVehicleIds) return Outcome.NoChange()
        val member = state.member(c.actorId)!!
        val arrived = plan.arrivedVehicleIds + c.actorId
        var next = state.updateProposal(plan.id) { it.copy(arrivedVehicleIds = arrived) }
            .withEvent(now, c.actorId, JourneyEventKind.RENDEZVOUS_ARRIVAL, "차량 ${member.vehicleLabel}가 ${plan.location.name}에 도착했어요.", plan.id)
        if (state.group.members.all { it.id in arrived }) {
            next = next.withEvent(now, null, JourneyEventKind.RENDEZVOUS_ARRIVAL, "모두 ${plan.location.name}에서 만났어요.", plan.id)
            plan.requestId?.let { requestId -> next = next.updateRequest(requestId) { it.copy(status = MobilityRequestStatus.RESOLVED) } }
        }
        return Outcome.Changed(next)
    }

    private fun confirmDestinationArrival(state: JourneyState, c: JourneyCommand.ConfirmDestinationArrival, now: Long): Outcome {
        val member = state.member(c.actorId)!!
        if (member.arrivalConfirmed) return Outcome.NoChange()
        var next = state.replaceMember(member.copy(arrivalConfirmed = true))
            .withEvent(now, c.actorId, JourneyEventKind.DESTINATION_ARRIVAL, "차량 ${member.vehicleLabel}가 ${state.group.destination.name}에 도착했어요.", member.id)
        if (next.group.members.all { it.arrivalConfirmed }) {
            next = next.copy(group = next.group.copy(status = JourneyStatus.COMPLETED, endedAt = now))
                .withEvent(now, null, JourneyEventKind.JOURNEY_COMPLETED, "모두 도착했어요.", state.group.id)
        }
        return Outcome.Changed(next)
    }

    private fun endJourney(state: JourneyState, c: JourneyCommand.EndJourney, now: Long): Outcome {
        val next = state.copy(
            group = state.group.copy(status = JourneyStatus.ENDED, endedAt = now),
            requests = state.requests.map {
                if (it.status == MobilityRequestStatus.OPEN || it.status == MobilityRequestStatus.IN_PROGRESS) it.copy(status = MobilityRequestStatus.CLOSED) else it
            },
            proposals = state.proposals.map {
                if (it.status == ProposalStatus.PROPOSED || it.status == ProposalStatus.VOTING) it.copy(status = ProposalStatus.CANCELLED, decidedAt = now) else it
            },
        ).withEvent(now, c.actorId, JourneyEventKind.JOURNEY_ENDED, "여행 세션이 종료되었어요.", state.group.id)
        return Outcome.Changed(next)
    }

    // endregion

    // region helpers

    private fun approve(state: JourneyState, proposalId: String, now: Long): JourneyState {
        val proposal = state.proposals.first { it.id == proposalId }
        var next = state.updateProposal(proposalId) { it.copy(status = ProposalStatus.APPROVED, decidedAt = now) }
        proposal.replacesProposalId?.let { oldId ->
            next = next.updateProposal(oldId) { it.copy(supersededByProposalId = proposalId) }
        }
        proposal.recoverySessionId?.let { sessionId ->
            next = next.updateRecovery(sessionId) { it.copy(status = RecoveryStatus.RESOLVED, resolvedAt = now) }
        }
        // 다른 재조율 세션이 같은 이전 계획을 기준으로 열려 있었다면 함께 해결된 것으로 본다.
        next = next.copy(
            recoverySessions = next.recoverySessions.map {
                if (it.id != proposal.recoverySessionId && it.status in OPEN_RECOVERY && it.previousProposalId == proposal.replacesProposalId && proposal.replacesProposalId != null) {
                    it.copy(status = RecoveryStatus.RESOLVED, resolvedAt = now, newProposalId = proposalId)
                } else it
            },
        )
        val message = if (proposal.recoverySessionId != null) "새로운 합류 계획이 확정되었어요." else "모든 차량이 동의했어요. 합류 장소 확정 · ${proposal.location.name}"
        return next.withEvent(now, null, JourneyEventKind.PROPOSAL_APPROVED, message, proposalId)
    }

    private fun reject(state: JourneyState, proposalId: String, by: VehicleMember, now: Long): JourneyState {
        val proposal = state.proposals.first { it.id == proposalId }
        val next = state.updateProposal(proposalId) { it.copy(status = ProposalStatus.REJECTED, decidedAt = now) }
            .withEvent(now, by.id, JourneyEventKind.PROPOSAL_REJECTED, "차량 ${by.vehicleLabel}가 거절해 ${proposal.location.name} 제안이 확정되지 않았어요.", proposalId)
        return reopenAfterFailedProposal(next, proposal)
    }

    /** 제안이 거절/취소되면 연결된 요청과 재조율 세션을 다시 열어 다른 장소를 제안할 수 있게 한다. */
    private fun reopenAfterFailedProposal(state: JourneyState, proposal: RendezvousProposal): JourneyState {
        var next = state
        proposal.requestId?.let { id ->
            next = next.updateRequest(id) { if (it.status == MobilityRequestStatus.IN_PROGRESS) it.copy(status = MobilityRequestStatus.OPEN) else it }
        }
        proposal.recoverySessionId?.let { id ->
            next = next.updateRecovery(id) { if (it.status == RecoveryStatus.REPLANNING) it.copy(status = RecoveryStatus.OPEN, newProposalId = null) else it }
        }
        return next
    }

    /** 파생 상태(차량별 내비게이션 요약, 요청 해결 여부)를 다시 계산한다. */
    private fun normalize(state: JourneyState): JourneyState {
        val plan = state.activePlan()
        val members = state.group.members.map { member ->
            val status = if (plan == null || member.id in plan.arrivedVehicleIds) {
                MemberNavigationStatus.ORIGINAL_DESTINATION
            } else {
                val action = state.navigationActions.lastOrNull { it.proposalId == plan.id && it.vehicleId == member.id }
                when (action?.status) {
                    null, NavigationActionStatus.PENDING, NavigationActionStatus.USER_CONFIRMATION_REQUIRED,
                    NavigationActionStatus.REQUESTED -> MemberNavigationStatus.CHANGE_PENDING
                    NavigationActionStatus.APPLIED -> MemberNavigationStatus.RENDEZVOUS_APPLIED
                    NavigationActionStatus.FAILED -> MemberNavigationStatus.CHANGE_FAILED
                    NavigationActionStatus.UNKNOWN -> MemberNavigationStatus.UNKNOWN
                }
            }
            if (member.navigationStatus == status) member else member.copy(navigationStatus = status)
        }
        return state.copy(group = state.group.copy(members = members))
    }

    private sealed interface Outcome {
        data class Changed(val state: JourneyState) : Outcome
        data class NoChange(val notice: DomainError? = null) : Outcome
        data class Error(val error: DomainError) : Outcome
    }

    // endregion

    companion object {
        const val MAX_VEHICLES = 6
        private const val MAX_TRACKED_COMMANDS = 500
        private const val MAX_EVENTS = 200

        val OPEN_RECOVERY = setOf(RecoveryStatus.OPEN, RecoveryStatus.REPLANNING, RecoveryStatus.DEFERRED)

        /** 차량별 내비게이션 적용 상태 전이 규칙. FAILED/UNKNOWN → PENDING은 RetryNavigation으로만 가능하다. */
        val allowedTransitions: Map<NavigationActionStatus, Set<NavigationActionStatus>> = mapOf(
            NavigationActionStatus.PENDING to setOf(
                NavigationActionStatus.USER_CONFIRMATION_REQUIRED, NavigationActionStatus.REQUESTED,
                NavigationActionStatus.FAILED, NavigationActionStatus.UNKNOWN,
            ),
            NavigationActionStatus.USER_CONFIRMATION_REQUIRED to setOf(
                NavigationActionStatus.REQUESTED, NavigationActionStatus.FAILED, NavigationActionStatus.UNKNOWN,
            ),
            NavigationActionStatus.REQUESTED to setOf(
                NavigationActionStatus.APPLIED, NavigationActionStatus.FAILED, NavigationActionStatus.UNKNOWN,
            ),
            // 시간 초과 후 늦게 도착한 내비게이션 결과는 반영한다.
            NavigationActionStatus.UNKNOWN to setOf(NavigationActionStatus.APPLIED, NavigationActionStatus.FAILED),
            NavigationActionStatus.FAILED to emptySet(),
            NavigationActionStatus.APPLIED to emptySet(),
        )

        private fun JourneyState.markProcessed(commandId: String): JourneyState =
            copy(processedCommandIds = (processedCommandIds + commandId).takeLast(MAX_TRACKED_COMMANDS))

        internal fun JourneyState.withEvent(
            at: Long,
            actorId: String?,
            kind: JourneyEventKind,
            message: String,
            refId: String?,
        ): JourneyState {
            val event = JourneyEvent(
                sequence = (events.lastOrNull()?.sequence ?: 0) + 1,
                at = at,
                actorId = actorId,
                kind = kind,
                message = message,
                refId = refId,
            )
            return copy(events = (events + event).takeLast(MAX_EVENTS))
        }
    }
}

// region state queries (앱/서버/테스트에서 공통으로 사용)

fun JourneyState.member(id: String): VehicleMember? = group.members.firstOrNull { it.id == id }

/** 투표가 진행 중인 제안 (최대 1개). */
fun JourneyState.activeProposal(): RendezvousProposal? =
    proposals.lastOrNull { it.status == ProposalStatus.PROPOSED || it.status == ProposalStatus.VOTING }

/** 현재 유효한 합류 계획: 승인되었고, 대체되지 않았고, 아직 모두 합류하지 않은 제안. */
fun JourneyState.activePlan(): RendezvousProposal? =
    proposals.lastOrNull { proposal ->
        proposal.status == ProposalStatus.APPROVED &&
            proposal.supersededByProposalId == null &&
            !group.members.all { it.id in proposal.arrivedVehicleIds }
    }

fun JourneyState.openRecoveryFor(vehicleId: String): RecoverySession? =
    recoverySessions.lastOrNull { vehicleId in it.affectedVehicleIds && it.status in JourneyEngine.OPEN_RECOVERY }

fun JourneyState.actionsFor(proposalId: String): List<NavigationAction> =
    navigationActions.filter { it.proposalId == proposalId }

private fun JourneyState.replaceMember(member: VehicleMember): JourneyState =
    copy(group = group.copy(members = group.members.map { if (it.id == member.id) member else it }))

private fun JourneyState.updateRequest(id: String, f: (MobilityRequest) -> MobilityRequest): JourneyState =
    copy(requests = requests.map { if (it.id == id) f(it) else it })

private fun JourneyState.updateProposal(id: String, f: (RendezvousProposal) -> RendezvousProposal): JourneyState =
    copy(proposals = proposals.map { if (it.id == id) f(it) else it })

private fun JourneyState.updateAction(id: String, f: (NavigationAction) -> NavigationAction): JourneyState =
    copy(navigationActions = navigationActions.map { if (it.id == id) f(it) else it })

private fun JourneyState.updateRecovery(id: String, f: (RecoverySession) -> RecoverySession): JourneyState =
    copy(recoverySessions = recoverySessions.map { if (it.id == id) f(it) else it })

// endregion
