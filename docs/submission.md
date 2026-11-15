# 제출 정보 — MODO (팀 정지원)

## 1. MODO MVP 시연 영상
- 영상 링크: https://github.com/codeJiwon/MODO/blob/main/docs/video/MODO_demo.mp4
- 다중 차량 이동 중 휴식 요청, 합류 장소 제안과 승인, 내비게이션 적용 상태 확인, 합류 실패 및 재조율까지 이어지는 핵심 사용자 시나리오를 시연합니다.
- 영상에 사용된 시뮬레이션 데이터와 실제 SDK 연동 기능은 장면마다 `DEMO · 시뮬레이션 데이터` / `실제 Pleos SDK 연동` 배지로 구분하여 표시했습니다.

## 2. GitHub 소스 코드
- 저장소 URL: https://github.com/codeJiwon/MODO
- Kotlin 기반 Android 앱 소스 코드와 주요 도메인 모델, 사용자 인터페이스, 상태 관리 및 Demo Mode 구현을 포함합니다.

## 3. Android 실행 파일
- APK 위치: `apk/정지원_MODO_release.apk`
- 실행 환경: Android 14 (API 34) · Pleos Connect Emulator v2.1.0 (`system-images;android-34;pleos_car_emulator;arm64-v8a`, 2560×1440 @160dpi). minSdk 28.
- 실행 방법: [README.md](../README.md) 참고

## 4. 주요 화면 자료
- 스크린샷: `docs/screenshots/` (02_journey_room, 03_mobility_intent, 04_smart_rendezvous, 05_group_route_action, 06_group_recovery 외), Pleos Connect Emulator 실제 실행 캡처: `docs/screenshots/emulator/`
- 주요 화면은 Journey Room, Mobility Intent, Smart Rendezvous, Group Route Action, Group Recovery로 구성됩니다.

## 5. 실제 SDK 연동 범위
- 연동 완료 SDK
  - **NaviHelper 3.0.5**: initialize → addListener → requestRoute → 콜백 수신 → getDestinationInfo로 목적지 일치 확인. Pleos Connect Emulator에서 MODO 요청으로 Pleos 내비게이션 앱이 경로 안내를 시작하는 것을 확인 (계측 테스트 통과)
  - **FusedLocation 1.1.3**: 런타임 권한 허용 후 차량 위치 수신 확인 (계측 테스트 통과)
  - **SpeechToText 3.3.1 / TextToSpeech 3.3.1 (ON_DEVICE)**: 서비스 초기화와 요청까지 확인. 실제 발화 인식 결과와 음성 출력은 미검증
- Demo Mode 사용 범위 (시뮬레이션)
  - 차량 B·C의 행동(요청, 동의, 운전자 확인, 합류 실패)과 모든 차량의 위치
  - 차량 B·C의 내비게이션 적용 결과 (내 차량 A는 옵션으로 실제 NaviHelper 사용 가능)
  - 음성 입력 문장 재현, 음성 안내(소리 대신 화면 자막)
  - 그룹 동기화는 서버 없이 같은 상태 엔진을 사용하는 In-Memory 저장소로 처리
- 미구현 및 향후 확장 기능
  - Vehicle SDK 기반 주행 중 조작 제한, NaviHelper 경유지 방식 합류
  - 그룹 서버 영속 저장소·인증, WebSocket 실시간 동기화(서버 엔드포인트는 구현, 앱은 폴링 사용)
  - 실시간 교통·ETA 기반 합류 후보 추천, 복수 차량(에뮬레이터) 동시 연결 실증
