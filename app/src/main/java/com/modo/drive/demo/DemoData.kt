package com.modo.drive.demo

import com.modo.domain.engine.JourneyEngine
import com.modo.domain.model.GeoPoint
import com.modo.domain.model.JourneyCommand
import com.modo.domain.model.JourneyState
import com.modo.domain.model.MovementStatus
import com.modo.domain.model.Place
import com.modo.domain.model.VehicleMember

/**
 * 데모 시나리오 데이터.
 *
 * 장소 좌표는 OpenStreetMap(Nominatim)에서 2026-11-15에 조회한 값이다.
 * 차량 위치는 이 지점들 사이를 직선 보간한 **시뮬레이션 값**이며 실제 차량 위치가 아니다.
 * 확인되지 않은 ETA/소요 시간은 화면에 표시하지 않는다.
 */
object DemoData {
    const val GROUP_ID = "grp-demo-gangneung"
    const val INVITE_CODE = "MODO24"
    const val SELF = "A"
    const val B = "B"
    const val C = "C"

    val gangneungStation = Place(
        id = "dst-gangneung-station",
        name = "강릉역",
        address = "강원특별자치도 강릉시 용지로 176",
        location = GeoPoint(37.7644065, 128.8995175),
        description = "공동 목적지",
    )

    val munmak = Place(
        id = "rest-munmak-gangneung",
        name = "문막휴게소",
        address = "강원특별자치도 원주시 문막읍",
        location = GeoPoint(37.3352128, 127.8581947),
        description = "영동고속도로 · 강릉방향",
    )

    val hoengseong = Place(
        id = "rest-hoengseong",
        name = "횡성휴게소",
        address = "강원특별자치도 횡성군 안흥면",
        location = GeoPoint(37.4648630, 128.1346210),
        description = "영동고속도로",
    )

    val pyeongchang = Place(
        id = "rest-pyeongchang-gangneung",
        name = "평창휴게소",
        address = "강원특별자치도 평창군 용평면",
        location = GeoPoint(37.6056935, 128.4535574),
        description = "영동고속도로 · 강릉방향",
    )

    /** 경로 진행 순서대로 정렬된 합류 후보. */
    val restAreas: List<Place> = listOf(munmak, hoengseong, pyeongchang)

    /** 데모 경로 개략도 기준점 (출발 구간 → 목적지). */
    val routeAnchors: List<GeoPoint> = listOf(
        GeoPoint(37.2900, 127.6500), // 출발 구간 (시뮬레이션 기준점)
        munmak.location,
        hoengseong.location,
        pyeongchang.location,
        gangneungStation.location,
    )

    fun lerp(a: GeoPoint, b: GeoPoint, t: Double) = GeoPoint(
        a.latitude + (b.latitude - a.latitude) * t,
        a.longitude + (b.longitude - a.longitude) * t,
    )

    fun between(a: Place, b: Place, t: Double) = lerp(a.location, b.location, t)

    /** Scene 1: 친구 8명이 3대의 차량으로 강릉으로 출발. 각 차량은 따로 이동 중. */
    fun seed(engine: JourneyEngine, clock: DemoClock): JourneyState {
        val start = routeAnchors.first()
        var state = engine.createJourney(
            groupId = GROUP_ID,
            name = "강릉 여행",
            destination = gangneungStation,
            inviteCode = INVITE_CODE,
            creator = VehicleMember(id = SELF, displayName = "내 차량", vehicleLabel = "A", passengerCount = 3),
            now = clock.now(),
        )
        var n = 0
        fun apply(command: JourneyCommand) {
            state = engine.handle(state, command, clock.tick()).state
        }
        apply(JourneyCommand.Join("seed-${++n}", B, INVITE_CODE, "친구 차량", "B", passengerCount = 3))
        apply(JourneyCommand.Join("seed-${++n}", C, INVITE_CODE, "친구 차량", "C", passengerCount = 2))
        // 데모에서는 세 차량 모두 위치 공유에 동의한 상태로 시작한다 (시뮬레이션된 동의).
        for (id in listOf(SELF, B, C)) apply(JourneyCommand.SetLocationSharing("seed-${++n}", id, true))
        val positions = mapOf(
            SELF to lerp(start, munmak.location, 0.85),
            B to lerp(start, munmak.location, 0.55),
            C to between(munmak, hoengseong, 0.25),
        )
        for ((id, point) in positions) {
            apply(JourneyCommand.UpdateLocation("seed-${++n}", id, point, MovementStatus.MOVING, clock.now() + 1))
        }
        return state
    }
}
