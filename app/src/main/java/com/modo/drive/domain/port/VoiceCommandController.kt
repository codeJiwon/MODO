package com.modo.drive.domain.port

import kotlinx.coroutines.flow.Flow

sealed interface VoiceEvent {
    data object Ready : VoiceEvent
    data object Listening : VoiceEvent
    data class Partial(val text: String) : VoiceEvent
    data class Final(val text: String) : VoiceEvent
    data class Error(val message: String) : VoiceEvent
}

/**
 * 음성을 텍스트로 변환한다. 명령 해석은 [com.modo.domain.engine.IntentClassifier]가 담당한다.
 * 실제 구현은 Pleos SpeechToText SDK (ON_DEVICE 모드)를 사용한다.
 */
interface VoiceCommandController {
    val isSimulated: Boolean
    /** 한 번의 인식 세션. Final 또는 Error 이후 Flow가 완료된다. */
    fun listen(): Flow<VoiceEvent>
    fun release() {}
}
