package com.modo.drive.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.modo.domain.engine.DomainError
import com.modo.domain.engine.RandomIdGenerator
import com.modo.domain.engine.RendezvousSuggester
import com.modo.domain.engine.activePlan
import com.modo.domain.engine.activeProposal
import com.modo.domain.engine.member
import com.modo.domain.model.JourneyCommand
import com.modo.domain.model.JourneyStatus
import com.modo.domain.model.MobilityRequestType
import com.modo.domain.model.NavigationActionStatus
import com.modo.domain.model.Place
import com.modo.domain.model.RecoveryReason
import com.modo.drive.core.AppMode
import com.modo.drive.core.EnvironmentFactory
import com.modo.drive.core.ModeEnvironment
import com.modo.drive.demo.DemoData
import com.modo.drive.demo.DemoScenarioController
import com.modo.drive.domain.port.LocationAvailability
import com.modo.drive.domain.port.NavigationController
import com.modo.drive.domain.port.NavigationServiceState
import com.modo.drive.domain.port.VoiceEvent
import com.modo.drive.domain.repository.CommandResult
import com.modo.drive.domain.repository.MemberProfile
import com.modo.drive.domain.repository.SessionResult
import com.modo.drive.domain.usecase.GroupAnnouncer
import com.modo.drive.domain.usecase.LocationSync
import com.modo.drive.domain.usecase.RouteActionExecutor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * DEMO/LIVE 모드가 공유하는 단일 ViewModel. 모드별 차이는 [ModeEnvironment]의 구현체에만 있다.
 */
class JourneyViewModel(private val factory: EnvironmentFactory) : ViewModel() {

    private val _ui = MutableStateFlow(ModoUiState())
    val ui: StateFlow<ModoUiState> = _ui.asStateFlow()

    private var env: ModeEnvironment? = null
    private var selfId: String = DemoData.SELF
    private var executor: RouteActionExecutor? = null
    private var locationSync: LocationSync? = null
    private var announcer: GroupAnnouncer? = null
    private var scenario: DemoScenarioController? = null
    private val sessionJobs = mutableListOf<Job>()
    private var voiceJob: Job? = null
    private var bannerSeq = 0L
    private var lastFailedCommand: JourneyCommand? = null
    private val dismissedRequestIds = mutableSetOf<String>()
    private val dismissedRecoveryIds = mutableSetOf<String>()
    private val demoMutex = Mutex()
    private var useRealNavigationInDemo = false

    // region 세션 시작/종료

    fun startDemo() {
        viewModelScope.launch {
            val environment = factory.demo()
            val tools = environment.demo ?: return@launch
            selfId = DemoData.SELF
            attach(environment)
            scenario = DemoScenarioController(
                repository = tools.repository,
                clock = tools.clock,
                location = tools.location,
                speech = tools.speech,
                awaitSelfIdle = { executor?.awaitIdle() },
                runVoiceRequest = { actor, utterance -> runVoiceRequest(actor, utterance, tools.voice) },
            )
            scenario!!.reset()
            _ui.update {
                it.copy(
                    screen = AppScreen.JOURNEY,
                    candidates = DemoData.restAreas,
                    demo = demoUi(panelOpen = false),
                )
            }
            refresh()
        }
    }

    fun openCreate() = viewModelScope.launch {
        _ui.update { it.copy(screen = AppScreen.CREATE, serverUrl = factory.settings.serverUrl()) }
    }

    fun openJoin() = viewModelScope.launch {
        _ui.update { it.copy(screen = AppScreen.JOIN, serverUrl = factory.settings.serverUrl()) }
    }

    fun backToWelcome() {
        _ui.update { it.copy(screen = AppScreen.WELCOME, banner = null) }
    }

    fun createLiveJourney(serverUrl: String, name: String, vehicleLabel: String, passengers: Int) {
        if (name.isBlank()) return showBanner("여행 이름을 입력해 주세요.", Banner.Tone.WARNING)
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            factory.settings.setServerUrl(serverUrl)
            val environment = factory.live(serverUrl)
            val me = liveProfile(vehicleLabel, passengers)
            // 기본 공동 목적지: 강릉역 (데모와 같은 좌표, OSM 기준)
            when (val r = environment.repository.createJourney(name.trim(), DemoData.gangneungStation, me)) {
                is SessionResult.Success -> enterLive(environment, me)
                is SessionResult.Failure -> showBanner(r.error.message, Banner.Tone.ERROR)
            }
            _ui.update { it.copy(busy = false) }
        }
    }

    fun joinLiveJourney(serverUrl: String, inviteCode: String, vehicleLabel: String, passengers: Int) {
        if (inviteCode.isBlank()) return showBanner("초대 코드를 입력해 주세요.", Banner.Tone.WARNING)
        if (vehicleLabel.isBlank()) return showBanner("차량 이름(예: B)을 입력해 주세요.", Banner.Tone.WARNING)
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            factory.settings.setServerUrl(serverUrl)
            val environment = factory.live(serverUrl)
            val me = liveProfile(vehicleLabel, passengers)
            when (val r = environment.repository.joinJourney(inviteCode, me)) {
                is SessionResult.Success -> enterLive(environment, me)
                is SessionResult.Failure -> showBanner(r.error.message, Banner.Tone.ERROR)
            }
            _ui.update { it.copy(busy = false) }
        }
    }

    private suspend fun liveProfile(label: String, passengers: Int) = MemberProfile(
        vehicleId = factory.settings.vehicleId(),
        displayName = "차량 ${label.uppercase()}",
        vehicleLabel = label.trim().uppercase(),
        passengerCount = passengers.coerceIn(1, 9),
    )

    private fun enterLive(environment: ModeEnvironment, me: MemberProfile) {
        selfId = me.vehicleId
        attach(environment)
        _ui.update { it.copy(screen = AppScreen.JOURNEY, candidates = DemoData.restAreas, demo = null) }
        refresh()
        startLiveTicker()
    }

    private fun attach(environment: ModeEnvironment) {
        detach()
        env = environment
        dismissedRequestIds.clear()
        dismissedRecoveryIds.clear()
        val idGen: () -> String = { environment.demo?.repository?.nextCommandId() ?: RandomIdGenerator.next("cmd") }
        executor = RouteActionExecutor(
            scope = viewModelScope,
            repository = environment.repository,
            navigation = ::currentNavigation,
            selfId = selfId,
            requireDriverConfirmation = { environment.requireDriverConfirmation },
            newCommandId = idGen,
        ).also { it.start() }
        locationSync = LocationSync(viewModelScope, environment.repository, { environment.location }, selfId, environment.locationIntervalMs, idGen)
            .also { it.start() }
        announcer = GroupAnnouncer(viewModelScope, environment.repository, { environment.speech }, selfId).also { it.start() }

        sessionJobs += viewModelScope.launch { environment.repository.state.collect { refresh() } }
        sessionJobs += viewModelScope.launch { environment.repository.syncStatus.collect { refresh() } }
        sessionJobs += viewModelScope.launch { environment.speech.lastSpoken.collect { refresh() } }
        sessionJobs += viewModelScope.launch { executor!!.inFlight.collect { refresh() } }
        sessionJobs += viewModelScope.launch { environment.location.availability.collect { refresh() } }
        sessionJobs += viewModelScope.launch { environment.navigation.serviceState.collect { refresh() } }
        _ui.update { it.copy(mode = environment.mode, selfId = selfId) }
    }

    private fun detach() {
        executor?.stop()
        locationSync?.stop()
        announcer?.stop()
        voiceJob?.cancel()
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        executor = null
        locationSync = null
        announcer = null
        scenario = null
        env = null
    }

    private fun startLiveTicker() {
        sessionJobs += viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                refresh()
            }
        }
    }

    fun leaveJourney() {
        viewModelScope.launch {
            env?.repository?.leave()
            detach()
            _ui.value = ModoUiState()
        }
    }

    // endregion

    // region 상태 → UI

    private fun currentNavigation(): NavigationController {
        val environment = env ?: error("no session")
        return if (environment.mode == AppMode.DEMO && useRealNavigationInDemo) factory.pleosNavigation() else environment.navigation
    }

    private fun refresh() {
        val environment = env ?: return
        val state = environment.repository.state.value
        _ui.update { current ->
            val pickerOpen = current.pickerOpen && state?.activeProposal() == null && state?.activePlan() == null
            val screen = when {
                state == null -> current.screen
                state.group.status == JourneyStatus.COMPLETED -> AppScreen.COMPLETED
                current.screen == AppScreen.COMPLETED -> AppScreen.JOURNEY
                else -> current.screen
            }
            current.copy(
                screen = screen,
                journey = state,
                now = environment.clock.now(),
                syncStatus = environment.repository.syncStatus.value,
                spoken = environment.speech.lastSpoken.value,
                pickerOpen = pickerOpen,
                stage = deriveStage(state, current.voice, pickerOpen, dismissedRequestIds, dismissedRecoveryIds),
                sdk = sdkStatus(environment),
                demo = current.demo?.let { demoUi(it.panelOpen, it.running) },
                selfActionInFlight = executor?.inFlight?.value?.isNotEmpty() == true,
            )
        }
    }

    private fun sdkStatus(environment: ModeEnvironment): SdkStatusUi {
        val navigation = currentNavigation()
        val nav = if (navigation.isSimulated) "시뮬레이션" else when (val s = navigation.serviceState.value) {
            NavigationServiceState.NotInitialized -> "NaviHelper 대기 (첫 경로 요청 시 연결)"
            NavigationServiceState.Connecting -> "NaviHelper 연결 중"
            NavigationServiceState.Connected -> "NaviHelper 연결됨"
            is NavigationServiceState.Unavailable -> "NaviHelper 사용 불가 · ${s.message}"
        }
        val location = if (environment.location.isSimulated) "시뮬레이션" else when (val s = environment.location.availability.value) {
            LocationAvailability.Idle -> "FusedLocation 대기 (위치 공유 동의 시 시작)"
            LocationAvailability.Available -> "FusedLocation 수신 중"
            LocationAvailability.NotSupported -> "FusedLocation 미지원 환경"
            is LocationAvailability.Blocked -> s.message
            is LocationAvailability.Error -> s.message
        }
        return SdkStatusUi(
            navigation = nav,
            location = location,
            voice = if (environment.voice.isSimulated) "시뮬레이션" else "SpeechToText (ON_DEVICE)",
            speech = if (environment.speech.isSimulated) "시뮬레이션 (자막)" else "TextToSpeech (ON_DEVICE)",
        )
    }

    private fun demoUi(panelOpen: Boolean, running: Boolean = false): DemoUi? {
        val s = scenario ?: return null
        val tools = env?.demo ?: return null
        return DemoUi(
            scenes = s.scenes,
            currentIndex = s.currentIndex.value,
            running = running,
            panelOpen = panelOpen,
            offline = tools.repository.simulateOffline,
            useRealNavigation = useRealNavigationInDemo,
        )
    }

    // endregion

    // region 명령 전송

    private fun newCommandId(): String = env?.demo?.repository?.nextCommandId() ?: RandomIdGenerator.next("cmd")

    private fun send(command: JourneyCommand, onApplied: () -> Unit = {}) {
        val environment = env ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            when (val r = environment.repository.send(command)) {
                is CommandResult.Applied -> {
                    lastFailedCommand = null
                    onApplied()
                }
                is CommandResult.Duplicate -> r.notice?.let { showBanner(it.message, Banner.Tone.INFO) }
                is CommandResult.Failed -> handleFailure(command, r.error)
            }
            _ui.update { it.copy(busy = false) }
            refresh()
        }
    }

    private fun handleFailure(command: JourneyCommand, error: DomainError) {
        val retryable = error.code == DomainError.Code.NETWORK || error.code == DomainError.Code.TIMEOUT
        lastFailedCommand = if (retryable) command else null
        showBanner(error.message, if (retryable) Banner.Tone.WARNING else Banner.Tone.ERROR, retryable)
    }

    /** 네트워크 오류로 실패한 명령을 같은 commandId로 다시 보낸다 (중복 반영되지 않음). */
    fun retryLastCommand() {
        val command = lastFailedCommand ?: return
        dismissBanner()
        send(command)
    }

    private fun showBanner(message: String, tone: Banner.Tone, retryable: Boolean = false) {
        _ui.update { it.copy(banner = Banner(++bannerSeq, message, tone, retryable)) }
    }

    fun dismissBanner() = _ui.update { it.copy(banner = null) }

    // endregion

    // region Mobility Intent

    /** 내 차량에서 음성으로 요청한다. */
    fun startVoiceRequest() {
        val environment = env ?: return
        runVoiceRequestAsync(selfId, null, environment.voice)
    }

    /** 데모: 다른 차량의 음성 요청 장면을 재현한다. */
    fun simulateVoiceRequestFrom(vehicleId: String) {
        val tools = env?.demo ?: return
        runVoiceRequestAsync(vehicleId, com.modo.drive.demo.DemoVoiceCommandController.DEFAULT_UTTERANCE, tools.voice)
    }

    private fun runVoiceRequestAsync(actorId: String, utterance: String?, controller: com.modo.drive.domain.port.VoiceCommandController) {
        voiceJob?.cancel()
        voiceJob = viewModelScope.launch { runVoiceRequest(actorId, utterance, controller) }
    }

    private suspend fun runVoiceRequest(actorId: String, utterance: String?, controller: com.modo.drive.domain.port.VoiceCommandController) {
        val environment = env ?: return
        if (utterance != null && controller is com.modo.drive.demo.DemoVoiceCommandController) controller.nextUtterance = utterance
        setVoice(VoiceUi(VoiceUi.Phase.LISTENING, actorId, "", null, controller.isSimulated))
        var finalText: String? = null
        controller.listen().collect { event ->
            when (event) {
                VoiceEvent.Ready, VoiceEvent.Listening -> Unit
                is VoiceEvent.Partial -> setVoice(_ui.value.voice.copy(transcript = event.text))
                is VoiceEvent.Final -> {
                    finalText = event.text
                    setVoice(_ui.value.voice.copy(transcript = event.text))
                }
                is VoiceEvent.Error -> setVoice(_ui.value.voice.copy(phase = VoiceUi.Phase.ERROR, error = event.message))
            }
        }
        val text = finalText ?: return
        when (val r = environment.repository.send(JourneyCommand.CreateRequest(newCommandId(), actorId, text))) {
            is CommandResult.Applied -> setVoice(VoiceUi())
            is CommandResult.Duplicate -> setVoice(VoiceUi())
            is CommandResult.Failed -> {
                if (r.error.code == DomainError.Code.UNRECOGNIZED_COMMAND) {
                    setVoice(_ui.value.voice.copy(phase = VoiceUi.Phase.ERROR, error = r.error.message))
                } else {
                    setVoice(VoiceUi())
                    handleFailure(JourneyCommand.CreateRequest(newCommandId(), actorId, text), r.error)
                }
            }
        }
        refresh()
    }

    private fun setVoice(voice: VoiceUi) {
        _ui.update { it.copy(voice = voice) }
        refresh()
    }

    fun cancelVoice() {
        voiceJob?.cancel()
        setVoice(VoiceUi())
    }

    /** 음성 대신 버튼으로 요청한다 (주행 중 간편 입력). */
    fun quickRequest(type: MobilityRequestType) {
        val text = when (type) {
            MobilityRequestType.REST_REQUEST -> "잠깐 쉬었다 가요."
            MobilityRequestType.RENDEZVOUS_REQUEST -> "다 같이 만나요."
            MobilityRequestType.STATUS_REQUEST -> "다들 어디쯤이에요?"
            MobilityRequestType.DESTINATION_CHANGE -> "목적지를 바꿔요."
            MobilityRequestType.MANUAL_RECOVERY_REQUEST -> "도움이 필요해요."
        }
        send(JourneyCommand.CreateRequest(newCommandId(), selfId, text, type))
    }

    fun dismissRequest(requestId: String) {
        dismissedRequestIds += requestId
        refresh()
    }

    // endregion

    // region Smart Rendezvous

    fun openRendezvousPicker() {
        _ui.update { it.copy(pickerOpen = true) }
        refresh()
    }

    fun closeRendezvousPicker() {
        _ui.update { it.copy(pickerOpen = false) }
        refresh()
    }

    fun propose(place: Place, recoverySessionId: String? = null) {
        send(JourneyCommand.ProposeRendezvous(newCommandId(), selfId, place, recoverySessionId = recoverySessionId)) {
            _ui.update { it.copy(pickerOpen = false) }
        }
    }

    fun respond(proposalId: String, approve: Boolean) =
        send(JourneyCommand.RespondToProposal(newCommandId(), selfId, proposalId, approve))

    fun cancelProposal(proposalId: String) = send(JourneyCommand.CancelProposal(newCommandId(), selfId, proposalId))

    // endregion

    // region Group Route Action

    fun dispatchNavigation(proposalId: String) = send(JourneyCommand.DispatchNavigation(newCommandId(), selfId, proposalId))

    fun confirmMyRoute(actionId: String) {
        if (executor?.confirmByDriver(actionId) != true) showBanner("지금은 경로를 적용할 수 없어요.", Banner.Tone.WARNING)
    }

    fun retryNavigation(actionId: String) = send(JourneyCommand.RetryNavigation(newCommandId(), selfId, actionId))

    fun confirmRendezvousArrival(proposalId: String) =
        send(JourneyCommand.ConfirmRendezvousArrival(newCommandId(), selfId, proposalId))

    fun confirmDestinationArrival() = send(JourneyCommand.ConfirmDestinationArrival(newCommandId(), selfId))

    // endregion

    // region Group Recovery

    /** 내 차량이 합류하지 못했거나 경로 적용에 실패했을 때 재조율을 요청한다. */
    fun reportMyRecovery(reason: RecoveryReason) {
        send(
            JourneyCommand.OpenRecovery(
                commandId = newCommandId(),
                actorId = selfId,
                affectedVehicleIds = listOf(selfId),
                reason = reason,
                suggestedMeetingPoint = suggestFor(listOf(selfId)),
            ),
        )
    }

    fun suggestFor(vehicleIds: List<String>): Place? {
        val state = env?.repository?.state?.value ?: return null
        return RendezvousSuggester.suggest(
            candidatesInRouteOrder = _ui.value.candidates,
            destination = state.group.destination,
            affectedLocations = vehicleIds.mapNotNull { state.member(it)?.location },
            exclude = setOfNotNull(state.activePlan()?.location?.id),
        )
    }

    fun keepCurrentPlan(sessionId: String) = send(JourneyCommand.KeepCurrentPlan(newCommandId(), selfId, sessionId))

    fun deferRecovery(sessionId: String) = send(JourneyCommand.DeferRecovery(newCommandId(), selfId, sessionId))

    // endregion

    // region 위치 공유 / 설정

    /** 참가자의 명시적인 동의 결과를 반영한다. LIVE 모드에서는 권한 요청 후 호출된다. */
    fun setLocationSharing(enabled: Boolean, permissionGranted: Boolean = true) {
        if (enabled && !permissionGranted) {
            env?.location?.showAccessBlockedNotice()
            return showBanner("위치 권한이 거부되어 내 위치를 공유하지 않아요. 그룹에는 '위치 공유 꺼짐'으로 표시돼요.", Banner.Tone.WARNING)
        }
        send(JourneyCommand.SetLocationSharing(newCommandId(), selfId, enabled))
    }

    fun toggleSettings(open: Boolean) = _ui.update { it.copy(settingsOpen = open) }

    fun endJourney() = send(JourneyCommand.EndJourney(newCommandId(), selfId)) {
        _ui.update { it.copy(settingsOpen = false) }
    }

    // endregion

    // region Demo Controller

    fun toggleDemoPanel(open: Boolean) = _ui.update { it.copy(demo = it.demo?.copy(panelOpen = open)) }

    private fun demoAction(block: suspend (DemoScenarioController) -> List<DomainError>) {
        val s = scenario ?: return
        viewModelScope.launch {
            demoMutex.withLock {
                _ui.update { it.copy(demo = it.demo?.copy(running = true)) }
                val errors = block(s)
                _ui.update { it.copy(demo = it.demo?.copy(running = false)) }
                errors.firstOrNull()?.let { showBanner(it.message, Banner.Tone.WARNING) }
                refresh()
            }
        }
    }

    fun demoNextScene() = demoAction { it.next() }

    fun demoJumpTo(index: Int) = demoAction {
        voiceJob?.cancel()
        setVoice(VoiceUi())
        resetLocalUi()
        it.jumpTo(index)
    }

    fun resetDemo() = demoAction {
        voiceJob?.cancel()
        setVoice(VoiceUi())
        resetLocalUi()
        env?.demo?.navigation?.failNextRequest = false
        env?.demo?.voice?.failNext = false
        it.reset()
        emptyList()
    }

    private fun resetLocalUi() {
        dismissedRequestIds.clear()
        dismissedRecoveryIds.clear()
        lastFailedCommand = null
        _ui.update { it.copy(banner = null, pickerOpen = false, screen = AppScreen.JOURNEY) }
        // 실행 기록(handled)을 지워 같은 action id를 다시 처리할 수 있게 한다.
        executor?.stop()
        executor?.start()
    }

    fun demoRespond(vehicleId: String, approve: Boolean) = demoAction { it.respondAs(vehicleId, approve) }

    fun demoSetNavigation(vehicleId: String, status: NavigationActionStatus) =
        demoAction { it.setVehicleNavigation(vehicleId, status) }

    fun demoMissedRendezvous(vehicleId: String) = demoAction { it.missedRendezvous(vehicleId) }

    fun demoToggleConnection(vehicleId: String) = demoAction { it.toggleConnection(vehicleId) }

    fun demoLocationLoss(vehicleId: String) = demoAction { it.simulateLocationLoss(vehicleId) }

    fun demoToggleOffline() {
        val tools = env?.demo ?: return
        tools.repository.simulateOffline = !tools.repository.simulateOffline
        refresh()
    }

    fun demoFailNextVoice() {
        env?.demo?.voice?.failNext = true
        showBanner("다음 음성 인식이 실패하도록 설정했어요. (시뮬레이션)", Banner.Tone.INFO)
    }

    fun demoUnrecognizedVoice() {
        env?.demo?.voice?.nextUtterance = "라디오 볼륨 좀 올려줘"
        showBanner("다음 음성 입력을 '인식할 수 없는 명령'으로 설정했어요. (시뮬레이션)", Banner.Tone.INFO)
    }

    fun demoFailMyNextRoute() {
        env?.demo?.navigation?.failNextRequest = true
        showBanner("내 차량의 다음 경로 요청이 실패하도록 설정했어요. (시뮬레이션)", Banner.Tone.INFO)
    }

    fun demoToggleRealNavigation() {
        useRealNavigationInDemo = !useRealNavigationInDemo
        refresh()
    }

    // endregion

    override fun onCleared() {
        detach()
        super.onCleared()
    }
}
