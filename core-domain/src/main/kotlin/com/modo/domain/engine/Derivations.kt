package com.modo.domain.engine

import com.modo.domain.model.ConnectionStatus
import com.modo.domain.model.GeoPoint
import com.modo.domain.model.JourneyState
import com.modo.domain.model.MemberNavigationStatus
import com.modo.domain.model.MobilityRequestStatus
import com.modo.domain.model.MovementStatus
import com.modo.domain.model.NavigationAction
import com.modo.domain.model.NavigationActionStatus
import com.modo.domain.model.Place
import com.modo.domain.model.ProposalStatus
import com.modo.domain.model.RendezvousProposal
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 화면에 표시하는 차량 상태. 텍스트와 함께 색/아이콘 톤을 결정한다. */
enum class VehicleDisplayStatus(val label: String, val tone: Tone) {
    ARRIVED("도착 확인", Tone.SUCCESS),
    DISCONNECTED("연결 끊김", Tone.ERROR),
    RECOVERY_NEEDED("재조율 필요", Tone.ERROR),
    NAV_FAILED("경로 적용 실패", Tone.ERROR),
    NAV_CHANGE_PENDING("내비게이션 변경 대기", Tone.WARNING),
    AT_RENDEZVOUS("합류 장소 도착", Tone.SUCCESS),
    RENDEZVOUS_SET("합류 장소 설정 완료", Tone.SUCCESS),
    REQUEST_RAISED("요청 발생", Tone.WARNING),
    SHARING_OFF("위치 공유 꺼짐", Tone.NEUTRAL),
    LOCATION_STALE("위치 갱신 중단", Tone.WARNING),
    NO_LOCATION("위치 정보 없음", Tone.NEUTRAL),
    MOVING("이동 중", Tone.ACTIVE),
    STOPPED("정차 중", Tone.NEUTRAL),
    ;

    enum class Tone { ACTIVE, SUCCESS, WARNING, ERROR, NEUTRAL }
}

/** 위치 갱신이 이 시간보다 오래 멈추면 "정차 중"이 아니라 "위치 갱신 중단"으로 표시한다. */
const val LOCATION_STALE_AFTER_MS: Long = 60_000

fun JourneyState.displayStatusOf(vehicleId: String, now: Long): VehicleDisplayStatus {
    val member = member(vehicleId) ?: return VehicleDisplayStatus.NO_LOCATION
    val plan = activePlan()
    return when {
        member.arrivalConfirmed -> VehicleDisplayStatus.ARRIVED
        member.connectionStatus == ConnectionStatus.DISCONNECTED -> VehicleDisplayStatus.DISCONNECTED
        openRecoveryFor(vehicleId) != null -> VehicleDisplayStatus.RECOVERY_NEEDED
        plan != null && vehicleId in plan.arrivedVehicleIds -> VehicleDisplayStatus.AT_RENDEZVOUS
        plan != null && member.navigationStatus == MemberNavigationStatus.CHANGE_FAILED -> VehicleDisplayStatus.NAV_FAILED
        plan != null && (member.navigationStatus == MemberNavigationStatus.CHANGE_PENDING ||
            member.navigationStatus == MemberNavigationStatus.UNKNOWN) -> VehicleDisplayStatus.NAV_CHANGE_PENDING
        plan != null && member.navigationStatus == MemberNavigationStatus.RENDEZVOUS_APPLIED -> VehicleDisplayStatus.RENDEZVOUS_SET
        requests.any { it.requesterId == vehicleId && (it.status == MobilityRequestStatus.OPEN || it.status == MobilityRequestStatus.IN_PROGRESS) } ->
            VehicleDisplayStatus.REQUEST_RAISED
        !member.locationSharingEnabled -> VehicleDisplayStatus.SHARING_OFF
        member.lastUpdatedAt == null -> VehicleDisplayStatus.NO_LOCATION
        now - member.lastUpdatedAt > LOCATION_STALE_AFTER_MS -> VehicleDisplayStatus.LOCATION_STALE
        member.movementStatus == MovementStatus.MOVING -> VehicleDisplayStatus.MOVING
        member.movementStatus == MovementStatus.STOPPED -> VehicleDisplayStatus.STOPPED
        else -> VehicleDisplayStatus.NO_LOCATION
    }
}

fun JourneyState.isLocationStale(vehicleId: String, now: Long): Boolean {
    val updated = member(vehicleId)?.lastUpdatedAt ?: return false
    return now - updated > LOCATION_STALE_AFTER_MS
}

/** Group Route Action 화면의 단계. "그룹 합의"와 "실제 적용"을 분리해서 보여준다. */
enum class RoutePhase(val label: String) {
    AGREED("그룹 합의 완료"),
    WAITING_CONFIRMATION("각 차량 확인 대기"),
    REQUESTING("내비게이션 요청 중"),
    ALL_APPLIED("실제 적용 확인"),
    NEEDS_RETRY("실패 및 재시도 필요"),
}

data class RouteActionSummary(
    val proposal: RendezvousProposal,
    val actions: List<NavigationAction>,
    val phase: RoutePhase,
    val appliedCount: Int,
    val total: Int,
)

fun JourneyState.routeSummary(proposalId: String): RouteActionSummary? {
    val proposal = proposals.firstOrNull { it.id == proposalId } ?: return null
    if (proposal.status != ProposalStatus.APPROVED) return null
    val actions = group.members.mapNotNull { m ->
        navigationActions.lastOrNull { it.proposalId == proposalId && it.vehicleId == m.id }
    }
    val phase = when {
        actions.isEmpty() -> RoutePhase.AGREED
        actions.any { it.status == NavigationActionStatus.FAILED || it.status == NavigationActionStatus.UNKNOWN } -> RoutePhase.NEEDS_RETRY
        actions.any { it.status == NavigationActionStatus.USER_CONFIRMATION_REQUIRED } -> RoutePhase.WAITING_CONFIRMATION
        actions.all { it.status == NavigationActionStatus.APPLIED } && actions.size == group.members.size -> RoutePhase.ALL_APPLIED
        else -> RoutePhase.REQUESTING
    }
    return RouteActionSummary(
        proposal = proposal,
        actions = actions,
        phase = phase,
        appliedCount = actions.count { it.status == NavigationActionStatus.APPLIED },
        total = group.members.size,
    )
}

/** 두 좌표 사이의 대원 거리(미터). */
fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}

/**
 * 재조율 시 새로운 합류 장소 후보를 고르는 단순 휴리스틱.
 *
 * 후보 목록은 경로 진행 순서대로 정렬되어 있다고 가정하고, 합류에 실패한 차량들이 아직 지나치지 않은
 * (목적지까지 거리가 더 먼) 후보 중 첫 번째를 고른다. 실시간 교통/ETA는 고려하지 않는다.
 */
object RendezvousSuggester {
    fun suggest(
        candidatesInRouteOrder: List<Place>,
        destination: Place,
        affectedLocations: List<GeoPoint>,
        exclude: Set<String>,
    ): Place? {
        val available = candidatesInRouteOrder.filter { it.id !in exclude }
        if (affectedLocations.isEmpty()) return available.firstOrNull()
        // 가장 앞서 있는 피해 차량의 목적지까지 남은 거리
        val remaining = affectedLocations.minOf { distanceMeters(it, destination.location) }
        return available.firstOrNull { distanceMeters(it.location, destination.location) < remaining }
    }
}
