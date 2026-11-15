package com.modo.drive.core

/**
 * DEMO: 서버/차량 없이 사전 정의된 시나리오를 재현한다. 다른 차량의 위치·응답·경로 적용 결과는 시뮬레이션이다.
 * LIVE: 그룹 동기화 서버와 Pleos Connect SDK(NaviHelper, FusedLocation, STT, TTS)를 사용한다.
 */
enum class AppMode { DEMO, LIVE }
