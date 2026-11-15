package com.modo.drive.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PortableWifiOff
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.modo.domain.engine.VehicleDisplayStatus
import com.modo.domain.model.NavigationActionStatus
import com.modo.drive.presentation.theme.ModoColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Seoul"))

fun formatTime(epochMs: Long): String = timeFormat.format(Instant.ofEpochMilli(epochMs))

fun VehicleDisplayStatus.icon(): ImageVector = when (this) {
    VehicleDisplayStatus.ARRIVED -> Icons.Rounded.Flag
    VehicleDisplayStatus.DISCONNECTED -> Icons.Rounded.LinkOff
    VehicleDisplayStatus.RECOVERY_NEEDED -> Icons.Rounded.SyncProblem
    VehicleDisplayStatus.NAV_FAILED -> Icons.Rounded.ErrorOutline
    VehicleDisplayStatus.NAV_CHANGE_PENDING -> Icons.Rounded.HourglassTop
    VehicleDisplayStatus.AT_RENDEZVOUS -> Icons.Rounded.Place
    VehicleDisplayStatus.RENDEZVOUS_SET -> Icons.Rounded.CheckCircle
    VehicleDisplayStatus.REQUEST_RAISED -> Icons.Rounded.NotificationsActive
    VehicleDisplayStatus.SHARING_OFF -> Icons.Rounded.LocationOff
    VehicleDisplayStatus.LOCATION_STALE -> Icons.Rounded.PortableWifiOff
    VehicleDisplayStatus.NO_LOCATION -> Icons.Rounded.HelpOutline
    VehicleDisplayStatus.MOVING -> Icons.Rounded.DirectionsCar
    VehicleDisplayStatus.STOPPED -> Icons.Rounded.Pause
}

fun VehicleDisplayStatus.color(): Color = ModoColors.tone(tone)

data class ActionLabel(val text: String, val color: Color, val icon: ImageVector)

/** 차량별 내비게이션 적용 상태 표시. "승인"과 구분되는 "실제 적용" 상태다. */
fun NavigationActionStatus?.label(): ActionLabel = when (this) {
    null -> ActionLabel("요청 전", ModoColors.TextMuted, Icons.Rounded.HourglassTop)
    NavigationActionStatus.PENDING -> ActionLabel("요청 대기", ModoColors.TextMuted, Icons.Rounded.HourglassTop)
    NavigationActionStatus.USER_CONFIRMATION_REQUIRED -> ActionLabel("운전자 확인 대기", ModoColors.Amber, Icons.Rounded.TouchApp)
    NavigationActionStatus.REQUESTED -> ActionLabel("내비게이션 요청 중", ModoColors.Blue, Icons.Rounded.Sync)
    NavigationActionStatus.APPLIED -> ActionLabel("적용 확인", ModoColors.Mint, Icons.Rounded.Navigation)
    NavigationActionStatus.FAILED -> ActionLabel("실패 · 재시도 필요", ModoColors.Red, Icons.Rounded.ErrorOutline)
    NavigationActionStatus.UNKNOWN -> ActionLabel("응답 없음 · 확인 필요", ModoColors.Amber, Icons.Rounded.HelpOutline)
}

val RestIcon: ImageVector get() = Icons.Rounded.LocalCafe
