package com.modo.domain

import com.modo.domain.engine.DomainError
import com.modo.domain.engine.EngineResult
import com.modo.domain.engine.RoutePhase
import com.modo.domain.engine.VehicleDisplayStatus
import com.modo.domain.engine.activePlan
import com.modo.domain.engine.displayStatusOf
import com.modo.domain.engine.member
import com.modo.domain.engine.routeSummary
import com.modo.domain.model.ApprovalDecision
import com.modo.domain.model.ConnectionStatus
import com.modo.domain.model.GeoPoint
import com.modo.domain.model.JourneyCommand
import com.modo.domain.model.JourneyStatus
import com.modo.domain.model.MemberNavigationStatus
import com.modo.domain.model.MobilityRequestStatus
import com.modo.domain.model.MobilityRequestType
import com.modo.domain.model.MovementStatus
import com.modo.domain.model.NavigationActionStatus
import com.modo.domain.model.ProposalStatus
import com.modo.domain.model.RecoveryReason
import com.modo.domain.model.RecoveryStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JourneyEngineTest {

    // region 그룹 참여

    @Test
    fun `잘못된 초대 코드와 중복 참가는 거절된다`() {
        val h = Harness().startThreeVehicles()
        assertEquals(
            DomainError.Code.INVALID_INVITE_CODE,
            h.rejected(JourneyCommand.Join(h.cid(), "D", "WRONG", "새 차량", "D")).error.code,
        )
        assertEquals(
            DomainError.Code.ALREADY_JOINED,
            h.rejected(JourneyCommand.Join(h.cid(), "B", "MODO24", "친구 차량", "Z")).error.code,
        )
        assertEquals(
            DomainError.Code.VEHICLE_LABEL_TAKEN,
            h.rejected(JourneyCommand.Join(h.cid(), "D", "modo24", "새 차량", "c")).error.code,
        )
        assertEquals(3, h.state.group.members.size)
    }

    // endregion

    // region 이동 요청

    @Test
    fun `휴식 요청은 REST_REQUEST로 생성되고 그룹 알림이 기록된다`() {
        val h = Harness().startThreeVehicles()
        val r = h.ok(JourneyCommand.CreateRequest(h.cid(), "B", "우리 잠깐 쉬었다 가자."))
        val request = h.state.requests.single()
        assertEquals(MobilityRequestType.REST_REQUEST, request.type)
        assertEquals(MobilityRequestStatus.OPEN, request.status)
        assertEquals("B", request.requesterId)
        assertEquals("차량 B가 휴식을 요청했어요.", r.events.single().message)
        assertEquals(VehicleDisplayStatus.REQUEST_RAISED, h.state.displayStatusOf("B", h.now))
    }

    @Test
    fun `인식할 수 없는 요청과 중복 요청은 거절된다`() {
        val h = Harness().startThreeVehicles()
        assertEquals(
            DomainError.Code.UNRECOGNIZED_COMMAND,
            h.rejected(JourneyCommand.CreateRequest(h.cid(), "B", "라디오 켜줘")).error.code,
        )
        h.ok(JourneyCommand.CreateRequest(h.cid(), "B", "우리 잠깐 쉬었다 가자."))
        assertEquals(
            DomainError.Code.DUPLICATE_REQUEST,
            h.rejected(JourneyCommand.CreateRequest(h.cid(), "B", "화장실 가고 싶어")).error.code,
        )
        assertEquals(1, h.state.requests.size)
    }

    @Test
    fun `같은 commandId가 다시 오면 상태를 바꾸지 않는다`() {
        val h = Harness().startThreeVehicles()
        val command = JourneyCommand.CreateRequest("same-id", "B", "우리 잠깐 쉬었다 가자.")
        h.ok(command)
        val version = h.state.version
        assertIs<EngineResult.Duplicate>(h.run(command))
        assertEquals(version, h.state.version)
        assertEquals(1, h.state.requests.size)
    }

    // endregion

    // region 합류 제안 / 승인

    @Test
    fun `모든 차량이 동의해야 제안이 APPROVED가 된다`() {
        val h = Harness().startThreeVehicles()
        h.ok(JourneyCommand.CreateRequest(h.cid(), "B", "우리 잠깐 쉬었다 가자."))
        h.ok(JourneyCommand.ProposeRendezvous(h.cid(), "A", Places.hoengseong))
        val id = h.state.proposals.single().id
        assertEquals(ProposalStatus.PROPOSED, h.state.proposals.single().status)
        assertEquals(ApprovalDecision.APPROVED, h.state.proposals.single().approvals["A"])
        assertEquals(MobilityRequestStatus.IN_PROGRESS, h.state.requests.single().status)

        h.ok(JourneyCommand.RespondToProposal(h.cid(), "B", id, true))
        val voting = h.state.proposals.single()
        assertEquals(ProposalStatus.VOTING, voting.status)
        assertEquals(ApprovalDecision.PENDING, voting.approvals["C"])

        h.ok(JourneyCommand.RespondToProposal(h.cid(), "C", id, true))
        assertEquals(ProposalStatus.APPROVED, h.state.proposals.single().status)
        assertEquals(id, h.state.activePlan()?.id)
    }

    @Test
    fun `중복 승인은 상태를 바꾸지 않고 안내만 한다`() {
        val h = Harness().startThreeVehicles()
        h.ok(JourneyCommand.ProposeRendezvous(h.cid(), "A", Places.hoengseong))
        val id = h.state.proposals.single().id
        h.ok(JourneyCommand.RespondToProposal(h.cid(), "B", id, true))
        val version = h.state.version
        val dup = assertIs<EngineResult.Duplicate>(h.run(JourneyCommand.RespondToProposal(h.cid(), "B", id, true)))
        assertEquals(DomainError.Code.ALREADY_RESPONDED, dup.notice?.code)
        assertEquals(version, h.state.version)
    }

    @Test
    fun `한 차량이라도 거절하면 REJECTED이고 요청은 다시 열린다`() {
        val h = Harness().startThreeVehicles()
        h.ok(JourneyCommand.CreateRequest(h.cid(), "B", "우리 잠깐 쉬었다 가자."))
        h.ok(JourneyCommand.ProposeRendezvous(h.cid(), "A", Places.hoengseong))
        val id = h.state.proposals.single().id
        h.ok(JourneyCommand.RespondToProposal(h.cid(), "C", id, false))
        assertEquals(ProposalStatus.REJECTED, h.state.proposals.single().status)
        assertEquals(MobilityRequestStatus.OPEN, h.state.requests.single().status)
        assertNull(h.state.activePlan())
    }

    @Test
    fun `결정이 끝난 제안에 늦게 도착한 응답은 반영하지 않는다`() {
        val h = Harness().startThreeVehicles()
        h.ok(JourneyCommand.ProposeRendezvous(h.cid(), "A", Places.hoengseong))
        val id = h.state.proposals.single().id
        h.ok(JourneyCommand.RespondToProposal(h.cid(), "B", id, false))
        val late = h.rejected(JourneyCommand.RespondToProposal(h.cid(), "C", id, true))
        assertEquals(DomainError.Code.PROPOSAL_CLOSED, late.error.code)
        assertEquals(ApprovalDecision.PENDING, h.state.proposals.single().approvals["C"])
    }

    @Test
    fun `제안 취소는 제안자만 할 수 있고 진행 중인 제안은 하나뿐이다`() {
        val h = Harness().startThreeVehicles()
        h.ok(JourneyCommand.ProposeRendezvous(h.cid(), "A", Places.hoengseong))
        val id = h.state.proposals.single().id
        assertEquals(
            DomainError.Code.PROPOSAL_ALREADY_ACTIVE,
            h.rejected(JourneyCommand.ProposeRendezvous(h.cid(), "B", Places.pyeongchang)).error.code,
        )
        assertEquals(DomainError.Code.NOT_PROPOSER, h.rejected(JourneyCommand.CancelProposal(h.cid(), "B", id)).error.code)
        h.ok(JourneyCommand.CancelProposal(h.cid(), "A", id))
        assertEquals(ProposalStatus.CANCELLED, h.state.proposals.single().status)
        h.ok(JourneyCommand.ProposeRendezvous(h.cid(), "B", Places.pyeongchang))
    }

    // endregion

    // region 내비게이션 적용

    @Test
    fun `승인 전에는 경로 변경을 요청할 수 없다`() {
        val h = Harness().startThreeVehicles()
        h.ok(JourneyCommand.ProposeRendezvous(h.cid(), "A", Places.hoengseong))
        val id = h.state.proposals.single().id
        h.ok(JourneyCommand.RespondToProposal(h.cid(), "B", id, true))

        val r = h.rejected(JourneyCommand.DispatchNavigation(h.cid(), "A", id))
        assertEquals(DomainError.Code.APPROVAL_INCOMPLETE, r.error.code)
        assertTrue(r.error.message.contains("차량 C"))
        assertTrue(h.state.navigationActions.isEmpty())
    }

    @Test
    fun `그룹 승인 완료는 경로 적용 완료가 아니다`() {
        val h = Harness().startThreeVehicles()
        val id = h.approvedRendezvous()
        // 승인되었지만 아직 어떤 차량에도 적용되지 않음
        assertEquals(RoutePhase.AGREED, h.state.routeSummary(id)!!.phase)
        assertTrue(h.state.group.members.all { it.navigationStatus == MemberNavigationStatus.CHANGE_PENDING })
        assertEquals(VehicleDisplayStatus.NAV_CHANGE_PENDING, h.state.displayStatusOf("A", h.now))

        h.ok(JourneyCommand.DispatchNavigation(h.cid(), "A", id))
        assertEquals(3, h.state.navigationActions.size)
        assertTrue(h.state.navigationActions.all { it.status == NavigationActionStatus.PENDING })
        assertEquals(RoutePhase.REQUESTING, h.state.routeSummary(id)!!.phase)
        assertEquals(
            DomainError.Code.ALREADY_DISPATCHED,
            h.rejected(JourneyCommand.DispatchNavigation(h.cid(), "B", id)).error.code,
        )
    }

    @Test
    fun `차량별 적용 결과가 각각 반영된다`() {
        val h = Harness().startThreeVehicles()
        val id = h.approvedRendezvous()
        h.ok(JourneyCommand.DispatchNavigation(h.cid(), "A", id))

        for (v in listOf("A", "B")) {
            h.ok(JourneyCommand.ReportNavigationResult(h.cid(), v, h.actionOf(v, id).id, NavigationActionStatus.REQUESTED))
            h.ok(JourneyCommand.ReportNavigationResult(h.cid(), v, h.actionOf(v, id).id, NavigationActionStatus.APPLIED))
        }
        h.ok(JourneyCommand.ReportNavigationResult(h.cid(), "C", h.actionOf("C", id).id, NavigationActionStatus.USER_CONFIRMATION_REQUIRED))

        assertEquals(MemberNavigationStatus.RENDEZVOUS_APPLIED, h.state.member("A")!!.navigationStatus)
        assertEquals(MemberNavigationStatus.RENDEZVOUS_APPLIED, h.state.member("B")!!.navigationStatus)
        assertEquals(MemberNavigationStatus.CHANGE_PENDING, h.state.member("C")!!.navigationStatus)
        val summary = h.state.routeSummary(id)!!
        assertEquals(RoutePhase.WAITING_CONFIRMATION, summary.phase)
        assertEquals(2, summary.appliedCount)
        assertNotNull(h.actionOf("A", id).completedAt)
    }

    @Test
    fun `다른 차량의 결과를 보고하거나 잘못된 전이는 거절된다`() {
        val h = Harness().startThreeVehicles()
        val id = h.approvedRendezvous()
        h.ok(JourneyCommand.DispatchNavigation(h.cid(), "A", id))
        val c = h.actionOf("C", id).id
        assertEquals(
            DomainError.Code.NOT_ACTION_OWNER,
            h.rejected(JourneyCommand.ReportNavigationResult(h.cid(), "A", c, NavigationActionStatus.APPLIED)).error.code,
        )
        // PENDING에서 바로 APPLIED로 갈 수 없음 (실제 요청 없이 성공 처리 금지)
        assertEquals(
            DomainError.Code.INVALID_TRANSITION,
            h.rejected(JourneyCommand.ReportNavigationResult(h.cid(), "C", c, NavigationActionStatus.APPLIED)).error.code,
        )
    }

    @Test
    fun `실패와 시간 초과는 재시도로 다시 PENDING이 된다`() {
        val h = Harness().startThreeVehicles()
        val id = h.approvedRendezvous()
        h.ok(JourneyCommand.DispatchNavigation(h.cid(), "A", id))
        val c = h.actionOf("C", id).id
        h.ok(JourneyCommand.ReportNavigationResult(h.cid(), "C", c, NavigationActionStatus.REQUESTED))
        h.ok(JourneyCommand.ReportNavigationResult(h.cid(), "C", c, NavigationActionStatus.FAILED, "경로 탐색 서버 오류"))
        assertEquals(RoutePhase.NEEDS_RETRY, h.state.routeSummary(id)!!.phase)
        assertEquals(VehicleDisplayStatus.NAV_FAILED, h.state.displayStatusOf("C", h.now))

        h.ok(JourneyCommand.RetryNavigation(h.cid(), "A", c))
        val retried = h.actionOf("C", id)
        assertEquals(NavigationActionStatus.PENDING, retried.status)
        assertEquals(2, retried.attempt)
        assertNull(retried.errorMessage)

        h.ok(JourneyCommand.ReportNavigationResult(h.cid(), "C", c, NavigationActionStatus.REQUESTED))
        h.ok(JourneyCommand.ReportNavigationResult(h.cid(), "C", c, NavigationActionStatus.UNKNOWN, "응답 시간 초과"))
        // 시간 초과 후 늦게 도착한 성공 결과는 반영된다
        h.ok(JourneyCommand.ReportNavigationResult(h.cid(), "C", c, NavigationActionStatus.APPLIED))
        assertEquals(MemberNavigationStatus.RENDEZVOUS_APPLIED, h.state.member("C")!!.navigationStatus)
    }

    // endregion

    // region 재조율

    @Test
    fun `합류 실패는 RecoverySession을 만들고 새 제안은 이전 계획과 구분된다`() {
        val h = Harness().startThreeVehicles()
        val first = h.approvedRendezvous(Places.hoengseong)
        h.ok(JourneyCommand.DispatchNavigation(h.cid(), "A", first))

        h.ok(JourneyCommand.OpenRecovery(h.cid(), "C", listOf("C"), RecoveryReason.MISSED_RENDEZVOUS, Places.pyeongchang))
        val session = h.state.recoverySessions.single()
        assertEquals(RecoveryStatus.OPEN, session.status)
        assertEquals(first, session.previousProposalId)
        assertEquals(VehicleDisplayStatus.RECOVERY_NEEDED, h.state.displayStatusOf("C", h.now))
        assertEquals(
            DomainError.Code.RECOVERY_ALREADY_OPEN,
            h.rejected(JourneyCommand.OpenRecovery(h.cid(), "A", listOf("C"), RecoveryReason.MISSED_RENDEZVOUS)).error.code,
        )

        h.ok(JourneyCommand.ProposeRendezvous(h.cid(), "A", Places.pyeongchang, recoverySessionId = session.id))
        val second = h.state.proposals.last()
        assertNotEquals(first, second.id)
        assertEquals(2, second.planNumber)
        assertEquals(first, second.replacesProposalId)
        assertEquals(RecoveryStatus.REPLANNING, h.state.recoverySessions.single().status)
        // 새 제안이 확정되기 전까지는 기존 계획이 유효
        assertEquals(first, h.state.activePlan()?.id)

        h.ok(JourneyCommand.RespondToProposal(h.cid(), "B", second.id, true))
        val approved = h.ok(JourneyCommand.RespondToProposal(h.cid(), "C", second.id, true))
        assertTrue(approved.events.any { it.message == "새로운 합류 계획이 확정되었어요." })
        assertEquals(second.id, h.state.activePlan()?.id)
        assertEquals(second.id, h.state.proposals.first { it.id == first }.supersededByProposalId)
        assertEquals(RecoveryStatus.RESOLVED, h.state.recoverySessions.single().status)
        // 이전 계획은 더 이상 경로 변경 요청 대상이 아님
        assertEquals(
            DomainError.Code.NO_ACTIVE_PLAN,
            h.rejected(JourneyCommand.ConfirmRendezvousArrival(h.cid(), "A", first)).error.code,
        )
        // 새 계획은 아직 적용 전
        assertTrue(h.state.group.members.all { it.navigationStatus == MemberNavigationStatus.CHANGE_PENDING })
    }

    @Test
    fun `현재 계획 유지와 나중에 확인`() {
        val h = Harness().startThreeVehicles()
        h.approvedRendezvous()
        h.ok(JourneyCommand.OpenRecovery(h.cid(), "C", listOf("C"), RecoveryReason.MISSED_RENDEZVOUS))
        val id = h.state.recoverySessions.single().id
        h.ok(JourneyCommand.DeferRecovery(h.cid(), "A", id))
        assertEquals(RecoveryStatus.DEFERRED, h.state.recoverySessions.single().status)
        h.ok(JourneyCommand.KeepCurrentPlan(h.cid(), "A", id))
        assertEquals(RecoveryStatus.KEPT_CURRENT_PLAN, h.state.recoverySessions.single().status)
        assertEquals(DomainError.Code.RECOVERY_CLOSED, h.rejected(JourneyCommand.DeferRecovery(h.cid(), "A", id)).error.code)
    }

    @Test
    fun `확정된 계획 없이 합류 실패 복구를 열 수 없다`() {
        val h = Harness().startThreeVehicles()
        assertEquals(
            DomainError.Code.NO_ACTIVE_PLAN,
            h.rejected(JourneyCommand.OpenRecovery(h.cid(), "C", listOf("C"), RecoveryReason.MISSED_RENDEZVOUS)).error.code,
        )
    }

    // endregion

    // region 위치 / 연결

    @Test
    fun `위치 갱신이 멈추면 정차 중이 아니라 위치 갱신 중단으로 표시한다`() {
        val h = Harness().startThreeVehicles()
        h.ok(JourneyCommand.SetLocationSharing(h.cid(), "B", true))
        h.ok(JourneyCommand.UpdateLocation(h.cid(), "B", GeoPoint(37.3, 127.6), MovementStatus.STOPPED, h.now))
        assertEquals(VehicleDisplayStatus.STOPPED, h.state.displayStatusOf("B", h.now))
        assertEquals(VehicleDisplayStatus.LOCATION_STALE, h.state.displayStatusOf("B", h.now + 120_000))
    }

    @Test
    fun `위치 공유 동의 없이는 위치를 보낼 수 없고 중단하면 위치가 지워진다`() {
        val h = Harness().startThreeVehicles()
        assertEquals(
            DomainError.Code.LOCATION_SHARING_OFF,
            h.rejected(JourneyCommand.UpdateLocation(h.cid(), "A", GeoPoint(37.0, 127.0), MovementStatus.MOVING, h.now)).error.code,
        )
        h.ok(JourneyCommand.SetLocationSharing(h.cid(), "A", true))
        h.ok(JourneyCommand.UpdateLocation(h.cid(), "A", GeoPoint(37.0, 127.0), MovementStatus.MOVING, h.now))
        h.ok(JourneyCommand.SetLocationSharing(h.cid(), "A", false))
        assertNull(h.state.member("A")!!.location)
        assertEquals(VehicleDisplayStatus.SHARING_OFF, h.state.displayStatusOf("A", h.now))
    }

    @Test
    fun `연결 끊김 상태를 표시한다`() {
        val h = Harness().startThreeVehicles()
        h.ok(JourneyCommand.SetConnection(h.cid(), "server", "B", ConnectionStatus.DISCONNECTED))
        assertEquals(VehicleDisplayStatus.DISCONNECTED, h.state.displayStatusOf("B", h.now))
    }

    // endregion

    // region 종료

    @Test
    fun `모든 차량이 도착을 확인하면 COMPLETED가 되고 이후 요청은 거절된다`() {
        val h = Harness().startThreeVehicles()
        h.ok(JourneyCommand.ConfirmDestinationArrival(h.cid(), "A"))
        h.ok(JourneyCommand.ConfirmDestinationArrival(h.cid(), "B"))
        assertEquals(JourneyStatus.ACTIVE, h.state.group.status)
        h.ok(JourneyCommand.ConfirmDestinationArrival(h.cid(), "C"))
        assertEquals(JourneyStatus.COMPLETED, h.state.group.status)
        assertNotNull(h.state.group.endedAt)
        assertEquals(
            DomainError.Code.SESSION_ENDED,
            h.rejected(JourneyCommand.CreateRequest(h.cid(), "B", "우리 잠깐 쉬었다 가자.")).error.code,
        )
    }

    @Test
    fun `세션 종료 후 받은 요청은 처리하지 않는다`() {
        val h = Harness().startThreeVehicles()
        h.ok(JourneyCommand.ProposeRendezvous(h.cid(), "A", Places.hoengseong))
        h.ok(JourneyCommand.EndJourney(h.cid(), "A"))
        assertEquals(JourneyStatus.ENDED, h.state.group.status)
        assertEquals(ProposalStatus.CANCELLED, h.state.proposals.single().status)
        val id = h.state.proposals.single().id
        assertEquals(
            DomainError.Code.SESSION_ENDED,
            h.rejected(JourneyCommand.RespondToProposal(h.cid(), "B", id, true)).error.code,
        )
    }

    // endregion
}
