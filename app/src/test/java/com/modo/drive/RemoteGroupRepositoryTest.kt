package com.modo.drive

import com.modo.domain.api.ApiPaths
import com.modo.domain.api.CommandOutcome
import com.modo.domain.api.CommandResponse
import com.modo.domain.api.CreateGroupRequest
import com.modo.domain.api.JoinGroupRequest
import com.modo.domain.api.ModoJson
import com.modo.domain.engine.DomainError
import com.modo.domain.engine.EngineResult
import com.modo.domain.engine.JourneyEngine
import com.modo.domain.engine.SequentialIdGenerator
import com.modo.domain.model.JourneyCommand
import com.modo.domain.model.JourneyState
import com.modo.domain.model.VehicleMember
import com.modo.drive.data.remote.HttpResponse
import com.modo.drive.data.remote.HttpTransport
import com.modo.drive.data.repository.RemoteGroupRepository
import com.modo.drive.demo.DemoData
import com.modo.drive.domain.repository.CommandResult
import com.modo.drive.domain.repository.MemberProfile
import com.modo.drive.domain.repository.SessionResult
import com.modo.drive.domain.repository.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 서버 동작을 JourneyEngine으로 흉내 내는 가짜 HTTP 전송 계층. 네트워크 장애를 주입할 수 있다. */
class FakeServerTransport : HttpTransport {
    private val engine = JourneyEngine(SequentialIdGenerator())
    var state: JourneyState? = null
    var failure: IOException? = null
    var now = 1_000L
    var commandCalls = 0

    override suspend fun request(method: String, path: String, body: String?): HttpResponse {
        failure?.let { throw it }
        now += 1_000
        return when {
            method == "POST" && path == ApiPaths.GROUPS -> {
                val req = ModoJson.decodeFromString<CreateGroupRequest>(body!!)
                val created = engine.createJourney(
                    groupId = "grp-1", name = req.name, destination = req.destination, inviteCode = "ABC123",
                    creator = VehicleMember(req.creatorId, req.displayName, req.vehicleLabel, req.passengerCount), now = now,
                )
                state = created
                ok(CommandResponse(CommandOutcome.APPLIED, created))
            }
            method == "POST" && path == ApiPaths.JOIN -> handle(ModoJson.decodeFromString<JoinGroupRequest>(body!!).command)
            method == "POST" && path.endsWith("/commands") -> {
                commandCalls++
                handle(ModoJson.decodeFromString(JourneyCommand.serializer(), body!!))
            }
            method == "GET" && path.contains("/state") -> {
                val since = path.substringAfter("since=").substringBefore("&").toLong()
                val s = state!!
                if (s.version > since) HttpResponse(200, ModoJson.encodeToString(JourneyState.serializer(), s)) else HttpResponse(204, "")
            }
            else -> HttpResponse(404, "")
        }
    }

    /** 다른 차량이 서버에 직접 보낸 명령 (폴링으로 전달되어야 함). */
    fun applyFromOtherDevice(command: JourneyCommand) {
        state = engine.handle(state!!, command, ++now).state
    }

    private fun handle(command: JourneyCommand): HttpResponse {
        val r = engine.handle(state!!, command, now)
        state = r.state
        val response = when (r) {
            is EngineResult.Applied -> CommandResponse(CommandOutcome.APPLIED, r.state, r.events)
            is EngineResult.Duplicate -> CommandResponse(CommandOutcome.DUPLICATE, r.state, error = r.notice)
            is EngineResult.Rejected -> CommandResponse(CommandOutcome.REJECTED, r.state, error = r.error)
        }
        return HttpResponse(if (r is EngineResult.Rejected) 409 else 200, ModoJson.encodeToString(response))
    }

    private fun ok(response: CommandResponse) = HttpResponse(200, ModoJson.encodeToString(response))
}

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteGroupRepositoryTest {

    private val me = MemberProfile("veh-a", "차량 A", "A", 3)

    @Test
    fun `그룹 생성 후 명령이 서버 상태에 반영된다`() = runTest {
        val server = FakeServerTransport()
        val repo = RemoteGroupRepository(server, backgroundScope)
        assertIs<SessionResult.Success>(repo.createJourney("강릉 여행", DemoData.gangneungStation, me))
        assertEquals(SyncStatus.CONNECTED, repo.syncStatus.value)

        val r = repo.send(JourneyCommand.CreateRequest("c1", "veh-a", "우리 잠깐 쉬었다 가자."))
        assertIs<CommandResult.Applied>(r)
        assertEquals(1, repo.state.value!!.requests.size)
    }

    @Test
    fun `네트워크 오류는 실패로 보고되고 복구 후 같은 commandId 재전송은 한 번만 반영된다`() = runTest {
        val server = FakeServerTransport()
        val repo = RemoteGroupRepository(server, backgroundScope)
        repo.createJourney("강릉 여행", DemoData.gangneungStation, me)
        val command = JourneyCommand.CreateRequest("c-retry", "veh-a", "우리 잠깐 쉬었다 가자.")

        server.failure = IOException("no route")
        val failed = assertIs<CommandResult.Failed>(repo.send(command))
        assertEquals(DomainError.Code.NETWORK, failed.error.code)
        assertEquals(SyncStatus.OFFLINE, repo.syncStatus.value)
        assertTrue(repo.state.value!!.requests.isEmpty())

        server.failure = null
        assertIs<CommandResult.Applied>(repo.send(command))
        assertEquals(SyncStatus.CONNECTED, repo.syncStatus.value)
        // 응답을 못 받은 클라이언트가 한 번 더 보내도 중복 반영되지 않음
        assertIs<CommandResult.Duplicate>(repo.send(command))
        assertEquals(1, server.state!!.requests.size)
    }

    @Test
    fun `응답 시간 초과는 TIMEOUT으로 보고된다`() = runTest {
        val server = FakeServerTransport()
        val repo = RemoteGroupRepository(server, backgroundScope)
        repo.createJourney("강릉 여행", DemoData.gangneungStation, me)
        server.failure = SocketTimeoutException("read timed out")
        val failed = assertIs<CommandResult.Failed>(repo.send(JourneyCommand.EndJourney("c1", "veh-a")))
        assertEquals(DomainError.Code.TIMEOUT, failed.error.code)
    }

    @Test
    fun `규칙 위반은 서버의 도메인 오류 메시지를 그대로 전달한다`() = runTest {
        val server = FakeServerTransport()
        val repo = RemoteGroupRepository(server, backgroundScope)
        repo.createJourney("강릉 여행", DemoData.gangneungStation, me)
        val failed = assertIs<CommandResult.Failed>(repo.send(JourneyCommand.CreateRequest("c1", "veh-a", "라디오 켜줘")))
        assertEquals(DomainError.Code.UNRECOGNIZED_COMMAND, failed.error.code)
    }

    @Test
    fun `잘못된 초대 코드로 참여하면 실패한다`() = runTest {
        val server = FakeServerTransport()
        RemoteGroupRepository(server, backgroundScope).createJourney("강릉 여행", DemoData.gangneungStation, me)
        val other = RemoteGroupRepository(server, backgroundScope)
        val r = assertIs<SessionResult.Failure>(other.joinJourney("WRONG1", MemberProfile("veh-b", "차량 B", "B", 3)))
        assertEquals(DomainError.Code.INVALID_INVITE_CODE, r.error.code)
        assertIs<SessionResult.Success>(other.joinJourney("abc123", MemberProfile("veh-b", "차량 B", "B", 3)))
    }

    @Test
    fun `다른 차량의 변경은 폴링으로 받고 오프라인 중에는 마지막 상태를 유지한다`() = runTest {
        val server = FakeServerTransport()
        val repo = RemoteGroupRepository(server, backgroundScope, pollIntervalMs = 1_000)
        repo.createJourney("강릉 여행", DemoData.gangneungStation, me)
        server.applyFromOtherDevice(JourneyCommand.Join("j1", "veh-b", "ABC123", "차량 B", "B", 3))

        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(2, repo.state.value!!.group.members.size)

        server.failure = IOException("down")
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(SyncStatus.OFFLINE, repo.syncStatus.value)
        assertEquals(2, repo.state.value!!.group.members.size)

        server.failure = null
        server.applyFromOtherDevice(JourneyCommand.CreateRequest("r1", "veh-b", "우리 잠깐 쉬었다 가자."))
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(SyncStatus.CONNECTED, repo.syncStatus.value)
        assertEquals(1, repo.state.value!!.requests.size)
        repo.leave()
    }
}
