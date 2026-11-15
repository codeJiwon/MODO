# Pleos SDK 연동 검증 결과

검증일: 2026-11-15 · 검증 환경: macOS (Apple Silicon, 외장 GPU 없음), Pleos Connect Emulator v2.1.0 (Android 14 / API 34, 2560×1440 @160dpi)

이 문서는 **무엇을 근거로 SDK를 사용했고, 실제로 어디까지 동작을 확인했는지**를 구분해 기록합니다. 확인하지 못한 항목은 "미검증"으로 표시했습니다.

## 1. 검증 방법

| 단계 | 방법 |
|---|---|
| 문서 | document.pleos.ai 의 각 SDK 가이드(Introduction, Importing SDK, Declaring permissions, Initialization, Event listeners, Destinations, Routes, Error handling 등)를 직접 확인 |
| 배포 아티팩트 | 공식 Maven 저장소 `https://nexus-playground.pleos.ai/repository/maven-releases/` 에서 AAR/POM을 받아 `javap`로 공개 클래스·메서드 시그니처를 확인 |
| 컴파일 | 문서의 Kotlin 예제 형태 그대로 호출하는 코드를 작성해 Kotlin 컴파일러로 시그니처/nullability 확인 |
| 실기 동작 | Pleos Connect Emulator v2.1.0 에 설치해 계측 테스트(`PleosSdkIntegrationTest`)와 수동 시연으로 콜백·결과 확인 (logcat 기록) |

## 2. 사용한 SDK와 버전

| SDK | 좌표 | 문서 기준 버전 | 앱 사용 여부 |
|---|---|---|---|
| NaviHelper | `ai.pleos.playground:NaviHelper:3.0.5` | v3.0.5 | ✅ 사용 (경로 변경) |
| FusedLocation | `ai.pleos.playground:FusedLocation:1.1.3` | v1.1.3 | ✅ 사용 (내 차량 위치) |
| SpeechToText | `ai.pleos.playground:SpeechToText:3.3.1` | v3.3.1 | ✅ 사용 (ON_DEVICE 모드) |
| TextToSpeech | `ai.pleos.playground:TextToSpeech:3.3.1` | v3.3.1 | ✅ 사용 (ON_DEVICE 모드) |
| Vehicle | `ai.pleos.playground:Vehicle:2.3.2` | — | ❌ 미적용 (아래 6절) |

인증 정보: STT/TTS는 `Mode.ON_DEVICE`만 사용합니다. 문서상 ON_DEVICE 모드는 앱 등록(Pleos Playground My Project Client ID/Secret)이 필요하지 않으므로 저장소에 자격 증명이 없습니다. 서버 기반(HYBRID/SERVER) 인식은 사용하지 않았습니다.

## 3. SDK별 구현과 확인 결과

### 3.1 NaviHelper — `PleosNavigationController`

문서 lifecycle: `NaviHelper(context)` → `initialize(onConnected, onFailed)` → `addListener()` → `requestRoute(RouteInfo)` → 콜백 → `removeListener()` → `release()`

| 항목 | 근거 | 결과 |
|---|---|---|
| `NaviHelper(Context)` 생성자 | javap | ✅ 일치 |
| `initialize(Function0, Function1<Throwable>)` | 문서 + javap | ✅ 일치, 에뮬레이터에서 `onServiceConnected`, `getServiceVersion = 3.0.4` 확인 |
| `addListener / removeListener / release` | 문서 + javap | ✅ |
| `requestRoute(RouteInfo)` | 문서 + javap | ✅ 에뮬레이터에서 Navi 서비스가 `requestToNaviProvider(RequestRoute) com.modo.drive >> ai.umos.maps.android.navigation.app` 로 전달, **Pleos 내비게이션 앱이 횡성휴게소로 실제 안내 시작** (스크린샷 `screenshots/emulator/e09_navihelper_route_in_pleos_navigation.png`) |
| `getDestinationInfo()` → `onDestinationInfo(DestinationInfo)` | 문서 + javap | ✅ 적용 결과 검증에 사용 |
| `NaviErrorCode` | javap: `CAUSE_LOW_BATTERY`, `CAUSE_SEARCH_ROUTE_OVER_DISTANCE`, `CAUSE_SEARCH_ROUTE`, `CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE`, `CAUSE_SEARCH_TRIP` | ✅ 한국어 메시지로 매핑 (`CAUSE_SEARCH_TRIP`은 문서 표에는 없고 바이트코드에만 존재 → else 분기로 처리) |
| 권한 `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH` | 문서 Declaring permissions | ✅ 설치 시 자동 허용(granted=true) 확인 |
| 계측 테스트 `naviHelper_requestRoute_appliesVerifiedDestination` | 에뮬레이터 | ✅ **통과**: `requestRoute(평창휴게소) → Applied, service=Connected` |

**문서와 실제 동작의 차이 (실측)**

1. 문서 Destinations 표는 `onRoutingCompleted()`를 "경로 탐색이 완료되면 호출"로 설명하지만, Emulator v2.1.0 / Navi service 3.0.4 에서는 `requestRoute` 후 다음 순서로만 콜백이 왔고 `onRoutingCompleted`는 오지 않았습니다.
   ```
   (기존 경로가 있으면) onRouteCancelled → onNavigationStatus → onDestinationInfo(0.0, 0.0)
   onRouteStarted(RouteStartInfo name=횡성휴게소) → onRouteEnded → onNavigationStatus → onRouteStateInfo
   ```
   → 대응: `onRoutingCompleted` 또는 `onRouteStarted`를 받으면 `getDestinationInfo()`로 Navigation 앱의 현재 목적지를 조회하고, 요청 좌표와 **300m 이내일 때만 APPLIED**로 보고합니다. 다르면 FAILED, 확인되지 않으면 UNKNOWN(시간 초과)입니다. `onRouteCancelled`는 이전 경로의 취소일 수 있어 최종 결과로 쓰지 않습니다.
2. `RouteInfo`의 `poiId`, `poiSubId`는 Kotlin에서 non-null(String)입니다(컴파일러 확인). MODO의 합류 후보는 Pleos POI DB id가 없으므로 `poiId = ""`, `poiSubId = "0"` 과 좌표로 요청했고, 에뮬레이터에서 정상 경로 탐색을 확인했습니다.
3. 문서의 Java 예제는 `new RouteInfo(127.03…, 37.48…, …)`로 경도를 먼저 넣지만, 바이트코드 생성자 첫 인자는 `latitude`입니다. MODO는 Kotlin named argument(`latitude = …, longitude = …`)를 사용해 순서 혼동을 피했습니다.
4. 소프트웨어 렌더링 에뮬레이터에서는 `requestRoute` 후 `onRouteStarted`까지 약 18초가 걸렸습니다. 앱 기본 제한 시간은 20초, 계측 테스트는 90초로 설정했습니다.

### 3.2 FusedLocation — `RealLocationProvider`

| 항목 | 근거 | 결과 |
|---|---|---|
| `FusedLocation(Context)`, `isSupported()`, `initialize()`, `release()` | 문서 + javap | ✅ |
| `registerFusedLocationCallback / unregisterFusedLocationCallback(FusedLocationListener)` | 문서 + javap | ✅ |
| `onChanged(LocationData?)`, `onError(Exception)` | 문서(null 가능) | ✅ null은 유효 위치로 처리하지 않음 |
| `LocationData` 필드 | javap: latitude, longitude, altitude, heading, confidence, timestampMillis | 속도 필드가 없어 연속 샘플 간 거리/시간으로 이동 여부 추정 |
| `showLocationAccessBlockedPopup()` | 문서 | 권한 거부 시 호출 |
| 권한 `FUSED_LOCATION` | 문서: protection level dangerous | ✅ 에뮬레이터에서 설치 직후 `granted=false` 확인 → 앱이 위치 공유 동의 시 런타임 권한 요청 |
| 계측 테스트 `fusedLocation_deliversVehicleLocationAfterPermission` | 에뮬레이터 | ✅ **통과**: 첫 위치 `37.42199, -122.084` 수신 (에뮬레이터 기본 GPS 값) |

### 3.3 SpeechToText — `PleosVoiceCommandController`

| 항목 | 근거 | 결과 |
|---|---|---|
| `SpeechToText(Context, Mode)`, `Mode.ON_DEVICE` | 문서 + javap | ✅ |
| `initialize(OnInitializeListener)`, `addListener(ResultListener)`, `setLocale(Locale.KOREA)`, `request()`, `stop()`, `release()` | 문서 + javap | ✅ |
| `ResultListener` 7개 콜백 | 문서 + javap (모두 abstract) | ✅ 전부 구현 |
| `ErrorType` 12개 | 문서 + javap | ✅ 한국어 메시지 매핑 |
| 권한 `STT_SERVICE` | 문서 | ✅ 설치 시 자동 허용 확인 |
| 계측 테스트 `speechToText_onDevice_respondsWithinTimeout` | 에뮬레이터 | ✅ 통과 — 초기화와 `request()`가 오류 없이 진행되었고, 마이크 입력이 없어 앱의 10초 입력 제한 시간 오류로 종료 |
| **실제 음성 인식 결과** | — | ⚠️ **미검증**: 에뮬레이터 마이크 입력(Extended Controls > Microphone)을 연결한 상태에서 발화를 인식시켜 보지 못했습니다. |

인식된 텍스트의 의도 분류는 SDK가 하지 않으므로(문서: "음성 명령 기능을 제공하지 않습니다") `IntentClassifier`(규칙 기반)가 수행합니다.

### 3.4 TextToSpeech — `PleosSpeechOutputController`

| 항목 | 근거 | 결과 |
|---|---|---|
| `TextToSpeech(Context, Mode.ON_DEVICE)`, `initialize(OnInitializeListener)`, `addEventListener(EventListener)`, `setLocale`, `speak(String, Boolean = true)`, `stop()`, `release()` | 문서 + javap | ✅ |
| 권한 `TTS_SERVICE` | 문서 | ✅ 설치 시 자동 허용 확인 |
| 계측 테스트 `textToSpeech_onDevice_initializes` | 에뮬레이터 | ✅ 통과 — `initialize` 성공(`onSuccess`) |
| **실제 소리 출력** | — | ⚠️ **미검증**: 녹화 환경에서 오디오 출력을 청취/녹음하지 않았습니다. |

## 4. 실제 SDK와 데모(시뮬레이션) 구분

| 기능 | LIVE 모드 | DEMO 모드 |
|---|---|---|
| 내 차량 경로 변경 | `PleosNavigationController` (NaviHelper) | `DemoNavigationController` (항상 성공, 지연 0.9초) — Demo 패널에서 **"내 차량(A)에 실제 Pleos NaviHelper 사용"**을 켜면 내 차량만 실제 SDK 사용 |
| 다른 차량(B, C) 경로 결과 | 각 차량 단말이 서버에 보고 | `DemoFleetReactor`가 시뮬레이션 |
| 내 차량 위치 | `RealLocationProvider` (FusedLocation) | `DemoLocationProvider` (보간된 시뮬레이션 좌표) |
| 음성 입력 | `PleosVoiceCommandController` | `DemoVoiceCommandController` (정해진 문장) |
| 음성 안내 | `PleosSpeechOutputController` | `DemoSpeechOutputController` (소리 없이 자막 "음성 안내 시뮬레이션") |
| 그룹 동기화 | `RemoteGroupRepository` → backend | `DemoGroupRepository` (In-Memory, 같은 엔진) |

DEMO 화면에는 항상 `DEMO · Simulated vehicle data` 표시, 상단 `시뮬레이션 동기화`, 경로 개략도에 `시뮬레이션 위치` 문구가 표시됩니다.

## 5. 에뮬레이터 환경 제약 (앱 외부 요인)

| 현상 | 원인(확인/추정) | 영향 |
|---|---|---|
| `-gpu host`에서 화면 전체가 검게 출력 | 확인: Pleos 문서의 권장 사양은 외장 GPU (최소 RTX 3060 / RX 6600M). Apple Silicon 내장 GPU 환경 | 소프트웨어 렌더링(`-gpu swangle_indirect`)으로 대체 |
| Pleos 내비게이션 앱의 지도 영역이 검게 보임 | 추정: 지도 렌더러가 소프트웨어 렌더링에서 그려지지 않음. 로그상 내비 엔진·경로 탐색은 정상 | MODO와 무관. 경로 안내 텍스트·턴 정보는 표시됨 |
| Driving View 차량이 흰색 기본 모델 | 확인: SystemUI가 `CarPropertyConfig` null로 반복 크래시, 차량 HAL의 보안 소켓 접근 SELinux 거부. 추정: Pleos Connect Virtual Console(차량 데이터) 미설정 | MODO와 무관 |
| `-gpu swiftshader_indirect`에서 앱 ANR | 확인: ANR 트레이스상 RenderThread가 `BufferQueueProducer::dequeueBuffer`에서 대기 (기본 내비 앱도 동일 ANR) | swangle 모드에서는 재현되지 않음 |
| 에뮬레이터 프로세스가 5~10분마다 SIGSEGV(139)로 종료 | 확인: 호스트 측 크래시, h264 디코더 경고 다수 | 긴 자동화 실행이 중단됨. 재부팅 후 이어서 검증 |

## 6. 미적용·미검증 항목

| 항목 | 상태 | 이유 / 대안 |
|---|---|---|
| Vehicle SDK | 미적용 | 핵심 시나리오(요청→합의→경로→복구)에 필수가 아니고, 에뮬레이터 VHAL이 설정되지 않아 검증할 수 없었음. 향후 주행 상태(기어/속도)에 따라 조작 가능 범위를 줄이는 데 사용 예정 |
| NaviHelper 경유지(`requestRouteWithWaypoints`, `addWaypoint`) | 미사용 | 합류 장소를 목적지로 설정하는 방식 사용. 원래 목적지를 유지하며 경유지로 추가하는 방식은 향후 검토 |
| `onDestinationArrived`로 도착 자동 판정 | 미사용 | 요구사항에 따라 도착은 사용자 확인으로만 처리 |
| STT 실제 인식, TTS 실제 출력 | 미검증 | 3.3, 3.4절 |
| STT/TTS 서버 모드 | 미사용 | Client ID/Secret 필요 — 저장소에 포함하지 않음 |
| 실제 차량(하드웨어) | 미검증 | 에뮬레이터만 사용 |
| 복수 에뮬레이터 간 그룹 동기화 | 미검증 | 서버와 앱 원격 저장소는 자동 테스트와 서버 프로세스 스모크 테스트로 검증, 2대 이상의 에뮬레이터 동시 연결은 미실행 |
