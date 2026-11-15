package com.modo.drive.domain.usecase

import com.modo.domain.model.JourneyEventKind
import com.modo.drive.domain.port.SpeechOutputController
import com.modo.drive.domain.repository.GroupRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** 주행 중 화면을 보지 않아도 되도록 중요한 그룹 사건을 음성으로 안내한다. */
class GroupAnnouncer(
    private val scope: CoroutineScope,
    private val repository: GroupRepository,
    private val speech: () -> SpeechOutputController,
    private val selfId: String,
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            var lastSeen: Long? = null
            repository.state.filterNotNull().collect { state ->
                val latest = state.events.lastOrNull()?.sequence ?: 0
                val seen = lastSeen
                if (seen == null || latest < seen) {
                    // 시작 시점 또는 데모 리셋: 과거 사건은 읽지 않는다.
                    lastSeen = latest
                    return@collect
                }
                val fresh = state.events.filter { it.sequence > seen && it.kind in ANNOUNCED && it.actorId != selfId }
                lastSeen = latest
                fresh.lastOrNull()?.let { speech().speak(it.message) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        val ANNOUNCED = setOf(
            JourneyEventKind.REQUEST_CREATED,
            JourneyEventKind.PROPOSAL_CREATED,
            JourneyEventKind.PROPOSAL_APPROVED,
            JourneyEventKind.PROPOSAL_REJECTED,
            JourneyEventKind.RECOVERY_OPENED,
            JourneyEventKind.MEMBER_CONNECTION_CHANGED,
            JourneyEventKind.JOURNEY_COMPLETED,
        )
    }
}
