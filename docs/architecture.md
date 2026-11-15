# MODO 아키텍처

## 1. 모듈 구성

```
MODO/
├── core-domain/   순수 Kotlin(JVM). 도메인 모델, JourneyEngine(상태 전이), API 계약, 의도 분류
├── app/           Android(Compose). 화면, ViewModel, SDK 추상화와 Pleos/데모 구현체
└── backend/       Ktor 서버. core-domain의 같은 JourneyEngine으로 그룹 상태를 처리
```

핵심 결정: **그룹 규칙을 한 곳(`core-domain`의 `JourneyEngine`)에만 둡니다.** 서버, 앱의 데모 저장소, 단위 테스트가 모두 같은 엔진을 실행하므로 Demo Mode와 실제 서버 모드의 비즈니스 규칙이 다를 수 없습니다.

```
            ┌──────────────── app ────────────────┐
 Compose UI │  presentation (Screens, ViewModel)   │
            │        │ ModoUiState (StateFlow)      │
            │  domain/usecase                       │  RouteActionExecutor, LocationSync, GroupAnnouncer
            │        │                              │
            │  domain/port  ◄── 인터페이스           │  LocationProvider, NavigationController,
            │        │                              │  VoiceCommandController, SpeechOutputController
            │  domain/repository ◄── 인터페이스      │  GroupRepository
            │     ▲            ▲                    │
            │  data/pleos   demo/                   │  실제 SDK 구현 / 시뮬레이션 구현
            │  data/repository(Remote)  demo/DemoGroupRepository
            └──────┬───────────────────────┬────────┘
                   │ REST/폴링             │ (같은 프로세스)
            ┌──────▼──────┐         ┌──────▼──────┐
            │  backend    │         │ JourneyEngine│
            │ GroupService├────────►│ (core-domain)│
            └─────────────┘         └──────────────┘
```

## 2. 패키지 (app)

| 패키지 | 내용 |
|---|---|
| `core/` | `AppContainer`(수동 DI), `ModeEnvironment`(모드별 구현체 묶음), `SettingsStore`(DataStore), `Clock`, `AppMode` |
| `domain/port/` | SDK 추상화 인터페이스 4종 |
| `domain/repository/` | `GroupRepository` 인터페이스 |
| `domain/usecase/` | `RouteActionExecutor`(내 차량 경로 적용·결과 보고), `LocationSync`(동의 시 위치 공유), `GroupAnnouncer`(TTS 안내) |
| `data/pleos/` | `PleosNavigationController`, `RealLocationProvider`, `PleosVoiceCommandController`, `PleosSpeechOutputController` |
| `data/repository/`, `data/remote/` | `RemoteGroupRepository`, `HttpTransport` |
| `demo/` | `DemoGroupRepository`, `DemoFleetReactor`, `DemoScenarioController`, 데모 SDK 구현 4종, `DemoData`, `DemoClock` |
| `presentation/` | `JourneyViewModel`, `ModoUiState`, 화면(`screens/`), 공통 컴포넌트, 테마 |
| `navigation/` | `ModoApp` — 상태 기반 화면 전환 |

요구사항의 인터페이스 이름과 구현체 매핑:

| 인터페이스 | 실제 | 데모 |
|---|---|---|
| `LocationProvider` | `RealLocationProvider` | `DemoLocationProvider` |
| `NavigationController` | `PleosNavigationController` | `DemoNavigationController` |
| `VoiceCommandController` | `PleosVoiceCommandController` | `DemoVoiceCommandController` |
| `SpeechOutputController` | `PleosSpeechOutputController` | `DemoSpeechOutputController` |
| `GroupRepository` | `RemoteGroupRepository` | `DemoGroupRepository` |

두 모드는 같은 `JourneyViewModel`과 같은 도메인 모델을 사용하고, `ModeEnvironment`만 다릅니다.

## 3. 도메인 모델과 상태 전이

`JourneyState` = `JourneyGroup`(+ `VehicleMember` 목록) + `MobilityRequest` + `RendezvousProposal` + `NavigationAction` + `RecoverySession` + 이벤트 타임라인 + `version` + 처리한 명령 id.

모든 변경은 `JourneyCommand`(18종)로만 일어나고, `JourneyEngine.handle(state, command, now)`가 `Applied / Duplicate / Rejected`를 돌려줍니다.

### 3.1 그룹 승인 ≠ 경로 적용

```
RendezvousProposal:  PROPOSED ──응답──► VOTING ──전원 동의──► APPROVED
                                          └──한 대라도 거절──► REJECTED
                     (제안자 취소) ──► CANCELLED

NavigationAction (차량별, APPROVED 이후 DispatchNavigation으로만 생성):
   PENDING ──► USER_CONFIRMATION_REQUIRED ──► REQUESTED ──► APPLIED
      │                    │                       ├──► FAILED ──(RetryNavigation)──► PENDING
      └────────────────────┴───────────────────────┴──► UNKNOWN(시간 초과) ──► APPLIED/FAILED(늦은 결과)
```

- `DispatchNavigation`은 제안이 `APPROVED`가 아니면 거절되며, 응답하지 않은 차량을 메시지로 알려줍니다.
- `PENDING`에서 바로 `APPLIED`로 갈 수 없습니다 (실제 요청 없이 성공 처리 금지).
- 화면은 `RoutePhase`(그룹 합의 완료 / 각 차량 확인 대기 / 내비게이션 요청 중 / 실제 적용 확인 / 실패 및 재시도 필요)로 두 상태를 따로 보여줍니다.

### 3.2 재조율(Recovery)

`OpenRecovery(MISSED_RENDEZVOUS 등)` → `RecoverySession(OPEN)` → 재합류 제안 시 `REPLANNING` + 새 `RendezvousProposal(planNumber+1, replacesProposalId=이전)` → 전원 동의 시 `RESOLVED`, 이전 제안에 `supersededByProposalId` 기록. `KeepCurrentPlan` → `KEPT_CURRENT_PLAN`, `DeferRecovery` → `DEFERRED`.

현재 유효한 계획(`activePlan`)은 "승인됨 + 대체되지 않음 + 아직 모두 합류하지 않음"인 마지막 제안이며, `RouteActionExecutor`는 이 계획의 작업만 내비게이션에 적용합니다 (대체된 이전 계획으로 경로를 바꾸지 않음).

### 3.3 중복·순서 처리

| 상황 | 처리 |
|---|---|
| 같은 `commandId` 재전송 (네트워크 재시도, 중복 탭) | `Duplicate`, 상태 변경 없음 |
| 이미 응답한 차량의 같은 응답 | `Duplicate` + "이미 이 제안에 응답했어요." |
| 결정이 끝난 제안에 늦게 도착한 응답 | `Rejected(PROPOSAL_CLOSED)`, 반영하지 않음 |
| 진행 중인 제안이 있는데 새 제안 | `Rejected(PROPOSAL_ALREADY_ACTIVE)` |
| 같은 차량·같은 유형의 열린 요청 | `Rejected(DUPLICATE_REQUEST)` |
| 오래된 위치(`measuredAt` 역전) | 무시 |
| 시간 초과(UNKNOWN) 후 늦게 온 성공 결과 | 반영 |
| 세션 종료(`COMPLETED`/`ENDED`) 후 명령 | `Rejected(SESSION_ENDED)` |

### 3.4 표시 상태

`displayStatusOf(vehicleId, now)` 우선순위: 도착 확인 > 연결 끊김 > 재조율 필요 > 합류 장소 도착 > 경로 적용 실패 > 내비게이션 변경 대기 > 합류 장소 설정 완료 > 요청 발생 > 위치 공유 꺼짐 > **위치 갱신 중단(60초 이상 갱신 없음)** > 이동 중 / 정차 중. 위치 갱신이 멈춘 차량은 "정차 중"이 아니라 "위치 갱신 중단 · 마지막 위치 HH:mm"으로 표시합니다.

## 4. 그룹 동기화 서버

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/groups` | 그룹 생성 (6자리 초대 코드 발급) |
| POST | `/api/groups/join` | 초대 코드 참여 (잘못된 코드 404, 중복 참가 409) |
| GET | `/api/groups/{id}` · `/members` | 그룹 상태 · 참가자 조회 |
| GET | `/api/groups/{id}/state?since=v&member=m` | 동기화 폴링 (변경 없으면 204). 30초 이상 폴링이 없는 차량은 연결 끊김 처리 |
| POST | `/api/groups/{id}/commands` | 모든 `JourneyCommand` (앱이 사용) |
| POST | `/members/{m}/location-sharing`, `/requests`, `/proposals`, `/proposals/{p}/responses`, `/proposals/{p}/dispatch`, `/navigation-actions/{a}/result`, `/recovery`, `/end` | 기능별 REST (내부적으로 같은 명령으로 변환) |
| WS | `/api/groups/{id}/events` | 상태 변경 푸시 |

규칙 위반은 `409 Conflict` + `CommandResponse(REJECTED, error.message=한국어)`. 저장소는 In-Memory(`ConcurrentHashMap`)이며 재시작 시 사라집니다. 앱 클라이언트는 현재 REST + 2초 폴링(오프라인 시 최대 10초 백오프)을 사용합니다.

## 5. 기술 선택과 이유

| 항목 | 선택 | 이유 |
|---|---|---|
| UI | Jetpack Compose (Material3, BOM 2024.12.01) | Pleos Connect Emulator(Android 14)에서 정상 렌더링·입력 확인. 호환성 문제 없음 |
| DI | 수동 DI (`AppContainer`) | 의존 그래프가 작고 kapt/ksp 없이 빌드를 단순화. Pleos SDK 객체는 LIVE 모드에서만 생성 |
| 화면 전환 | 상태 기반 (`AppScreen` + `Stage`) | 주행 중 조작 최소화 — 그룹 상태에 따라 "지금 필요한 행동"을 자동으로 보여줌 |
| 화면 크기 | `ScaledCanvas` (기준 1280×720dp 비례 확대) | 에뮬레이터 기본 해상도 2560×1440 @mdpi(=2560dp 폭)와 Pleos 창 분할(1:1, portrait)에 동시 대응 |
| 직렬화 | kotlinx.serialization (`@type` 구분자) | 앱·서버 공유 계약 |
| 로컬 저장 | DataStore Preferences | 서버 주소와 무작위 차량 단말 id만 저장 (개인정보 없음) |
| 빌드 | AGP 8.7.3 / Gradle 8.11.1 / Kotlin 2.0.21 / JDK 17 / compileSdk 35 / targetSdk 34 / minSdk 28 | Pleos 요구(minSdk 28, Gradle 8+)와 에뮬레이터 API 34 |
| 릴리스 | R8 축소 + APK Signature Scheme v3 | Pleos SDK(AIDL/Gson/JNA)는 keep 규칙으로 보존, v3는 서명 키 순환(lineage) 지원 |

## 6. 안전·개인정보

- 앱은 차량 운행을 직접 제어하지 않습니다. 목적지 변경 요청만 보내며, LIVE 모드에서는 운전자 확인(`USER_CONFIRMATION_REQUIRED` → "내 차량 경로 적용")을 먼저 받습니다.
- 위치 공유 기본값은 꺼짐이며, 참가자가 스위치로 동의하고 `FUSED_LOCATION` 런타임 권한을 허용한 경우에만 공유됩니다. 공유를 끄면 마지막 위치도 그룹에서 지웁니다.
- 도착은 GPS로 판단하지 않고 사용자가 "도착 확인"을 눌러야 기록됩니다.
- 서명 키, `keystore.properties`, 자격 증명 파일은 `.gitignore` 대상입니다.
- 서버 평문 HTTP는 로컬 개발 주소(10.0.2.2, localhost)만 허용합니다 (`network_security_config.xml`).
