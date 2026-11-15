package com.modo.drive.demo

import com.modo.drive.core.Clock
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 데모 시나리오 전용 시계. 실제 시간과 무관하게 고정된 시각에서 시작하고
 * 명령이 처리될 때마다 일정하게 증가하므로, 같은 조작을 반복하면 같은 결과(시각 포함)를 재현한다.
 */
class DemoClock(
    private val start: Long = LocalDateTime.of(2026, 11, 21, 9, 30).atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli(),
) : Clock {
    @Volatile private var current = start

    override fun now(): Long = current

    fun tick(ms: Long = TICK_MS): Long {
        current += ms
        return current
    }

    fun reset() {
        current = start
    }

    companion object {
        const val TICK_MS = 5_000L
    }
}
