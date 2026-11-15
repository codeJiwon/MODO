package com.modo.backend

import com.modo.domain.api.CommandOutcome
import com.modo.domain.api.CommandResponse
import com.modo.domain.api.CreateGroupRequest
import com.modo.domain.api.JoinGroupRequest
import com.modo.domain.api.ModoJson
import com.modo.domain.engine.DomainError
import com.modo.domain.engine.SequentialIdGenerator
import com.modo.domain.model.ConnectionStatus
import com.modo.domain.model.GeoPoint
import com.modo.domain.model.JourneyCommand
import com.modo.domain.model.JourneyState
import com.modo.domain.model.Place
import com.modo.domain.model.ProposalStatus
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.encodeToString
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {

    private val gangneung = Place("dst", "강릉역", "강원특별자치도 강릉시 용지로 176", GeoPoint(37.7644, 128.8995))
    private val hoengseong = Place("rest-h", "횡성휴게소", "강원특별자치도 횡성군 안흥면", GeoPoint(37.4649, 128.1346))

    private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
        install(ContentNegotiation) { json(ModoJson) }
        install(WebSockets)
    }

    private suspend fun HttpClient.command(groupId: String, command: JourneyCommand): Pair<HttpStatusCode, CommandResponse> {
        val r = post("/api/groups/$groupId/commands") {
            contentType(ContentType.Application.Json)
            setBody(ModoJson.encodeToString(JourneyCommand.serializer(), command))
        }
        return r.status to ModoJson.decodeFromString(CommandResponse.serializer(), r.bodyAsText())
    }

    private suspend fun HttpClient.createGroup(): JourneyState {
        val r = post("/api/groups") {
            contentType(ContentType.Application.Json)
            setBody(ModoJson.encodeToString(CreateGroupRequest("강릉 여행", gangneung, "A", "차량 A", "A", 3)))
        }
        assertEquals(HttpStatusCode.Created, r.status)
        return ModoJson.decodeFromString(CommandResponse.serializer(), r.bodyAsText()).state
    }

    private suspend fun HttpClient.join(code: String, id: String, label: String): Pair<HttpStatusCode, String> {
        val r = post("/api/groups/join") {
            contentType(ContentType.Application.Json)
            setBody(ModoJson.encodeToString(JoinGroupRequest(JourneyCommand.Join("join-$id-$code", id, code, "차량 $label", label, 3))))
        }
        return r.status to r.bodyAsText()
    }

    @Test
    fun `그룹 생성 - 초대 코드 참여 - 참가자 조회`() = testApplication {
        application { modoModule(GroupService(ids = SequentialIdGenerator()), sweepIntervalMs = 0) }
        val client = jsonClient()
        val group = client.createGroup()
        assertEquals(6, group.group.inviteCode.length)

        val (badStatus, badBody) = client.join("ZZZZZZ", "B", "B")
        assertEquals(HttpStatusCode.NotFound, badStatus)
        assertTrue(badBody.contains(DomainError.Code.INVALID_INVITE_CODE.name))

        assertEquals(HttpStatusCode.OK, client.join(group.group.inviteCode.lowercase(), "B", "B").first)
        // 중복 참가: 같은 차량이 다른 명령으로 다시 참여
        val dup = client.post("/api/groups/join") {
            contentType(ContentType.Application.Json)
            setBody(ModoJson.encodeToString(JoinGroupRequest(JourneyCommand.Join("join-again", "B", group.group.inviteCode, "차량 B", "B", 3))))
        }
        assertEquals(HttpStatusCode.Conflict, dup.status)
        assertTrue(dup.bodyAsText().contains(DomainError.Code.ALREADY_JOINED.name))

        val members = client.get("/api/groups/${group.group.id}/members").bodyAsText()
        assertTrue(members.contains("\"B\""))
    }

    @Test
    fun `명령 처리 - 승인 전 경로 변경 거절 - 중복 명령`() = testApplication {
        application { modoModule(GroupService(ids = SequentialIdGenerator()), sweepIntervalMs = 0) }
        val client = jsonClient()
        val group = client.createGroup()
        val id = group.group.id
        client.join(group.group.inviteCode, "B", "B")

        val (s1, r1) = client.command(id, JourneyCommand.CreateRequest("c1", "B", "우리 잠깐 쉬었다 가자."))
        assertEquals(HttpStatusCode.OK, s1)
        assertEquals(CommandOutcome.APPLIED, r1.outcome)
        assertEquals(CommandOutcome.DUPLICATE, client.command(id, JourneyCommand.CreateRequest("c1", "B", "우리 잠깐 쉬었다 가자.")).second.outcome)

        val (_, proposed) = client.command(id, JourneyCommand.ProposeRendezvous("c2", "A", hoengseong))
        val proposalId = proposed.state.proposals.single().id
        val (s3, r3) = client.command(id, JourneyCommand.DispatchNavigation("c3", "A", proposalId))
        assertEquals(HttpStatusCode.Conflict, s3)
        assertEquals(DomainError.Code.APPROVAL_INCOMPLETE, r3.error!!.code)

        // 기능별 REST 엔드포인트도 같은 규칙으로 동작
        val resp = client.post("/api/groups/$id/proposals/$proposalId/responses") {
            contentType(ContentType.Application.Json)
            setBody("""{"actorId":"B","approve":true,"commandId":"c4"}""")
        }
        val approved = ModoJson.decodeFromString(CommandResponse.serializer(), resp.bodyAsText())
        assertEquals(ProposalStatus.APPROVED, approved.state.proposals.single().status)
    }

    @Test
    fun `상태 폴링은 변경이 없으면 204를 돌려준다`() = testApplication {
        application { modoModule(GroupService(ids = SequentialIdGenerator()), sweepIntervalMs = 0) }
        val client = jsonClient()
        val group = client.createGroup()
        val id = group.group.id
        assertEquals(HttpStatusCode.NoContent, client.get("/api/groups/$id/state?since=${group.version}&member=A").status)
        client.command(id, JourneyCommand.SetLocationSharing("c1", "A", true))
        assertEquals(HttpStatusCode.OK, client.get("/api/groups/$id/state?since=${group.version}&member=A").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/groups/nope/state?since=0").status)
    }

    @Test
    fun `일정 시간 응답 없는 차량은 연결 끊김으로 표시되고 다시 조회하면 복구된다`() = testApplication {
        var now = 1_000L
        val service = GroupService(clock = { now }, ids = SequentialIdGenerator(), disconnectAfterMs = 30_000)
        application { modoModule(service, sweepIntervalMs = 0) }
        val client = jsonClient()
        val group = client.createGroup()
        client.join(group.group.inviteCode, "B", "B")
        val id = group.group.id

        now += 31_000
        service.touch(id, "A")
        service.sweepPresence()
        val state = service.state(id)!!.value
        assertEquals(ConnectionStatus.DISCONNECTED, state.group.members.first { it.id == "B" }.connectionStatus)
        assertEquals(ConnectionStatus.CONNECTED, state.group.members.first { it.id == "A" }.connectionStatus)

        client.get("/api/groups/$id/state?since=0&member=B")
        assertEquals(ConnectionStatus.CONNECTED, service.state(id)!!.value.group.members.first { it.id == "B" }.connectionStatus)
    }

    @Test
    fun `웹소켓으로 상태 변경을 받는다`() = testApplication {
        application { modoModule(GroupService(ids = SequentialIdGenerator()), sweepIntervalMs = 0) }
        val client = jsonClient()
        val group = client.createGroup()
        val id = group.group.id
        client.webSocket("/api/groups/$id/events") {
            val first = ModoJson.decodeFromString(JourneyState.serializer(), (incoming.receive() as Frame.Text).readText())
            assertEquals(group.version, first.version)
            client.command(id, JourneyCommand.CreateRequest("c1", "A", "우리 잠깐 쉬었다 가자."))
            val next = ModoJson.decodeFromString(JourneyState.serializer(), (incoming.receive() as Frame.Text).readText())
            assertEquals(1, next.requests.size)
        }
    }
}
