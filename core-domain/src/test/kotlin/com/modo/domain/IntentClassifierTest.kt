package com.modo.domain

import com.modo.domain.engine.IntentClassifier
import com.modo.domain.model.MobilityRequestType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IntentClassifierTest {

    @Test
    fun `휴식 요청 문장은 REST_REQUEST로 분류된다`() {
        assertEquals(MobilityRequestType.REST_REQUEST, IntentClassifier.classify("우리 잠깐 쉬었다 가자."))
        assertEquals(MobilityRequestType.REST_REQUEST, IntentClassifier.classify("화장실 좀 들르자"))
        assertEquals(MobilityRequestType.REST_REQUEST, IntentClassifier.classify("다음 휴게소에서 커피 마시자"))
    }

    @Test
    fun `합류 목적지변경 상태확인 도움 요청을 구분한다`() {
        assertEquals(MobilityRequestType.RENDEZVOUS_REQUEST, IntentClassifier.classify("앞에서 다 같이 만나자"))
        assertEquals(MobilityRequestType.DESTINATION_CHANGE, IntentClassifier.classify("목적지 바꾸자, 속초로 가자"))
        assertEquals(MobilityRequestType.STATUS_REQUEST, IntentClassifier.classify("다들 어디쯤이야?"))
        assertEquals(MobilityRequestType.MANUAL_RECOVERY_REQUEST, IntentClassifier.classify("휴게소를 지나쳤어"))
    }

    @Test
    fun `도움 요청이 휴식 키워드보다 우선한다`() {
        // "휴게소"가 들어 있어도 "지나쳤"이 있으면 복구 요청이다.
        assertEquals(MobilityRequestType.MANUAL_RECOVERY_REQUEST, IntentClassifier.classify("휴게소 놓쳤어 도와줘"))
    }

    @Test
    fun `인식할 수 없는 문장은 null`() {
        assertNull(IntentClassifier.classify("음악 볼륨 올려"))
        assertNull(IntentClassifier.classify("   "))
        assertNull(IntentClassifier.classify("..."))
    }
}
