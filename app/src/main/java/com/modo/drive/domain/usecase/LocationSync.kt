package com.modo.drive.domain.usecase

import com.modo.domain.engine.member
import com.modo.domain.model.JourneyCommand
import com.modo.drive.domain.port.LocationProvider
import com.modo.drive.domain.repository.GroupRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 내 차량 위치를 그룹에 공유한다. 참가자가 위치 공유에 동의(locationSharingEnabled)한 동안에만 동작한다.
 */
class LocationSync(
    private val scope: CoroutineScope,
    private val repository: GroupRepository,
    private val provider: () -> LocationProvider,
    private val selfId: String,
    private val minIntervalMs: Long,
    private val newCommandId: () -> String,
) {
    private var job: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        if (job != null) return
        job = scope.launch {
            var lastSentAt = Long.MIN_VALUE
            repository.state
                .map { it?.member(selfId)?.locationSharingEnabled == true }
                .distinctUntilChanged()
                .flatMapLatest { enabled -> if (enabled) provider().updates() else emptyFlow() }
                .collect { sample ->
                    if (minIntervalMs > 0 && lastSentAt != Long.MIN_VALUE && sample.measuredAt - lastSentAt < minIntervalMs) return@collect
                    lastSentAt = sample.measuredAt
                    repository.send(
                        JourneyCommand.UpdateLocation(newCommandId(), selfId, sample.point, sample.movementStatus, sample.measuredAt),
                    )
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
