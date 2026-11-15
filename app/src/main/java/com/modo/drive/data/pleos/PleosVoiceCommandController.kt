package com.modo.drive.data.pleos

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.util.Log
import com.modo.drive.domain.port.VoiceCommandController
import com.modo.drive.domain.port.VoiceEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Pleos SpeechToText SDK v3.3.1 연동 (ON_DEVICE 모드).
 *
 * ON_DEVICE 모드는 문서상 앱 등록(Client ID/Secret)이 필요하지 않으므로 자격 증명을 저장소에 두지 않는다.
 * 문서 기준 lifecycle: SpeechToText(context, Mode) → initialize(OnInitializeListener) → addListener()
 * → setLocale() → request() → stop() → removeListener() → release().
 */
class PleosVoiceCommandController(
    context: Context,
    private val listenTimeoutMs: Long = 12_000,
) : VoiceCommandController {

    override val isSimulated: Boolean = false

    private val appContext = context.applicationContext
    private var stt: SpeechToText? = null
    private var initialized = false
    private val initMutex = Mutex()

    private suspend fun ensureInitialized(): String? = initMutex.withLock {
        if (initialized && stt != null) return null
        val instance = try {
            SpeechToText(appContext, Mode.ON_DEVICE).also { stt = it }
        } catch (t: Throwable) {
            return "음성 인식 SDK를 사용할 수 없는 환경이에요."
        }
        val result = CompletableDeferred<ErrorType?>()
        try {
            instance.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    result.complete(null)
                }

                override fun onFailure(errorType: ErrorType) {
                    result.complete(errorType)
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "initialize threw", t)
            return "음성 인식 서비스를 시작하지 못했어요."
        }
        val completed = withTimeoutOrNull(INIT_TIMEOUT_MS) { result.await() }
        return when {
            !result.isCompleted -> "음성 인식 서비스 연결 시간이 초과되었어요."
            completed == null -> {
                initialized = true
                instance.setLocale(Locale.KOREA)
                null
            }
            else -> completed.toKoreanMessage()
        }
    }

    override fun listen(): Flow<VoiceEvent> = callbackFlow {
        val initError = ensureInitialized()
        val sdk = stt
        if (initError != null || sdk == null) {
            send(VoiceEvent.Error(initError ?: "음성 인식 서비스를 시작하지 못했어요."))
            close()
            return@callbackFlow
        }
        var finished = false
        var lastPartial = ""
        val listener = object : ResultListener {
            override fun onReady() {
                trySend(VoiceEvent.Ready)
            }

            override fun onStartedRecognition() {
                trySend(VoiceEvent.Listening)
            }

            override fun onUpdated(stt: String, completed: Boolean) {
                if (finished) return
                if (completed) {
                    finished = true
                    trySend(if (stt.isBlank()) VoiceEvent.Error("음성을 인식하지 못했어요. 다시 말씀해 주세요.") else VoiceEvent.Final(stt))
                    close()
                } else {
                    lastPartial = stt
                    trySend(VoiceEvent.Partial(stt))
                }
            }

            override fun onUpdatedEpdData(on: Long, off: Long) = Unit

            override fun onUpdatedRms(rms: Double) = Unit

            override fun onEndedRecognition() {
                if (finished) return
                finished = true
                trySend(
                    if (lastPartial.isNotBlank()) VoiceEvent.Final(lastPartial)
                    else VoiceEvent.Error("음성을 인식하지 못했어요. 다시 말씀해 주세요."),
                )
                close()
            }

            override fun onError(errorType: ErrorType) {
                if (finished) return
                finished = true
                trySend(VoiceEvent.Error(errorType.toKoreanMessage()))
                close()
            }
        }
        sdk.addListener(listener)
        try {
            sdk.request()
        } catch (t: Throwable) {
            finished = true
            trySend(VoiceEvent.Error("음성 인식을 시작하지 못했어요."))
            close()
        }
        launch {
            delay(listenTimeoutMs)
            if (!finished) {
                finished = true
                trySend(VoiceEvent.Error("음성 입력 시간이 초과되었어요. 다시 시도해 주세요."))
                close()
            }
        }
        awaitClose {
            runCatching { sdk.stop() }
            runCatching { sdk.removeListener(listener) }
        }
    }

    override fun release() {
        stt?.let { runCatching { it.release() } }
        stt = null
        initialized = false
    }

    private companion object {
        const val TAG = "MODO/STT"
        const val INIT_TIMEOUT_MS = 8_000L

        fun ErrorType.toKoreanMessage(): String = when (this) {
            ErrorType.MIC_ERROR -> "마이크를 사용할 수 없어요. 마이크 설정을 확인해 주세요."
            ErrorType.PERMISSION_DENIED -> "음성 인식 권한(STT_SERVICE)이 허용되지 않았어요."
            ErrorType.NETWORK_ERROR -> "네트워크 오류로 음성을 인식하지 못했어요."
            ErrorType.TIMEOUT -> "음성 인식 응답 시간이 초과되었어요."
            ErrorType.UNAVAILABLE_LANGUAGE -> "한국어 음성 인식을 사용할 수 없어요."
            ErrorType.ON_DEVICE_ENGINE_ERROR -> "차량 음성 인식 엔진에서 오류가 발생했어요."
            ErrorType.UNAUTHENTICATED -> "음성 인식 앱 인증에 실패했어요."
            ErrorType.NOT_SUPPORTED_API -> "현재 환경에서 지원하지 않는 음성 인식 기능이에요."
            else -> "음성 인식에 실패했어요. 다시 시도해 주세요. ($name)"
        }
    }
}
