package com.modo.domain

import com.modo.domain.engine.EngineResult
import com.modo.domain.engine.JourneyEngine
import com.modo.domain.engine.SequentialIdGenerator
import com.modo.domain.model.GeoPoint
import com.modo.domain.model.JourneyCommand
import com.modo.domain.model.JourneyState
import com.modo.domain.model.Place
import com.modo.domain.model.VehicleMember
import kotlin.test.assertIs
import kotlin.test.fail

object Places {
    val gangneung = Place("dst-gangneung", "강릉역", "강원특별자치도 강릉시 용지로 176", GeoPoint(37.7644, 128.8995))
    val munmak = Place("rest-munmak", "문막휴게소", "강원 원주시 문막읍", GeoPoint(37.3352, 127.8582))
    val hoengseong = Place("rest-hoengseong", "횡성휴게소", "강원 횡성군 안흥면", GeoPoint(37.4649, 128.1346))
    val pyeongchang = Place("rest-pyeongchang", "평창휴게소", "강원 평창군 용평면", GeoPoint(37.6057, 128.4536))
    val route = listOf(munmak, hoengseong, pyeongchang)
}

/** 테스트용 3대 그룹 (A: 내 차량, B, C) 하네스. */
class Harness {
    val engine = JourneyEngine(SequentialIdGenerator())
    var now = 1_700_000_000_000L
    private var cmd = 0
    lateinit var state: JourneyState
        private set

    fun cid() = "cmd-${++cmd}"

    fun startThreeVehicles(): Harness {
        state = engine.createJourney(
            groupId = "grp-test",
            name = "강릉 여행",
            destination = Places.gangneung,
            inviteCode = "MODO24",
            creator = VehicleMember(id = "A", displayName = "내 차량", vehicleLabel = "A", passengerCount = 3),
            now = now,
        )
        ok(JourneyCommand.Join(cid(), "B", "MODO24", "친구 차량", "B", 3))
        ok(JourneyCommand.Join(cid(), "C", "MODO24", "친구 차량", "C", 2))
        return this
    }

    fun run(command: JourneyCommand): EngineResult {
        now += 1_000
        val result = engine.handle(state, command, now)
        state = result.state
        return result
    }

    fun ok(command: JourneyCommand): EngineResult.Applied {
        val r = run(command)
        if (r is EngineResult.Rejected) fail("expected applied but rejected: ${r.error}")
        return assertIs<EngineResult.Applied>(r)
    }

    fun rejected(command: JourneyCommand): EngineResult.Rejected = assertIs(run(command))

    /** 휴식 요청 → A가 횡성휴게소 제안 → B, C 동의 → 확정. */
    fun approvedRendezvous(place: Place = Places.hoengseong): String {
        ok(JourneyCommand.CreateRequest(cid(), "B", "우리 잠깐 쉬었다 가자."))
        ok(JourneyCommand.ProposeRendezvous(cid(), "A", place))
        val id = state.proposals.last().id
        ok(JourneyCommand.RespondToProposal(cid(), "B", id, true))
        ok(JourneyCommand.RespondToProposal(cid(), "C", id, true))
        return id
    }

    fun actionOf(vehicle: String, proposalId: String) =
        state.navigationActions.last { it.vehicleId == vehicle && it.proposalId == proposalId }
}
