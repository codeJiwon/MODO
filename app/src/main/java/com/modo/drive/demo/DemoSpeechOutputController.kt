package com.modo.drive.demo

import com.modo.drive.core.Clock
import com.modo.drive.domain.port.SpeechOutputController
import com.modo.drive.domain.port.SpokenLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 소리를 내지 않고, 안내 문장을 화면 자막으로만 보여주는 시뮬레이션 TTS. */
class DemoSpeechOutputController(private val clock: Clock) : SpeechOutputController {
    override val isSimulated: Boolean = true
    private val _lastSpoken = MutableStateFlow<SpokenLine?>(null)
    override val lastSpoken: StateFlow<SpokenLine?> = _lastSpoken.asStateFlow()

    override fun speak(text: String) {
        _lastSpoken.value = SpokenLine(text, clock.now(), simulated = true)
    }

    fun clear() {
        _lastSpoken.value = null
    }
}
