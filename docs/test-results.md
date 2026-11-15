# 테스트 결과

실행일: 2026-11-15 · JDK 17 (Temurin 17.0.16) · Gradle 8.11.1 · macOS (Apple Silicon)

## 요약

| 구분 | 위치 | 실행 환경 | 결과 |
|---|---|---|---|
| 도메인 단위 테스트 | `core-domain/src/test` | JVM | **26 / 26 통과** |
| 앱 통합·단위 테스트 | `app/src/test` | JVM (+ Robolectric) | **22 / 22 통과** |
| UI 테스트 (Compose) | `app/src/sharedTest` | JVM Robolectric (1280×720dp) | **8 / 8 통과** |
| UI 테스트 (Compose) | 같은 코드, `connectedDebugAndroidTest` | Pleos Connect Emulator v2.1.0 | **6 / 8 통과, 2 실패** (아래 참고) |
| Pleos SDK 연동 계측 테스트 | `app/src/androidTest` | Pleos Connect Emulator v2.1.0 | **4 / 4 통과** (STT 실제 인식은 미검증) |
| 서버 통합 테스트 | `backend/src/test` | JVM (Ktor testApplication) | **5 / 5 통과** |
| 서버 스모크 테스트 | `backend/scripts/smoke-test.sh` | 실제 서버 프로세스 + curl | 통과 (아래 출력) |
| Release APK 수동 시연 | `apk/팀명_MODO_release.apk` | Pleos Connect Emulator v2.1.0 | Scene 1~8 전체 실행, 크래시 없음 |

`ScreenshotCaptureTest`(문서용 스크린샷 생성기)는 `-Pmodo.screenshots=true`일 때만 동작하므로 위 집계에서 제외했습니다.

## 실행 방법

```bash
export JAVA_HOME=<JDK 17 경로>
./gradlew :core-domain:test :app:testDebugUnitTest :backend:test   # JVM 전체
./gradlew :app:connectedDebugAndroidTest                            # 에뮬레이터 연결 필요
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.modo.drive.PleosSdkIntegrationTest
```

## 요구사항 대비 테스트 매핑

### Unit Tests
| 요구사항 | 테스트 |
|---|---|
| MobilityRequest 생성 및 유형 분류 | `IntentClassifierTest`(4), `JourneyEngineTest.휴식 요청은 REST_REQUEST로…`, `인식할 수 없는 요청과 중복 요청은 거절된다` |
| 그룹 승인 상태 전환 | `모든 차량이 동의해야 제안이 APPROVED가 된다`, `한 차량이라도 거절하면 REJECTED…`, `제안 취소는 제안자만…` |
| 승인 전 NavigationAction 실행 방지 | `승인 전에는 경로 변경을 요청할 수 없다`, `그룹 승인 완료는 경로 적용 완료가 아니다` |
| 차량별 적용 결과 반영 | `차량별 적용 결과가 각각 반영된다`, `다른 차량의 결과를 보고하거나 잘못된 전이는 거절된다`, `실패와 시간 초과는 재시도로…`, `RouteActionExecutorTest`(3) |
| RecoverySession 생성 | `합류 실패는 RecoverySession을 만들고 새 제안은 이전 계획과 구분된다`, `현재 계획 유지와 나중에 확인`, `확정된 계획 없이 합류 실패 복구를 열 수 없다` |
| 중복 이벤트 처리 | `같은 commandId가 다시 오면 상태를 바꾸지 않는다`, `중복 승인은 상태를 바꾸지 않고…`, `결정이 끝난 제안에 늦게 도착한 응답은 반영하지 않는다` |
| Demo Reset 동작 | `DemoScenarioIntegrationTest.Reset Demo는 최초 상태를 완전히 복구한다`, `같은 시나리오를 다시 실행하면 같은 결과를 재현한다` |

### Integration Tests (`DemoScenarioIntegrationTest`, `RemoteGroupRepositoryTest`, `ApplicationTest`)
| 요구사항 | 테스트 |
|---|---|
| Journey 생성부터 종료까지 | `Journey 생성부터 종료까지 - 전체 시나리오`, `여행 종료 후 요청은 처리하지 않는다`, 서버 `그룹 생성 - 초대 코드 참여 - 참가자 조회` |
| 휴식 요청 → 합류 제안 → 승인 | `휴식 요청 - 합류 제안 - 승인 흐름` |
| 목적지 변경 요청 → 결과 확인 | `목적지 변경 요청 - 차량별 결과 확인 - C 상태 변경`, `내 차량 내비게이션 실패는 성공으로 처리하지 않는다` |
| 합류 실패 → 새로운 제안 생성 | `합류 실패 - RecoverySession - 새로운 제안이 이전 계획과 구분된다` |
| 네트워크 오류 및 복구 | `네트워크 오류 후 같은 명령을 재시도하면 한 번만 반영된다`, `RemoteGroupRepositoryTest`(6: 네트워크 오류/시간 초과/복구 후 재전송, 폴링 동기화, 잘못된 초대 코드, 규칙 위반 메시지), 서버 `일정 시간 응답 없는 차량은 연결 끊김…` |

### UI Tests (`ModoUiFlowTest`)
`welcome_showsBrandAndActions`, `journeyRoom_showsGroupVehiclesAndDemoLabel`, `mobilityIntent_restRequestFromVehicleB`, `smartRendezvous_proposeAndApprove`, `groupRouteAction_separatesAgreementFromApplication`, `groupRecovery_missedRendezvousAndReplan`, `journeyCompleted_showsClosingMessage`, `resetDemo_restoresJourneyRoom`

### 오류 처리 요구사항 대비
| 오류 | 검증 |
|---|---|
| 그룹 참여 실패 / 중복 참가 | 엔진·서버·원격 저장소 테스트, 스모크 테스트(404/409) |
| 위치 공유 거부 | 엔진 테스트(동의 없이 위치 전송 거절), 런타임 권한 거부 시 배너(코드) — 에뮬레이터 수동 확인은 미실시 |
| 네트워크 연결 실패 / 응답 시간 초과 | `RemoteGroupRepositoryTest`, 데모 "네트워크 끊김" |
| 그룹원 연결 끊김 | 서버 presence 테스트, 데모 "B 연결 끊김" |
| 음성 인식 실패 / 인식할 수 없는 명령 | `음성 인식 실패와 인식할 수 없는 명령` |
| 중복 요청 | 엔진 테스트 |
| 승인 전 목적지 변경 / 일부 차량 승인 누락 | `승인 전 경로 변경 시도는 거절되고 누락 차량을 안내한다` (메시지에 "차량 C") |
| 내비게이션 API 실패 / 일부 차량 경로 미적용 | `RouteActionExecutorTest`, 데모 "C 적용 실패/응답 없음" |
| 여행 세션 종료 후 요청 수신 | `세션 종료 후 받은 요청은 처리하지 않는다` |
| 위치 갱신 중단 | `위치 갱신이 멈추면 정차 중이 아니라 위치 갱신 중단으로 표시한다`, `연결 끊김과 위치 갱신 중단을 구분해 표시한다` |

## 에뮬레이터 계측 테스트 상세

### PleosSdkIntegrationTest — 4/4 통과 (logcat `MODO/SdkTest`)
```
NaviHelper requestRoute(평창휴게소) → Applied, service=Connected
FusedLocation first sample=LocationSample(point=GeoPoint(latitude=37.421998333333335, longitude=-122.084), movementStatus=UNKNOWN, ...) availability=Available
TextToSpeech status=연결됨
SpeechToText events=[Error(message=음성 입력 시간이 초과되었어요. 다시 시도해 주세요.)]
```
- STT는 서비스 초기화·요청까지 확인했고, 마이크 입력이 없어 앱 입력 제한 시간 오류로 끝났습니다. **실제 음성 인식은 미검증**입니다.

### ModoUiFlowTest (계측) — 6/8 통과
- 1차 실행: 8개 중 6개 통과. `groupRouteAction_separatesAgreementFromApplication`, `mobilityIntent_restRequestFromVehicleB` 실패 — 원인: `Activity never becomes requested state "[RESUMED, ...]" (last lifecycle transition = "STARTED")`. 테스트용 Activity가 Pleos 홈 화면의 다중 창 환경에서 RESUMED에 도달하지 못함(앱 로직 이전 단계).
- 2차 실행: 에뮬레이터 프로세스가 실행 중 종료(SIGSEGV)되어 6개만 수행, 그중 2개 실패(`groupRouteAction…`, `journeyRoom…`). 결과 파일이 이후 실행으로 덮어써져 개별 원인은 기록하지 못했습니다.
- 같은 테스트 코드는 Robolectric(JVM)에서 8/8 통과합니다. 에뮬레이터에서의 실패는 환경 요인으로 보고 있으나 **확정하지 못했습니다**.

## 서버 스모크 테스트 출력 (실제 프로세스, `PORT=18080`)

```
health: {"status":"ok"}
create: group=grp-1b516093-86d invite=GWRXG3
join (wrong code): 404
join: 200
join again (duplicate): 409
request: APPLIED 차량 B가 휴식을 요청했어요.
same commandId again: DUPLICATE
unrecognized: REJECTED UNRECOGNIZED_COMMAND
dispatch before approval: APPROVAL_INCOMPLETE HTTP409
approve B: APPROVED
dispatch: ['PENDING', 'PENDING']
poll unchanged: 204
members: ['A', 'B']
```

## 테스트로 발견해 수정한 결함

| 결함 | 발견 | 수정 커밋 |
|---|---|---|
| JSON 클래스 구분자 `type`이 `CreateRequest.type`과 충돌해 서버 전송 시 예외 | `RemoteGroupRepositoryTest` | `fix(api): JSON 클래스 구분자를 '@type'으로 변경` |
| 에뮬레이터 NaviHelper가 `onRoutingCompleted`를 보내지 않아 실제 적용된 경로가 UNKNOWN으로 보고됨 | 에뮬레이터 실측 | `fix(sdk): NaviHelper 경로 적용을 목적지 조회로 검증` |
| 기존 경로 취소 콜백(`onRouteCancelled`)을 새 요청 실패로 오인 | 에뮬레이터 실측 | 같은 커밋 |
| 데모 하단 자막·조작 버튼이 콘텐츠와 겹침 | 스크린샷 검토 | `fix(ui): 하단 자막·데모 조작 겹침…` |

## 실행하지 못한 항목

| 항목 | 이유 |
|---|---|
| 실제 음성 발화 인식, TTS 소리 청취 | 에뮬레이터 마이크/오디오 연결 미구성 |
| LIVE 모드 2대 이상 에뮬레이터 동시 그룹 동기화 | 에뮬레이터 1대도 소프트웨어 렌더링으로 불안정(주기적 종료) |
| LIVE 모드에서 위치 권한 거부 화면 수동 확인 | 시간 제약 — 코드 경로만 구현 |
| 실제 차량 | 접근 불가 |
