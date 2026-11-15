package com.modo.backend

import com.modo.domain.api.ApiPaths
import com.modo.domain.api.CommandOutcome
import com.modo.domain.api.CommandResponse
import com.modo.domain.api.CreateGroupRequest
import com.modo.domain.api.ErrorResponse
import com.modo.domain.api.JoinGroupRequest
import com.modo.domain.api.ModoJson
import com.modo.domain.engine.DomainError
import com.modo.domain.engine.RandomIdGenerator
import com.modo.domain.model.JourneyCommand
import com.modo.domain.model.JourneyState
import com.modo.domain.model.NavigationActionStatus
import com.modo.domain.model.Place
import com.modo.domain.model.RecoveryReason
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") { modoModule() }.start(wait = true)
}

fun Application.modoModule(service: GroupService = GroupService(), sweepIntervalMs: Long = 5_000) {
    install(ContentNegotiation) { json(ModoJson) }
    install(WebSockets)
    install(StatusPages) {
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(DomainError.server("요청 형식 오류"))) }
        exception<SerializationException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(DomainError.server("요청 형식 오류"))) }
    }
    if (sweepIntervalMs > 0) {
        launch {
            while (true) {
                delay(sweepIntervalMs)
                service.sweepPresence()
            }
        }
    }
    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }

        // 그룹 생성
        post(ApiPaths.GROUPS) {
            val state = service.create(call.receive<CreateGroupRequest>())
            call.respond(HttpStatusCode.Created, CommandResponse(CommandOutcome.APPLIED, state))
        }
        // 초대 코드 참여 (잘못된 코드, 중복 참가는 도메인 오류로 응답)
        post(ApiPaths.JOIN) {
            val command = call.receive<JoinGroupRequest>().command
            val response = service.join(command)
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse(DomainError.invalidInviteCode()))
            call.respondCommand(response)
        }
        route("/api/groups/{id}") {
            groupRoutes(service)
        }
    }
}

private fun Route.groupRoutes(service: GroupService) {
    // 그룹 상태 조회
    get {
        val state = service.state(call.groupId())?.value ?: return@get call.notFound()
        call.respond(state)
    }
    // 그룹 참가자 조회
    get("/members") {
        val state = service.state(call.groupId())?.value ?: return@get call.notFound()
        call.respond(state.group.members)
    }
    // 그룹 상태 동기화 (폴링). member를 보내면 연결 상태가 갱신된다.
    get("/state") {
        val id = call.groupId()
        val flow = service.state(id) ?: return@get call.notFound()
        call.request.queryParameters["member"]?.let { service.touch(id, it) }
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: -1
        val state = flow.value
        if (state.version > since) call.respond(state) else call.respond(HttpStatusCode.NoContent)
    }
    // 모든 상태 변경 명령 (앱이 사용하는 단일 엔드포인트)
    post("/commands") {
        val command = call.receive<JourneyCommand>()
        call.applyAndRespond(service, command)
    }
    // 실시간 이벤트: 상태가 바뀔 때마다 JourneyState JSON을 보낸다.
    webSocket("/events") {
        val flow = service.state(call.parameters["id"]!!) ?: return@webSocket
        flow.collect { state -> send(Frame.Text(ModoJson.encodeToString(JourneyState.serializer(), state))) }
    }

    // --- 아래는 기능별 REST 엔드포인트. 내부적으로 같은 JourneyCommand로 변환된다. ---

    post("/members/{memberId}/location-sharing") {
        val body = call.receive<LocationSharingBody>()
        call.applyAndRespond(service, JourneyCommand.SetLocationSharing(body.commandId ?: newId(), call.parameters["memberId"]!!, body.enabled))
    }
    post("/requests") {
        val body = call.receive<RequestBody>()
        call.applyAndRespond(service, JourneyCommand.CreateRequest(body.commandId ?: newId(), body.actorId, body.text))
    }
    post("/proposals") {
        val body = call.receive<ProposalBody>()
        call.applyAndRespond(service, JourneyCommand.ProposeRendezvous(body.commandId ?: newId(), body.actorId, body.place, body.requestId, body.recoverySessionId))
    }
    post("/proposals/{proposalId}/responses") {
        val body = call.receive<ResponseBody>()
        call.applyAndRespond(service, JourneyCommand.RespondToProposal(body.commandId ?: newId(), body.actorId, call.parameters["proposalId"]!!, body.approve))
    }
    post("/proposals/{proposalId}/dispatch") {
        val body = call.receive<ActorBody>()
        call.applyAndRespond(service, JourneyCommand.DispatchNavigation(body.commandId ?: newId(), body.actorId, call.parameters["proposalId"]!!))
    }
    post("/navigation-actions/{actionId}/result") {
        val body = call.receive<NavigationResultBody>()
        call.applyAndRespond(
            service,
            JourneyCommand.ReportNavigationResult(body.commandId ?: newId(), body.actorId, call.parameters["actionId"]!!, body.status, body.errorMessage),
        )
    }
    post("/recovery") {
        val body = call.receive<RecoveryBody>()
        call.applyAndRespond(
            service,
            JourneyCommand.OpenRecovery(body.commandId ?: newId(), body.actorId, body.affectedVehicleIds, body.reason, body.suggestedMeetingPoint),
        )
    }
    post("/end") {
        val body = call.receive<ActorBody>()
        call.applyAndRespond(service, JourneyCommand.EndJourney(body.commandId ?: newId(), body.actorId))
    }
}

@Serializable data class ActorBody(val actorId: String, val commandId: String? = null)
@Serializable data class LocationSharingBody(val enabled: Boolean, val commandId: String? = null)
@Serializable data class RequestBody(val actorId: String, val text: String, val commandId: String? = null)
@Serializable data class ProposalBody(
    val actorId: String,
    val place: Place,
    val requestId: String? = null,
    val recoverySessionId: String? = null,
    val commandId: String? = null,
)
@Serializable data class ResponseBody(val actorId: String, val approve: Boolean, val commandId: String? = null)
@Serializable data class NavigationResultBody(
    val actorId: String,
    val status: NavigationActionStatus,
    val errorMessage: String? = null,
    val commandId: String? = null,
)
@Serializable data class RecoveryBody(
    val actorId: String,
    val affectedVehicleIds: List<String>,
    val reason: RecoveryReason,
    val suggestedMeetingPoint: Place? = null,
    val commandId: String? = null,
)

private fun newId() = RandomIdGenerator.next("cmd")

private fun ApplicationCall.groupId(): String = parameters["id"]!!

private suspend fun ApplicationCall.notFound() =
    respond(HttpStatusCode.NotFound, ErrorResponse(GroupService.notFound()))

private suspend fun ApplicationCall.applyAndRespond(service: GroupService, command: JourneyCommand) {
    val response = service.apply(groupId(), command) ?: return notFound()
    respondCommand(response)
}

/** 규칙 위반은 409 Conflict + CommandResponse(REJECTED, 한국어 메시지). */
private suspend fun ApplicationCall.respondCommand(response: CommandResponse) {
    val status = if (response.outcome == CommandOutcome.REJECTED) HttpStatusCode.Conflict else HttpStatusCode.OK
    respond(status, response)
}
