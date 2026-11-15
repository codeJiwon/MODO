package com.modo.drive

import com.modo.domain.engine.RoutePhase
import com.modo.domain.engine.VehicleDisplayStatus
import com.modo.domain.engine.displayStatusOf
import com.modo.domain.engine.activePlan
import com.modo.domain.engine.activeProposal
import com.modo.domain.engine.member
import com.modo.domain.engine.routeSummary
import com.modo.domain.model.ApprovalDecision
import com.modo.domain.model.JourneyState
import com.modo.domain.model.JourneyStatus
import com.modo.domain.model.MemberNavigationStatus
import com.modo.domain.model.MobilityRequestType
import com.modo.domain.model.NavigationActionStatus
import com.modo.domain.model.ProposalStatus
import com.modo.domain.model.RecoveryReason
import com.modo.domain.model.RecoveryStatus
import com.modo.drive.demo.DemoData
import com.modo.drive.presentation.AppScreen
import com.modo.drive.presentation.Banner
import com.modo.drive.presentation.JourneyViewModel
import com.modo.drive.presentation.Stage
import com.modo.drive.presentation.VoiceUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ViewModel + DemoScenarioController + DemoGroupRepository + JourneyEngine을 함께 실행하는 통합 테스트.
 * 화면 없이 Demo Scenario 전체 흐름과 오류 처리를 검증한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoScenarioIntegrationTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var factory: TestEnvironmentFactory
    private lateinit var vm: JourneyViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        factory = TestEnvironmentFactory()
        vm = JourneyViewModel(factory)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val state: JourneyState get() = vm.ui.value.journey!!

    private fun TestScope.start() {
        vm.startDemo()
        advanceUntilIdle()
    }

    private fun TestScope.next(times: Int = 1) = repeat(times) {
        vm.demoNextScene()
        advanceUntilIdle()
    }

    @Test
    fun `Scene 1 - 3대 8명이 각자 이동 중`() = runTest(dispatcher) {
        start()
        val ui = vm.ui.value
        assertEquals(AppScreen.JOURNEY, ui.screen)
        assertEquals(Stage.ROOM, ui.stage)
        assertEquals("강릉 여행", state.group.name)
        assertEquals(listOf("A", "B", "C"), state.group.members.map { it.vehicleLabel })
        assertEquals(8, state.group.members.sumOf { it.passengerCount })
        assertTrue(state.group.members.all { it.locationSharingEnabled && it.location != null })
    }

    @Test
    fun `휴식 요청 - 합류 제안 - 승인 흐름`() = runTest(dispatcher) {
        start()
        next() // Scene 2
        assertEquals(Stage.INTENT, vm.ui.value.stage)
        val request = state.requests.single()
        assertEquals(MobilityRequestType.REST_REQUEST, request.type)
        assertEquals("우리 잠깐 쉬었다 가자.", request.originalText)
        assertEquals("B", request.requesterId)
        assertTrue(state.events.any { it.message == "차량 B가 휴식을 요청했어요." })

        next() // Scene 3
        assertEquals(Stage.RENDEZVOUS, vm.ui.value.stage)
        val proposal = state.activeProposal()!!
        assertEquals(DemoData.hoengseong.id, proposal.location.id)
        assertEquals(ApprovalDecision.APPROVED, proposal.approvals["A"])
        assertEquals(ApprovalDecision.APPROVED, proposal.approvals["B"])
        assertEquals(ApprovalDecision.PENDING, proposal.approvals["C"])

        next() // Scene 4
        val approved = state.proposals.single()
        assertEquals(ProposalStatus.APPROVED, approved.status)
        assertEquals(Stage.ROUTE_ACTION, vm.ui.value.stage)
        // 승인 ≠ 적용: 아직 아무 차량에도 경로가 적용되지 않음
        assertEquals(RoutePhase.AGREED, state.routeSummary(approved.id)!!.phase)
        assertTrue(state.group.members.none { it.navigationStatus == MemberNavigationStatus.RENDEZVOUS_APPLIED })
    }

    @Test
    fun `목적지 변경 요청 - 차량별 결과 확인 - C 상태 변경`() = runTest(dispatcher) {
        start()
        next(4) // Scene 5
        val plan = state.activePlan()!!
        val byVehicle = state.navigationActions.filter { it.proposalId == plan.id }.associate { it.vehicleId to it.status }
        assertEquals(NavigationActionStatus.APPLIED, byVehicle["A"])
        assertEquals(NavigationActionStatus.APPLIED, byVehicle["B"])
        assertEquals(NavigationActionStatus.USER_CONFIRMATION_REQUIRED, byVehicle["C"])
        assertEquals(RoutePhase.WAITING_CONFIRMATION, state.routeSummary(plan.id)!!.phase)
        assertEquals(listOf(plan.location), factory.tools.navigation.requested)

        // 테스트 조작: C 적용 실패 → 재시도 → 운전자 확인 → 적용
        vm.demoSetNavigation(DemoData.C, NavigationActionStatus.FAILED)
        advanceUntilIdle()
        assertEquals(RoutePhase.NEEDS_RETRY, state.routeSummary(plan.id)!!.phase)
        assertEquals(MemberNavigationStatus.CHANGE_FAILED, state.member("C")!!.navigationStatus)

        val cAction = state.navigationActions.last { it.vehicleId == "C" && it.proposalId == plan.id }
        vm.retryNavigation(cAction.id)
        advanceUntilIdle()
        assertEquals(NavigationActionStatus.USER_CONFIRMATION_REQUIRED, state.navigationActions.last { it.id == cAction.id }.status)
        vm.demoSetNavigation(DemoData.C, NavigationActionStatus.REQUESTED)
        advanceUntilIdle()
        assertEquals(RoutePhase.ALL_APPLIED, state.routeSummary(plan.id)!!.phase)
    }

    @Test
    fun `합류 실패 - RecoverySession - 새로운 제안이 이전 계획과 구분된다`() = runTest(dispatcher) {
        start()
        next(5) // Scene 6
        assertEquals(Stage.RECOVERY, vm.ui.value.stage)
        val session = state.recoverySessions.single()
        assertEquals(RecoveryReason.MISSED_RENDEZVOUS, session.reason)
        assertEquals(listOf("C"), session.affectedVehicleIds)
        assertEquals(DemoData.pyeongchang.id, session.proposedMeetingPoint?.id)
        val firstPlanId = session.previousProposalId!!

        next() // Scene 7
        val second = state.activePlan()!!
        assertNotEquals(firstPlanId, second.id)
        assertEquals(2, second.planNumber)
        assertEquals(firstPlanId, second.replacesProposalId)
        assertEquals(second.id, state.proposals.first { it.id == firstPlanId }.supersededByProposalId)
        assertEquals(RecoveryStatus.RESOLVED, state.recoverySessions.single().status)
        assertTrue(state.events.any { it.message == "새로운 합류 계획이 확정되었어요." })
        assertEquals(RoutePhase.ALL_APPLIED, state.routeSummary(second.id)!!.phase)
        assertEquals(Stage.ROUTE_ACTION, vm.ui.value.stage)
    }

    @Test
    fun `Journey 생성부터 종료까지 - 전체 시나리오`() = runTest(dispatcher) {
        start()
        next(8)
        assertEquals(JourneyStatus.COMPLETED, state.group.status)
        assertEquals(AppScreen.COMPLETED, vm.ui.value.screen)
        assertTrue(state.group.members.all { it.arrivalConfirmed })
        assertEquals(8, vm.ui.value.demo!!.currentIndex)
    }

    @Test
    fun `Reset Demo는 최초 상태를 완전히 복구한다`() = runTest(dispatcher) {
        start()
        val initial = state
        next(6)
        vm.demoToggleOffline()
        vm.resetDemo()
        advanceUntilIdle()
        assertEquals(initial, state)
        assertEquals(0, vm.ui.value.demo!!.currentIndex)
        assertEquals(Stage.ROOM, vm.ui.value.stage)
        assertNull(vm.ui.value.banner)
        assertTrue(!vm.ui.value.demo!!.offline)
    }

    @Test
    fun `같은 시나리오를 다시 실행하면 같은 결과를 재현한다`() = runTest(dispatcher) {
        start()
        vm.demoJumpTo(8)
        advanceUntilIdle()
        val first = state
        vm.demoJumpTo(8)
        advanceUntilIdle()
        assertEquals(first, state)
        assertEquals(JourneyStatus.COMPLETED, state.group.status)
    }

    @Test
    fun `승인 전 경로 변경 시도는 거절되고 누락 차량을 안내한다`() = runTest(dispatcher) {
        start()
        next(2) // Scene 3: C 대기
        vm.dispatchNavigation(state.activeProposal()!!.id)
        advanceUntilIdle()
        val banner = vm.ui.value.banner
        assertNotNull(banner)
        assertTrue(banner.message.contains("차량 C"))
        assertTrue(state.navigationActions.isEmpty())
    }

    @Test
    fun `네트워크 오류 후 같은 명령을 재시도하면 한 번만 반영된다`() = runTest(dispatcher) {
        start()
        next(2)
        vm.demoToggleOffline()
        vm.demoRespond(DemoData.C, true)
        advanceUntilIdle()
        assertEquals(ApprovalDecision.PENDING, state.activeProposal()!!.approvals["C"])

        val proposal = state.activeProposal()!!
        vm.cancelProposal(proposal.id) // 내 명령도 실패해야 함
        advanceUntilIdle()
        val banner = vm.ui.value.banner!!
        assertEquals(Banner.Tone.WARNING, banner.tone)
        assertTrue(banner.retryable)

        vm.demoToggleOffline()
        vm.retryLastCommand()
        advanceUntilIdle()
        assertEquals(ProposalStatus.CANCELLED, state.proposals.single().status)
        vm.retryLastCommand() // 재시도할 명령이 없으므로 변화 없음
        advanceUntilIdle()
        assertEquals(1, state.proposals.size)
    }

    @Test
    fun `음성 인식 실패와 인식할 수 없는 명령`() = runTest(dispatcher) {
        start()
        vm.demoFailNextVoice()
        vm.startVoiceRequest()
        advanceUntilIdle()
        assertEquals(VoiceUi.Phase.ERROR, vm.ui.value.voice.phase)
        assertEquals(Stage.INTENT, vm.ui.value.stage)

        vm.demoUnrecognizedVoice()
        vm.startVoiceRequest()
        advanceUntilIdle()
        assertEquals(VoiceUi.Phase.ERROR, vm.ui.value.voice.phase)
        assertTrue(vm.ui.value.voice.error!!.contains("이해하지 못했어요"))
        assertTrue(state.requests.isEmpty())

        vm.startVoiceRequest() // 기본 발화: "우리 잠깐 쉬었다 가자."
        advanceUntilIdle()
        assertEquals(VoiceUi.Phase.IDLE, vm.ui.value.voice.phase)
        assertEquals("A", state.requests.single().requesterId)
    }

    @Test
    fun `내 차량 내비게이션 실패는 성공으로 처리하지 않는다`() = runTest(dispatcher) {
        start()
        vm.demoFailMyNextRoute()
        next(4)
        val plan = state.activePlan()!!
        val mine = state.navigationActions.last { it.vehicleId == "A" && it.proposalId == plan.id }
        assertEquals(NavigationActionStatus.FAILED, mine.status)
        assertNotNull(mine.errorMessage)

        vm.retryNavigation(mine.id)
        advanceUntilIdle()
        assertEquals(NavigationActionStatus.APPLIED, state.navigationActions.last { it.id == mine.id }.status)
    }

    @Test
    fun `연결 끊김과 위치 갱신 중단을 구분해 표시한다`() = runTest(dispatcher) {
        start()
        vm.demoLocationLoss(DemoData.B)
        advanceUntilIdle()
        val now = vm.ui.value.now
        assertEquals(
            VehicleDisplayStatus.LOCATION_STALE,
            state.displayStatusOf("B", now),
        )
        vm.demoToggleConnection(DemoData.B)
        advanceUntilIdle()
        assertEquals(
            VehicleDisplayStatus.DISCONNECTED,
            state.displayStatusOf("B", now),
        )
    }

    @Test
    fun `여행 종료 후 요청은 처리하지 않는다`() = runTest(dispatcher) {
        start()
        vm.endJourney()
        advanceUntilIdle()
        assertEquals(JourneyStatus.ENDED, state.group.status)
        vm.quickRequest(MobilityRequestType.REST_REQUEST)
        advanceUntilIdle()
        assertTrue(vm.ui.value.banner!!.message.contains("종료된 여행"))
        assertTrue(state.requests.isEmpty())
    }
}
