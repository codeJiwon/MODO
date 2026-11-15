package com.modo.drive.domain.usecase

import com.modo.domain.engine.activePlan
import com.modo.domain.model.JourneyCommand
import com.modo.domain.model.JourneyState
import com.modo.domain.model.NavigationAction
import com.modo.domain.model.NavigationActionStatus
import com.modo.drive.domain.port.NavigationController
import com.modo.drive.domain.port.NavigationResult
import com.modo.drive.domain.repository.CommandResult
import com.modo.drive.domain.repository.GroupRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 내 차량에 배정된 NavigationAction을 실제 내비게이션(또는 데모 내비게이션)에 적용하고 결과를 그룹에 보고한다.
 *
 * - 현재 유효한 합류 계획(activePlan)의 작업만 처리한다. 대체된 이전 계획으로 경로를 바꾸지 않는다.
 * - [requireDriverConfirmation]이 true면 바로 적용하지 않고 운전자 확인(USER_CONFIRMATION_REQUIRED)을 먼저 요청한다.
 * - SDK가 실패하거나 응답하지 않으면 성공으로 보고하지 않는다 (FAILED / UNKNOWN).
 */
class RouteActionExecutor(
    private val scope: CoroutineScope,
    private val repository: GroupRepository,
    private val navigation: () -> NavigationController,
    private val selfId: String,
    private val requireDriverConfirmation: () -> Boolean,
    private val newCommandId: () -> String,
) {
    private val handled = mutableSetOf<String>()
    private val _inFlight = MutableStateFlow<Set<String>>(emptySet())
    val inFlight: StateFlow<Set<String>> = _inFlight.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            repository.state.filterNotNull().collect { process(it) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        handled.clear()
        _inFlight.value = emptySet()
    }

    private fun key(action: NavigationAction) = "${action.id}#${action.attempt}"

    private fun selfAction(state: JourneyState): NavigationAction? {
        val plan = state.activePlan() ?: return null
        return state.navigationActions.lastOrNull { it.proposalId == plan.id && it.vehicleId == selfId }
    }

    private fun process(state: JourneyState) {
        val action = selfAction(state) ?: return
        if (action.status != NavigationActionStatus.PENDING) return
        val key = key(action)
        if (!handled.add(key)) return
        if (requireDriverConfirmation()) {
            scope.launch { report(action.id, NavigationActionStatus.USER_CONFIRMATION_REQUIRED, null) }
        } else {
            execute(action)
        }
    }

    /** 운전자가 내 차량 경로 적용을 확인했을 때 호출한다. */
    fun confirmByDriver(actionId: String): Boolean {
        val state = repository.state.value ?: return false
        val action = selfAction(state)?.takeIf { it.id == actionId } ?: return false
        if (action.status != NavigationActionStatus.USER_CONFIRMATION_REQUIRED && action.status != NavigationActionStatus.PENDING) return false
        handled += key(action)
        execute(action)
        return true
    }

    private fun execute(action: NavigationAction) {
        val key = key(action)
        if (key in _inFlight.value) return
        _inFlight.value = _inFlight.value + key
        scope.launch {
            try {
                if (!report(action.id, NavigationActionStatus.REQUESTED, null)) return@launch
                val result = navigation().requestRoute(action.destination)
                // 결과를 기다리는 동안 계획이 바뀌었으면 이전 계획의 결과로만 기록된다 (엔진이 현재 계획과 구분).
                when (result) {
                    NavigationResult.Applied -> report(action.id, NavigationActionStatus.APPLIED, null)
                    is NavigationResult.Failed -> report(action.id, NavigationActionStatus.FAILED, result.message)
                    is NavigationResult.Timeout -> report(action.id, NavigationActionStatus.UNKNOWN, result.message)
                }
            } finally {
                _inFlight.value = _inFlight.value - key
            }
        }
    }

    /** 네트워크 오류 시 같은 commandId로 재전송한다 (서버에서 중복 처리됨). */
    private suspend fun report(actionId: String, status: NavigationActionStatus, error: String?): Boolean {
        val command = JourneyCommand.ReportNavigationResult(newCommandId(), selfId, actionId, status, error)
        repeat(REPORT_ATTEMPTS) { attempt ->
            when (val r = repository.send(command)) {
                is CommandResult.Applied, is CommandResult.Duplicate -> return true
                is CommandResult.Failed -> {
                    val retryable = r.error.code == com.modo.domain.engine.DomainError.Code.NETWORK ||
                        r.error.code == com.modo.domain.engine.DomainError.Code.TIMEOUT
                    if (!retryable) return false
                    delay(RETRY_BASE_MS * (attempt + 1))
                }
            }
        }
        return false
    }

    /** 내 차량 작업이 처리 중이 아닐 때까지 기다린다 (데모 장면 연속 실행용). */
    suspend fun awaitIdle(timeoutMs: Long = 5_000) {
        withTimeoutOrNull(timeoutMs) {
            repository.state.filterNotNull().first { state ->
                val action = selfAction(state)
                _inFlight.value.isEmpty() &&
                    (action == null || (action.status != NavigationActionStatus.PENDING && action.status != NavigationActionStatus.REQUESTED) ||
                        (action.status == NavigationActionStatus.PENDING && requireDriverConfirmation() && key(action) in handled))
            }
        }
    }

    private companion object {
        const val REPORT_ATTEMPTS = 3
        const val RETRY_BASE_MS = 1_000L
    }
}
