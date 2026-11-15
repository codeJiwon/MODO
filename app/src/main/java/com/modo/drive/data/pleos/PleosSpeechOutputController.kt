package com.modo.drive.data.pleos

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.util.Log
import com.modo.drive.domain.port.SpeechOutputController
import com.modo.drive.domain.port.SpokenLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pleos TextToSpeech SDK v3.3.1 연동 (ON_DEVICE 모드, 앱 등록 불필요).
 *
 * 문서 기준 lifecycle: TextToSpeech(context, Mode) → initialize(OnInitializeListener) → addEventListener()
 * → setLocale() → speak() → stop() → removeEventListener() → release().
 * 초기화가 끝나기 전에 들어온 안내는 마지막 문장 하나만 보관했다가 재생한다.
 */
class PleosSpeechOutputController(context: Context) : SpeechOutputController {

    override val isSimulated: Boolean = false

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var initializing = false
    @Volatile private var pending: String? = null

    private val _lastSpoken = MutableStateFlow<SpokenLine?>(null)
    override val lastSpoken: StateFlow<SpokenLine?> = _lastSpoken.asStateFlow()

    private val _status = MutableStateFlow("초기화 전")
    val status: StateFlow<String> = _status.asStateFlow()

    private val eventListener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() = Unit
        override fun onStop() = Unit
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "TTS onError $errorType")
            _status.value = "음성 안내 오류 ($errorType)"
        }
    }

    private fun ensureInitialized() {
        if (ready || initializing) return
        initializing = true
        val instance = try {
            TextToSpeech(appContext, Mode.ON_DEVICE).also { tts = it }
        } catch (t: Throwable) {
            initializing = false
            _status.value = "음성 안내 SDK를 사용할 수 없는 환경이에요."
            return
        }
        try {
            instance.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    instance.addEventListener(eventListener)
                    instance.setLocale(java.util.Locale.KOREA)
                    ready = true
                    initializing = false
                    _status.value = "연결됨"
                    pending?.let { text ->
                        pending = null
                        speakNow(instance, text)
                    }
                }

                override fun onFailure(errorType: ErrorType) {
                    initializing = false
                    _status.value = "음성 안내 초기화 실패 ($errorType)"
                }
            })
        } catch (t: Throwable) {
            initializing = false
            _status.value = "음성 안내 서비스를 시작하지 못했어요."
        }
    }

    override fun speak(text: String) {
        _lastSpoken.value = SpokenLine(text, System.currentTimeMillis(), simulated = false)
        ensureInitialized()
        val instance = tts
        if (ready && instance != null) speakNow(instance, text) else pending = text
    }

    private fun speakNow(instance: TextToSpeech, text: String) {
        try {
            instance.speak(text)
        } catch (t: Throwable) {
            Log.w(TAG, "speak failed", t)
            _status.value = "음성 안내를 재생하지 못했어요."
        }
    }

    override fun stop() {
        runCatching { tts?.stop() }
    }

    override fun release() {
        tts?.let {
            runCatching { it.removeEventListener(eventListener) }
            runCatching { it.release() }
        }
        tts = null
        ready = false
        initializing = false
    }

    private companion object {
        const val TAG = "MODO/TTS"
    }
}
