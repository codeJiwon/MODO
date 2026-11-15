package com.modo.domain.engine

import com.modo.domain.model.MobilityRequestType

/**
 * 음성 인식 결과(텍스트)를 이동 요청 유형으로 분류하는 규칙 기반 분류기.
 *
 * Pleos SpeechToText SDK는 텍스트만 반환하고 명령을 실행하지 않으므로,
 * 의도 분류는 앱이 직접 수행한다. 여러 규칙이 겹치면 더 긴급한 유형을 우선한다.
 */
object IntentClassifier {

    private val rules: List<Pair<MobilityRequestType, List<String>>> = listOf(
        MobilityRequestType.MANUAL_RECOVERY_REQUEST to listOf(
            "지나쳤", "놓쳤", "길을잘못", "길잘못", "길을잃", "길잃", "헤매", "도와줘", "도와주", "도움", "못찾",
        ),
        MobilityRequestType.DESTINATION_CHANGE to listOf(
            "목적지바꾸", "목적지변경", "목적지를바꾸", "목적지를변경", "대신가", "로바꾸", "으로바꾸", "다른데로",
        ),
        MobilityRequestType.RENDEZVOUS_REQUEST to listOf(
            "만나", "합류", "모이", "모여", "같이가", "기다려", "기다릴",
        ),
        MobilityRequestType.REST_REQUEST to listOf(
            "쉬었다", "쉬자", "쉬어", "쉴까", "쉬고", "휴식", "휴게소", "화장실", "커피", "배고", "밥먹", "졸려", "피곤", "스트레칭",
        ),
        MobilityRequestType.STATUS_REQUEST to listOf(
            "어디야", "어디쯤", "어디에", "얼마나남", "몇분", "언제도착", "도착했", "상태", "위치알려",
        ),
    )

    /** 분류할 수 없으면 null. */
    fun classify(text: String): MobilityRequestType? {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return null
        return rules.firstOrNull { (_, keywords) -> keywords.any { normalized.contains(it) } }?.first
    }

    fun label(type: MobilityRequestType): String = when (type) {
        MobilityRequestType.REST_REQUEST -> "휴식 요청"
        MobilityRequestType.RENDEZVOUS_REQUEST -> "합류 요청"
        MobilityRequestType.DESTINATION_CHANGE -> "목적지 변경 요청"
        MobilityRequestType.STATUS_REQUEST -> "상태 확인 요청"
        MobilityRequestType.MANUAL_RECOVERY_REQUEST -> "도움 요청"
    }

    private fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }
}
