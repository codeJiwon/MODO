package com.modo.drive.domain.port

import kotlinx.coroutines.flow.StateFlow

data class SpokenLine(val text: String, val at: Long, val simulated: Boolean)

/** 그룹 알림을 음성으로 안내한다. 실제 구현은 Pleos TextToSpeech SDK (ON_DEVICE 모드). */
interface SpeechOutputController {
    val isSimulated: Boolean
    /** 마지막으로 안내한 문장. 데모 모드에서는 소리 대신 화면 자막으로 표시한다. */
    val lastSpoken: StateFlow<SpokenLine?>
    fun speak(text: String)
    fun stop() {}
    fun release() {}
}
