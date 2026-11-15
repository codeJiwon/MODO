package com.modo.drive.presentation.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.modo.domain.engine.VehicleDisplayStatus
import com.modo.domain.engine.activePlan
import com.modo.domain.engine.displayStatusOf
import com.modo.domain.engine.distanceMeters
import com.modo.domain.engine.isLocationStale
import com.modo.domain.model.GeoPoint
import com.modo.domain.model.JourneyState
import com.modo.domain.model.Place
import com.modo.domain.model.VehicleMember
import com.modo.drive.presentation.color
import com.modo.drive.presentation.components.SectionLabel
import com.modo.drive.presentation.components.StatusPill
import com.modo.drive.presentation.components.VehicleBadge
import com.modo.drive.presentation.formatTime
import com.modo.drive.presentation.icon
import com.modo.drive.presentation.theme.ModoColors

@Composable
fun VehicleCard(member: VehicleMember, state: JourneyState, now: Long, isSelf: Boolean, modifier: Modifier = Modifier) {
    val status = state.displayStatusOf(member.id, now)
    val color = status.color()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (isSelf) ModoColors.SurfaceHigh else ModoColors.Surface)
            .border(1.dp, if (status.tone == VehicleDisplayStatus.Tone.ERROR) color.copy(alpha = 0.6f) else ModoColors.Outline, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .testTag("vehicle-${member.vehicleLabel}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VehicleBadge(member.vehicleLabel, color)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${member.vehicleLabel} · ${if (isSelf) "내 차량" else member.displayName}",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            StatusPill(status.label, color, status.icon())
            Spacer(Modifier.height(6.dp))
            Text(locationLine(member, state, now), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("${member.passengerCount}명", style = MaterialTheme.typography.labelMedium)
    }
}

/** 위치 갱신이 멈춘 경우 "정차 중"이 아니라 마지막 갱신 시각을 함께 보여준다. */
private fun locationLine(member: VehicleMember, state: JourneyState, now: Long): String {
    if (!member.locationSharingEnabled) return "위치 공유에 동의하지 않음"
    val updated = member.lastUpdatedAt ?: return "위치 정보를 기다리는 중"
    return if (state.isLocationStale(member.id, now)) "위치 갱신 중단 · 마지막 위치 ${formatTime(updated)}"
    else "위치 갱신 ${formatTime(updated)}"
}

@Composable
fun VehicleList(state: JourneyState, now: Long, selfId: String, modifier: Modifier = Modifier, horizontal: Boolean = false) {
    val ordered = state.group.members.sortedBy { if (it.id == selfId) "" else it.vehicleLabel }
    if (horizontal) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ordered.forEach { VehicleCard(it, state, now, it.id == selfId, Modifier.weight(1f)) }
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ordered.forEach { VehicleCard(it, state, now, it.id == selfId) }
        }
    }
}

/**
 * 경로 개략도. 지도 대신 "출발 → 휴게소 → 목적지" 진행선 위에 차량 위치를 표시하는 보조 영역이다.
 * 진행률은 목적지까지 남은 직선 거리로 계산한다 (실제 도로 거리/ETA 아님).
 */
@Composable
fun RouteSchematic(
    state: JourneyState,
    selfId: String,
    stops: List<Place>,
    simulated: Boolean,
    modifier: Modifier = Modifier,
) {
    val destination = state.group.destination
    val members = state.group.members.filter { it.location != null }
    val origin = members.mapNotNull { it.location }
        .plus(stops.map { it.location })
        .maxByOrNull { distanceMeters(it, destination.location) } ?: destination.location
    val total = distanceMeters(origin, destination.location).coerceAtLeast(1.0)
    fun progress(p: GeoPoint) = (1 - distanceMeters(p, destination.location) / total).coerceIn(0.0, 1.0).toFloat()
    val plan = state.activePlan()

    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(ModoColors.Surface)
            .border(1.dp, ModoColors.Outline, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("경로 개략도")
            Spacer(Modifier.weight(1f))
            Text(
                if (simulated) "시뮬레이션 위치 · 직선거리 기준" else "공유된 위치 · 직선거리 기준",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(10.dp))
        Canvas(Modifier.fillMaxWidth().height(54.dp)) {
            val y = size.height * 0.55f
            val left = 12.dp.toPx()
            val right = size.width - 12.dp.toPx()
            fun x(t: Float) = left + (right - left) * t
            drawLine(ModoColors.Outline, Offset(left, y), Offset(right, y), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
            stops.forEach { stop ->
                val isPlan = plan?.location?.id == stop.id
                val sx = x(progress(stop.location))
                drawCircle(
                    color = if (isPlan) ModoColors.Mint else ModoColors.TextFaint,
                    radius = if (isPlan) 9.dp.toPx() else 6.dp.toPx(),
                    center = Offset(sx, y),
                    style = if (isPlan) Stroke(width = 4.dp.toPx()) else androidx.compose.ui.graphics.drawscope.Fill,
                )
            }
            drawLine(
                ModoColors.TextFaint, Offset(x(1f), y - 22.dp.toPx()), Offset(x(1f), y + 8.dp.toPx()),
                strokeWidth = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
            members.forEachIndexed { index, m ->
                val color = if (m.id == selfId) ModoColors.Blue else listOf(ModoColors.Amber, ModoColors.Mint, ModoColors.TextMuted)[index % 3]
                val cx = x(progress(m.location!!))
                drawCircle(color.copy(alpha = 0.25f), radius = 14.dp.toPx(), center = Offset(cx, y - 16.dp.toPx()))
                drawCircle(color, radius = 8.dp.toPx(), center = Offset(cx, y - 16.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("출발", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            stops.forEach {
                Text(it.name.removeSuffix("휴게소"), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(destination.name, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            members.forEach { m ->
                Text(
                    "${m.vehicleLabel}${if (m.id == selfId) "(나)" else ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (m.id == selfId) ModoColors.Blue else ModoColors.TextMuted,
                )
            }
        }
    }
}
