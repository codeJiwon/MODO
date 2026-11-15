package com.modo.drive

import com.modo.domain.engine.activePlan
import com.modo.domain.model.JourneyCommand
import com.modo.domain.model.NavigationActionStatus
import com.modo.domain.model.Place
import com.modo.drive.demo.DemoClock
import com.modo.drive.demo.DemoData
import com.modo.drive.demo.DemoGroupRepository
import com.modo.drive.domain.port.NavigationController
import com.modo.drive.domain.port.NavigationResult
import com.modo.drive.domain.port.NavigationServiceState
import com.modo.drive.domain.usecase.RouteActionExecutor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class RouteActionExecutorTest {

    private class ScriptedNavigation(var result: NavigationResult) : NavigationController {
        override val isSimulated = true
        override val serviceState = MutableStateFlow<NavigationServiceState>(NavigationServiceState.Connected)
        var calls = 0
        override suspend fun requestRoute(destination: Place): NavigationResult {
            calls++
            delay(100)
            return result
        }
    }

    private suspend fun TestScope.approvedPlan(repo: DemoGroupRepository): String {
        repo.reset()
        repo.send(JourneyCommand.ProposeRendezvous(repo.nextCommandId(), "A", DemoData.hoengseong))
        val id = repo.state.value!!.proposals.last().id
        repo.send(JourneyCommand.RespondToProposal(repo.nextCommandId(), "B", id, true))
        repo.send(JourneyCommand.RespondToProposal(repo.nextCommandId(), "C", id, true))
        return id
    }

    private fun TestScope.executor(repo: DemoGroupRepository, nav: NavigationController, confirm: Boolean) =
        RouteActionExecutor(backgroundScope, repo, { nav }, "A", { confirm }, repo::nextCommandId).also { it.start() }

    /** backgroundScope 작업은 advanceUntilIdle()의 대상이 아니므로 시간을 직접 진행한다. */
    private fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    private fun DemoGroupRepository.myStatus(): NavigationActionStatus? {
        val s = state.value!!
        val plan = s.activePlan()!!
        return s.navigationActions.lastOrNull { it.vehicleId == "A" && it.proposalId == plan.id }?.status
    }

    @Test
    fun `SDK가 실패하면 FAILED로 보고하고 APPLIED로 처리하지 않는다`() = runTest {
        val repo = DemoGroupRepository(DemoClock())
        val nav = ScriptedNavigation(NavigationResult.Failed("경로 탐색 서버에서 오류가 발생했어요."))
        executor(repo, nav, confirm = false)
        val plan = approvedPlan(repo)
        repo.send(JourneyCommand.DispatchNavigation(repo.nextCommandId(), "A", plan))
        settle()
        assertEquals(NavigationActionStatus.FAILED, repo.myStatus())
        assertEquals(1, nav.calls)
    }

    @Test
    fun `응답 시간 초과는 UNKNOWN으로 보고한다`() = runTest {
        val repo = DemoGroupRepository(DemoClock())
        executor(repo, ScriptedNavigation(NavigationResult.Timeout("응답 시간 초과")), confirm = false)
        val plan = approvedPlan(repo)
        repo.send(JourneyCommand.DispatchNavigation(repo.nextCommandId(), "A", plan))
        settle()
        assertEquals(NavigationActionStatus.UNKNOWN, repo.myStatus())
    }

    @Test
    fun `운전자 확인이 필요하면 확인 전에는 내비게이션을 호출하지 않는다`() = runTest {
        val repo = DemoGroupRepository(DemoClock())
        val nav = ScriptedNavigation(NavigationResult.Applied)
        val ex = executor(repo, nav, confirm = true)
        val plan = approvedPlan(repo)
        repo.send(JourneyCommand.DispatchNavigation(repo.nextCommandId(), "A", plan))
        settle()
        assertEquals(NavigationActionStatus.USER_CONFIRMATION_REQUIRED, repo.myStatus())
        assertEquals(0, nav.calls)

        val actionId = repo.state.value!!.navigationActions.last { it.vehicleId == "A" }.id
        ex.confirmByDriver(actionId)
        settle()
        assertEquals(NavigationActionStatus.APPLIED, repo.myStatus())
        assertEquals(1, nav.calls)
    }
}
