package com.modo.drive.presentation.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.modo.domain.engine.member
import com.modo.domain.model.NavigationActionStatus
import com.modo.drive.demo.DemoData
import com.modo.drive.domain.repository.SyncStatus
import com.modo.drive.presentation.JourneyViewModel
import com.modo.drive.presentation.ModoUiState
import com.modo.drive.presentation.Stage
import com.modo.drive.presentation.components.BannerBar
import com.modo.drive.presentation.components.ButtonKind
import com.modo.drive.presentation.components.DemoWatermark
import com.modo.drive.presentation.components.ModoButton
import com.modo.drive.presentation.components.ModoCard
import com.modo.drive.presentation.components.SectionLabel
import com.modo.drive.presentation.components.SpokenCaption
import com.modo.drive.presentation.components.StatusPill
import com.modo.drive.presentation.theme.ModoColors

@Composable
fun JourneyScreen(ui: ModoUiState, vm: JourneyViewModel, isWide: Boolean, onRequestLocationConsent: () -> Unit) {
    val state = ui.journey ?: return
    Box(Modifier.fillMaxSize().background(ModoColors.Navy).testTag("screen-journey")) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
            TopBar(ui, vm)
            Spacer(Modifier.height(18.dp))
            if (isWide) {
                Row(Modifier.weight(1f)) {
                    Column(Modifier.width(400.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        SectionLabel("참가 차량 · ${state.group.members.size}대 · ${state.group.members.sumOf { it.passengerCount }}명")
                        Spacer(Modifier.height(10.dp))
                        VehicleList(state, ui.now, ui.selfId)
                        Spacer(Modifier.height(14.dp))
                        RouteSchematic(state, ui.selfId, ui.candidates, ui.isDemo)
                        Spacer(Modifier.height(14.dp))
                        ConsentCard(ui, vm, onRequestLocationConsent)
                    }
                    Spacer(Modifier.width(28.dp))
                    StageArea(ui, vm, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                // 분할 화면/세로 창: 차량 요약을 위에, 필요한 행동을 아래에 배치
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    VehicleList(state, ui.now, ui.selfId)
                    Spacer(Modifier.height(16.dp))
                    StageArea(ui, vm, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    RouteSchematic(state, ui.selfId, ui.candidates, ui.isDemo)
                    Spacer(Modifier.height(14.dp))
                    ConsentCard(ui, vm, onRequestLocationConsent)
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SpokenCaption(ui.spoken)
            Spacer(Modifier.height(8.dp))
            BannerBar(ui.banner, vm::dismissBanner, vm::retryLastCommand, Modifier.testTag("banner"))
        }
        if (ui.isDemo) {
            DemoWatermark(Modifier.align(Alignment.BottomStart).padding(16.dp))
            DemoControls(ui, vm, Modifier.align(Alignment.BottomEnd).padding(16.dp))
        }
        if (ui.settingsOpen) SettingsSheet(ui, vm)
    }
}

@Composable
private fun StageArea(ui: ModoUiState, vm: JourneyViewModel, modifier: Modifier) {
    AnimatedContent(
        targetState = ui.stage,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        modifier = modifier,
        label = "stage",
    ) { stage ->
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
            when (stage) {
                Stage.ROOM -> RoomStage(ui, vm)
                Stage.INTENT -> IntentStage(ui, vm)
                Stage.RENDEZVOUS -> RendezvousStage(ui, vm)
                Stage.ROUTE_ACTION -> RouteActionStage(ui, vm)
                Stage.RECOVERY -> RecoveryStage(ui, vm)
            }
        }
    }
}

@Composable
private fun TopBar(ui: ModoUiState, vm: JourneyViewModel) {
    val state = ui.journey ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        ModoSymbol(Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Text("MODO", fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp, color = ModoColors.Text)
        Spacer(Modifier.width(28.dp))
        Column {
            Text(state.group.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("group-name"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.LocationOn, null, tint = ModoColors.Mint, modifier = Modifier.size(18.dp))
                Text("공동 목적지 ${state.group.destination.name}", style = MaterialTheme.typography.bodyMedium)
                if (!ui.isDemo) {
                    Text("  ·  초대 코드 ${state.group.inviteCode}", style = MaterialTheme.typography.bodyMedium, color = ModoColors.Blue)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val (text, color, icon) = when {
            ui.isDemo && ui.syncStatus != SyncStatus.OFFLINE -> Triple("시뮬레이션 동기화", ModoColors.Amber, Icons.Rounded.Science)
            ui.syncStatus == SyncStatus.CONNECTED -> Triple("그룹 동기화됨", ModoColors.Mint, Icons.Rounded.CloudDone)
            ui.syncStatus == SyncStatus.CONNECTING -> Triple("연결 중", ModoColors.Blue, Icons.Rounded.Sync)
            ui.syncStatus == SyncStatus.OFFLINE -> Triple("오프라인 · 마지막 상태 표시", ModoColors.Red, Icons.Rounded.CloudOff)
            else -> Triple("대기", ModoColors.TextMuted, Icons.Rounded.Sync)
        }
        StatusPill(text, color, icon, modifier = Modifier.testTag("sync-status"))
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(ModoColors.SurfaceHigh)
                .clickable(role = Role.Button) { vm.toggleSettings(true) },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Settings, contentDescription = "설정", tint = ModoColors.TextMuted) }
    }
}

/** 위치 공유는 참가자의 명시적인 동의가 있을 때만 켜진다. */
@Composable
private fun ConsentCard(ui: ModoUiState, vm: JourneyViewModel, onRequestLocationConsent: () -> Unit) {
    val me = ui.journey?.member(ui.selfId) ?: return
    ModoCard(padding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("내 위치 공유", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        ui.isDemo -> "데모: 시뮬레이션 위치를 공유 중"
                        me.locationSharingEnabled -> "그룹에 내 차량 위치를 공유하고 있어요"
                        else -> "동의하면 그룹에 내 차량 위치가 공유돼요"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Switch(
                checked = me.locationSharingEnabled,
                onCheckedChange = { enable -> if (enable && !ui.isDemo) onRequestLocationConsent() else vm.setLocationSharing(enable) },
                colors = SwitchDefaults.colors(checkedTrackColor = ModoColors.Mint),
                modifier = Modifier.testTag("location-consent"),
            )
        }
    }
}

// region Demo Controller

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoControls(ui: ModoUiState, vm: JourneyViewModel, modifier: Modifier) {
    val demo = ui.demo ?: return
    Column(modifier, horizontalAlignment = Alignment.End) {
        if (demo.panelOpen) {
            Column(
                Modifier
                    .width(560.dp)
                    .height(560.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(ModoColors.SurfaceHigh)
                    .border(1.dp, ModoColors.Amber.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("demo-panel"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Demo Controller", style = MaterialTheme.typography.titleLarge, color = ModoColors.Amber)
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { vm.toggleDemoPanel(false) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Close, "데모 패널 닫기", tint = ModoColors.TextMuted) }
                }
                Text("모든 조작은 실제 도메인 명령으로 처리돼요. 다른 차량의 행동과 위치는 시뮬레이션이에요.", style = MaterialTheme.typography.labelMedium)
                ModoButton("Reset Demo", vm::resetDemo, kind = ButtonKind.DANGER, icon = Icons.Rounded.RestartAlt, modifier = Modifier.fillMaxWidth().testTag("reset-demo"))
                SectionLabel("장면 (탭하면 처음부터 해당 장면까지 재생)")
                demo.scenes.forEachIndexed { index, scene ->
                    val done = index <= demo.currentIndex
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (index == demo.currentIndex) ModoColors.Amber.copy(alpha = 0.14f) else ModoColors.Surface)
                            .clickable(enabled = !demo.running, role = Role.Button) { vm.demoJumpTo(index) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(scene.label, color = if (done) ModoColors.Amber else ModoColors.TextMuted, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.width(110.dp))
                        Column {
                            Text(scene.title, style = MaterialTheme.typography.titleMedium)
                            Text(scene.description, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                SectionLabel("차량 C 테스트 조작")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModoButton("C 동의", { vm.demoRespond(DemoData.C, true) }, kind = ButtonKind.SECONDARY, compact = true)
                    ModoButton("C 거절", { vm.demoRespond(DemoData.C, false) }, kind = ButtonKind.SECONDARY, compact = true)
                    ModoButton("C 운전자 확인 → 적용", { vm.demoSetNavigation(DemoData.C, NavigationActionStatus.REQUESTED) }, kind = ButtonKind.SECONDARY, compact = true)
                    ModoButton("C 적용 실패", { vm.demoSetNavigation(DemoData.C, NavigationActionStatus.FAILED) }, kind = ButtonKind.SECONDARY, compact = true)
                    ModoButton("C 응답 없음", { vm.demoSetNavigation(DemoData.C, NavigationActionStatus.UNKNOWN) }, kind = ButtonKind.SECONDARY, compact = true)
                    ModoButton("C 합류 실패 발생", { vm.demoMissedRendezvous(DemoData.C) }, kind = ButtonKind.SECONDARY, compact = true)
                }
                SectionLabel("오류 상황 시뮬레이션")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModoButton("B 음성 요청 재현", { vm.simulateVoiceRequestFrom(DemoData.B) }, kind = ButtonKind.SECONDARY, compact = true)
                    ModoButton("B 동의", { vm.demoRespond(DemoData.B, true) }, kind = ButtonKind.SECONDARY, compact = true)
                    ModoButton("B 연결 끊김/복구", { vm.demoToggleConnection(DemoData.B) }, kind = ButtonKind.SECONDARY, compact = true)
                    ModoButton("B 위치 갱신 중단", { vm.demoLocationLoss(DemoData.B) }, kind = ButtonKind.SECONDARY, compact = true)
                    ModoButton(if (demo.offline) "네트워크 복구" else "네트워크 끊김", vm::demoToggleOffline, kind = ButtonKind.SECONDARY, compact = true)
                    ModoButton("음성 인식 실패", vm::demoFailNextVoice, kind = ButtonKind.SECONDARY, compact = true)
                    ModoButton("인식 불가 명령", vm::demoUnrecognizedVoice, kind = ButtonKind.SECONDARY, compact = true)
                    ModoButton("내 경로 적용 실패", vm::demoFailMyNextRoute, kind = ButtonKind.SECONDARY, compact = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("내 차량(A)에 실제 Pleos NaviHelper 사용", style = MaterialTheme.typography.titleMedium)
                        Text("Pleos Connect 환경에서만 동작. 다른 차량은 계속 시뮬레이션.", style = MaterialTheme.typography.labelMedium)
                    }
                    Switch(checked = demo.useRealNavigation, onCheckedChange = { vm.demoToggleRealNavigation() })
                }
                ui.sdk?.let { SdkStatusBlock(it) }
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val scene = demo.scenes.getOrNull(demo.currentIndex)
            if (scene != null) Text("${scene.label} · ${scene.title}", style = MaterialTheme.typography.labelMedium, color = ModoColors.Amber)
            ModoButton(
                "DEMO",
                { vm.toggleDemoPanel(!demo.panelOpen) },
                kind = ButtonKind.GHOST,
                icon = Icons.Rounded.Science,
                compact = true,
                modifier = Modifier.testTag("demo-toggle"),
            )
            ModoButton(
                if (demo.running) "실행 중…" else "다음 장면",
                vm::demoNextScene,
                kind = ButtonKind.SECONDARY,
                icon = Icons.Rounded.SkipNext,
                compact = true,
                enabled = !demo.running && demo.currentIndex < demo.scenes.lastIndex,
                modifier = Modifier.testTag("demo-next"),
            )
        }
    }
}

// endregion

@Composable
private fun SdkStatusBlock(sdk: com.modo.drive.presentation.SdkStatusUi) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("SDK 연동 상태")
        Text("내비게이션: ${sdk.navigation}", style = MaterialTheme.typography.labelMedium)
        Text("위치: ${sdk.location}", style = MaterialTheme.typography.labelMedium)
        Text("음성 인식: ${sdk.voice}", style = MaterialTheme.typography.labelMedium)
        Text("음성 안내: ${sdk.speech}", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SettingsSheet(ui: ModoUiState, vm: JourneyViewModel) {
    Box(
        Modifier.fillMaxSize().background(ModoColors.Navy.copy(alpha = 0.7f)).clickable { vm.toggleSettings(false) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(620.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(ModoColors.SurfaceHigh)
                .clickable(enabled = false) {}
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("여행 설정", style = MaterialTheme.typography.headlineMedium)
            Text(if (ui.isDemo) "모드: Demo (시뮬레이션)" else "모드: Live (그룹 서버 + Pleos SDK)", style = MaterialTheme.typography.bodyLarge)
            ui.sdk?.let { SdkStatusBlock(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModoButton("여행 종료", vm::endJourney, kind = ButtonKind.DANGER)
                ModoButton("처음 화면으로", vm::leaveJourney, kind = ButtonKind.SECONDARY)
                ModoButton("닫기", { vm.toggleSettings(false) }, kind = ButtonKind.GHOST)
            }
        }
    }
}
