package com.modo.drive.demo

import com.modo.drive.domain.port.VoiceCommandController
import com.modo.drive.domain.port.VoiceEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 사전에 정한 발화를 단어 단위로 "인식"하는 시뮬레이션 음성 입력. 마이크를 사용하지 않는다. */
class DemoVoiceCommandController(private val stepDelayMs: Long = 380) : VoiceCommandController {
    override val isSimulated: Boolean = true

    @Volatile var nextUtterance: String = DEFAULT_UTTERANCE
    /** 테스트 조작: 다음 인식을 실패로 처리한다. */
    @Volatile var failNext: Boolean = false

    override fun listen(): Flow<VoiceEvent> = flow {
        emit(VoiceEvent.Ready)
        emit(VoiceEvent.Listening)
        if (stepDelayMs > 0) delay(stepDelayMs * 2)
        if (failNext) {
            failNext = false
            emit(VoiceEvent.Error("음성을 인식하지 못했어요. 다시 말씀해 주세요. (시뮬레이션)"))
            return@flow
        }
        val utterance = nextUtterance
        nextUtterance = DEFAULT_UTTERANCE
        val words = utterance.split(" ")
        for (i in 1 until words.size) {
            emit(VoiceEvent.Partial(words.take(i).joinToString(" ")))
            if (stepDelayMs > 0) delay(stepDelayMs)
        }
        emit(VoiceEvent.Final(utterance))
    }

    companion object {
        const val DEFAULT_UTTERANCE = "우리 잠깐 쉬었다 가자."
    }
}
