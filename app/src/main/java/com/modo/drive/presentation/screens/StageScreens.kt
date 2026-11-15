package com.modo.drive.presentation.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.modo.domain.engine.IntentClassifier
import com.modo.domain.engine.RoutePhase
import com.modo.domain.engine.activePlan
import com.modo.domain.engine.activeProposal
import com.modo.domain.engine.member
import com.modo.domain.engine.routeSummary
import com.modo.domain.model.ApprovalDecision
import com.modo.domain.model.JourneyState
import com.modo.domain.model.MobilityRequestType
import com.modo.domain.model.NavigationActionStatus
import com.modo.domain.model.Place
import com.modo.domain.model.ProposalStatus
import com.modo.domain.model.RecoveryReason
import com.modo.domain.model.RecoveryStatus
import com.modo.domain.model.RendezvousProposal
import com.modo.drive.presentation.JourneyViewModel
import com.modo.drive.presentation.ModoUiState
import com.modo.drive.presentation.RestIcon
import com.modo.drive.presentation.VoiceUi
import com.modo.drive.presentation.activeRecovery
import com.modo.drive.presentation.components.ButtonKind
import com.modo.drive.presentation.components.ModoButton
import com.modo.drive.presentation.components.ModoCard
import com.modo.drive.presentation.components.SectionLabel
import com.modo.drive.presentation.components.StatusPill
import com.modo.drive.presentation.components.VehicleBadge
import com.modo.drive.presentation.deferredRecovery
import com.modo.drive.presentation.label
import com.modo.drive.presentation.openIntentRequest
import com.modo.drive.presentation.theme.ModoColors
import java.util.Locale

private fun JourneyState.labelOf(id: String) = member(id)?.vehicleLabel ?: "?"

@Composable
private fun StageHeader(eyebrow: String, title: String, subtitle: String? = null, accent: Color = ModoColors.Blue) {
    Column {
        Text(eyebrow, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.headlineLarge)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = ModoColors.TextMuted)
        }
    }
}

// region Screen 02 — Journey Room

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoomStage(ui: ModoUiState, vm: JourneyViewModel) {
    val state = ui.journey ?: return
    val self = state.member(ui.selfId)
    val deferred = deferredRecovery(state)
    Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.testTag("stage-room")) {
        StageHeader(
            eyebrow = "JOURNEY ROOM",
            title = if (state.proposals.any { it.status == ProposalStatus.APPROVED && it.supersededByProposalId == null && state.group.members.all { m -> m.id in it.arrivedVehicleIds } })
                "모두 합류했어요. 이제 함께 목적지로 출발해요."
            else "모든 차량이 공동 목적지를 향해 이동 중이에요.",
            subtitle = "필요한 일이 생기면 말로 요청하세요. MODO가 그룹의 결정을 도와드려요.",
        )
        if (deferred != null) {
            ModoCard(accent = ModoColors.Amber) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Schedule, null, tint = ModoColors.Amber, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "나중에 확인하기로 한 재조율이 있어요 · ${deferred.affectedVehicleIds.joinToString { "차량 ${state.labelOf(it)}" }}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    ModoButton("지금 확인", { vm.propose(deferred.proposedMeetingPoint ?: return@ModoButton, deferred.id) }, kind = ButtonKind.SECONDARY, compact = true, enabled = deferred.proposedMeetingPoint != null)
                    Spacer(Modifier.width(8.dp))
                    ModoButton("계획 유지", { vm.keepCurrentPlan(deferred.id) }, kind = ButtonKind.GHOST, compact = true)
                }
            }
        }
        ModoCard {
            SectionLabel("이동 요청")
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MicButton(onClick = vm::startVoiceRequest, testTag = "mic-button")
                Spacer(Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    Text("말로 요청하기", style = MaterialTheme.typography.headlineMedium)
                    Text("\"우리 잠깐 쉬었다 가자\" · \"다 같이 만나자\" · \"다들 어디쯤이야?\"", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(18.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModoButton("휴식 요청", { vm.quickRequest(MobilityRequestType.REST_REQUEST) }, kind = ButtonKind.SECONDARY, icon = RestIcon)
                ModoButton("합류 요청", { vm.quickRequest(MobilityRequestType.RENDEZVOUS_REQUEST) }, kind = ButtonKind.SECONDARY, icon = Icons.Rounded.Groups)
                ModoButton("합류 장소 정하기", vm::openRendezvousPicker, kind = ButtonKind.SECONDARY, icon = Icons.Rounded.Place)
            }
        }
        ModoCard {
            SectionLabel("도착")
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${state.group.destination.name}에 도착했나요?", style = MaterialTheme.typography.titleLarge)
                    Text("도착은 GPS만으로 판단하지 않고 직접 확인해요.", style = MaterialTheme.typography.bodyMedium)
                }
                if (self?.arrivalConfirmed == true) {
                    StatusPill("도착 확인됨", ModoColors.Mint, Icons.Rounded.Flag, large = true)
                } else {
                    ModoButton("도착 확인", vm::confirmDestinationArrival, kind = ButtonKind.SUCCESS, icon = Icons.Rounded.Flag)
                }
            }
        }
    }
}

@Composable
fun MicButton(onClick: () -> Unit, testTag: String, listening: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "mic")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (listening) 1.12f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(
        modifier = Modifier
            .size(96.dp)
            .scale(pulse)
            .clip(CircleShape)
            .background(if (listening) ModoColors.Blue else ModoColors.BlueDeep)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Mic, contentDescription = "음성으로 요청", tint = Color.White, modifier = Modifier.size(46.dp))
    }
}

// endregion

// region Screen 03 — Mobility Intent

@Composable
fun IntentStage(ui: ModoUiState, vm: JourneyViewModel) {
    val state = ui.journey ?: return
    val voice = ui.voice
    val request = openIntentRequest(state)
    Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.testTag("stage-intent")) {
        StageHeader("MOBILITY INTENT", "이동 요청", "음성이나 버튼 한 번으로 그룹에 요청을 전달해요.")
        when {
            voice.phase == VoiceUi.Phase.LISTENING -> ModoCard(accent = ModoColors.Blue) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MicButton(onClick = vm::cancelVoice, testTag = "mic-listening", listening = true)
                    Spacer(Modifier.width(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "차량 ${voice.speakerId?.let(state::labelOf) ?: "?"}에서 듣고 있어요" + if (voice.simulated) " · 시뮬레이션" else "",
                            style = MaterialTheme.typography.labelLarge,
                            color = ModoColors.Blue,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (voice.transcript.isBlank()) "…" else "\"${voice.transcript}\"",
                            style = MaterialTheme.typography.headlineLarge,
                            modifier = Modifier.testTag("voice-transcript"),
                        )
                    }
                }
            }
            voice.phase == VoiceUi.Phase.ERROR -> ModoCard(accent = ModoColors.Amber) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WarningAmber, null, tint = ModoColors.Amber, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(voice.error ?: "음성을 인식하지 못했어요.", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModoButton("다시 말하기", vm::startVoiceRequest, icon = Icons.Rounded.Mic)
                    ModoButton("휴식 요청 버튼으로", { vm.cancelVoice(); vm.quickRequest(MobilityRequestType.REST_REQUEST) }, kind = ButtonKind.SECONDARY, icon = RestIcon)
                    ModoButton("닫기", vm::cancelVoice, kind = ButtonKind.GHOST)
                }
            }
            request != null -> {
                val requester = state.labelOf(request.requesterId)
                ModoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VehicleBadge(requester, ModoColors.Amber, size = 64)
                        Spacer(Modifier.width(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text("차량 $requester", style = MaterialTheme.typography.labelLarge, color = ModoColors.TextMuted)
                            Text("\"${request.originalText}\"", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("request-text"))
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("인식 결과", style = MaterialTheme.typography.labelMedium)
                        StatusPill(IntentClassifier.label(request.type), ModoColors.Blue, RestIcon, large = true)
                        Text(request.type.name, style = MaterialTheme.typography.labelSmall)
                    }
                }
                ModoCard(accent = ModoColors.Amber) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Groups, null, tint = ModoColors.Amber, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("그룹 알림", style = MaterialTheme.typography.labelMedium)
                            Text(
                                state.events.lastOrNull { it.refId == request.id }?.message ?: "차량 ${requester}의 요청",
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                    }
                }
                ModoCard(accent = ModoColors.Blue) {
                    Text("합류 장소를 정할까요?", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ModoButton("합류 장소 정하기", vm::openRendezvousPicker, icon = Icons.Rounded.Place, modifier = Modifier.testTag("open-picker"))
                        ModoButton("나중에", { vm.dismissRequest(request.id) }, kind = ButtonKind.GHOST)
                    }
                }
            }
        }
    }
}

// endregion

// region Screen 04 — Smart Rendezvous

@Composable
fun RendezvousStage(ui: ModoUiState, vm: JourneyViewModel) {
    val state = ui.journey ?: return
    val proposal = state.activeProposal()
    Column(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.testTag("stage-rendezvous")) {
        if (proposal == null) {
            StageHeader("SMART RENDEZVOUS", "합류 장소 선택", "그룹에 제안할 휴게소를 고르세요. 모든 차량이 동의해야 확정돼요.")
            ui.candidates.forEach { place ->
                PlaceRow(place, onSelect = { vm.propose(place) }, actionLabel = "이곳을 제안")
            }
            Text(
                "후보 좌표: OpenStreetMap 기준 · 예상 도착 시간은 확인된 값이 아니므로 표시하지 않아요.",
                style = MaterialTheme.typography.labelSmall,
            )
            ModoButton("닫기", vm::closeRendezvousPicker, kind = ButtonKind.GHOST)
        } else {
            StageHeader("SMART RENDEZVOUS", "합류 장소 제안", "모든 차량이 동의해야 합류 장소가 확정돼요.")
            ProposalVoting(proposal, state, ui.selfId, vm)
        }
    }
}

@Composable
fun PlaceRow(place: Place, onSelect: () -> Unit, actionLabel: String, highlight: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ModoColors.Surface)
            .border(1.dp, if (highlight) ModoColors.Mint else ModoColors.Outline, RoundedCornerShape(20.dp))
            .padding(horizontal = 22.dp, vertical = 16.dp)
            .testTag("place-${place.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(RestIcon, null, tint = ModoColors.Blue, modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(place.name, style = MaterialTheme.typography.titleLarge)
            Text("${place.description} · ${place.address}", style = MaterialTheme.typography.bodyMedium)
            Text(
                String.format(Locale.US, "%.4f, %.4f", place.location.latitude, place.location.longitude),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        ModoButton(actionLabel, onSelect, compact = true, icon = Icons.Rounded.ArrowForward)
    }
}

@Composable
fun ProposalVoting(proposal: RendezvousProposal, state: JourneyState, selfId: String, vm: JourneyViewModel) {
    ModoCard(accent = ModoColors.Blue) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Place, null, tint = ModoColors.Blue, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(proposal.location.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("proposal-place"))
                Text("${proposal.location.description} · ${proposal.location.address}", style = MaterialTheme.typography.bodyMedium)
            }
            Column(horizontalAlignment = Alignment.End) {
                StatusPill("합류 계획 #${proposal.planNumber}", ModoColors.Blue)
                Spacer(Modifier.height(6.dp))
                Text("제안: 차량 ${state.labelOf(proposal.proposedBy)}", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(18.dp))
        state.group.members.forEach { m ->
            val decision = proposal.approvals[m.id] ?: ApprovalDecision.PENDING
            val (text, color, icon) = when (decision) {
                ApprovalDecision.APPROVED -> Triple("동의", ModoColors.Mint, Icons.Rounded.CheckCircle)
                ApprovalDecision.REJECTED -> Triple("거절", ModoColors.Red, Icons.Rounded.Close)
                ApprovalDecision.PENDING -> Triple("대기", ModoColors.Amber, Icons.Rounded.HourglassTop)
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("approval-${m.vehicleLabel}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VehicleBadge(m.vehicleLabel, color, size = 40)
                Spacer(Modifier.width(14.dp))
                Text("차량 ${m.vehicleLabel} — $text", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
            }
        }
        val pending = proposal.approvals.count { it.value == ApprovalDecision.PENDING }
        Spacer(Modifier.height(12.dp))
        Text(
            if (pending == 0) "모든 차량이 응답했어요." else "${proposal.approvals.size - pending}/${proposal.approvals.size} 동의 · ${pending}대 응답 대기",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (proposal.approvals[selfId] == ApprovalDecision.PENDING) {
                ModoButton("동의", { vm.respond(proposal.id, true) }, kind = ButtonKind.SUCCESS, icon = Icons.Rounded.Check, modifier = Modifier.testTag("approve"))
                ModoButton("거절", { vm.respond(proposal.id, false) }, kind = ButtonKind.DANGER, icon = Icons.Rounded.Close)
            }
            // 승인 전 경로 변경 시도 → 엔진이 거절하고 응답하지 않은 차량을 안내한다.
            ModoButton("경로 변경 요청", { vm.dispatchNavigation(proposal.id) }, kind = ButtonKind.SECONDARY, icon = Icons.Rounded.Navigation, enabled = pending == 0)
            if (proposal.proposedBy == selfId) {
                ModoButton("제안 취소", { vm.cancelProposal(proposal.id) }, kind = ButtonKind.GHOST)
            }
        }
    }
}

// endregion

// region Screen 05 — Group Route Action

@Composable
fun RouteActionStage(ui: ModoUiState, vm: JourneyViewModel) {
    val state = ui.journey ?: return
    val plan = state.activePlan() ?: return
    val summary = state.routeSummary(plan.id) ?: return
    val previous = plan.replacesProposalId?.let { id -> state.proposals.firstOrNull { it.id == id } }
    val myAction = summary.actions.firstOrNull { it.vehicleId == ui.selfId }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.testTag("stage-route")) {
        StageHeader(
            "GROUP ROUTE ACTION",
            "합류 장소 · ${plan.location.name}",
            if (plan.recoverySessionId != null) null else "그룹 합의와 각 차량의 실제 적용은 따로 확인해요.",
            accent = ModoColors.Mint,
        )
        if (plan.recoverySessionId != null) {
            ModoCard(accent = ModoColors.Mint) {
                Text("새로운 합류 계획이 확정되었어요.", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("replan-confirmed"))
                Text("각 차량이 안전한 경로로 이동합니다.", style = MaterialTheme.typography.titleLarge, color = ModoColors.TextMuted)
                if (previous != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "기존 계획 #${previous.planNumber} ${previous.location.name}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = ModoColors.TextFaint,
                            textDecoration = TextDecoration.LineThrough,
                        )
                        Icon(Icons.Rounded.ArrowForward, null, tint = ModoColors.TextMuted)
                        StatusPill("새 계획 #${plan.planNumber} ${plan.location.name}", ModoColors.Mint)
                    }
                }
            }
        }
        PhaseStepper(summary.phase)
        ModoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill("그룹 합의 완료", ModoColors.Mint, Icons.Rounded.CheckCircle, large = true)
                Spacer(Modifier.width(14.dp))
                StatusPill(
                    "실제 적용 ${summary.appliedCount}/${summary.total}",
                    if (summary.phase == RoutePhase.ALL_APPLIED) ModoColors.Mint else ModoColors.Amber,
                    Icons.Rounded.Navigation,
                    large = true,
                )
                Spacer(Modifier.weight(1f))
                StatusPill("합류 계획 #${plan.planNumber}", ModoColors.Blue)
            }
            Spacer(Modifier.height(14.dp))
            state.group.members.forEach { m ->
                val action = summary.actions.firstOrNull { it.vehicleId == m.id }
                val l = action?.status.label()
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("route-${m.vehicleLabel}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VehicleBadge(m.vehicleLabel, l.color, size = 40)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "차량 ${m.vehicleLabel}${if (m.id == ui.selfId) " (내 차량)" else ""}",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        action?.errorMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = l.color) }
                        if (m.id in plan.arrivedVehicleIds) Text("합류 장소 도착 확인", style = MaterialTheme.typography.bodyMedium, color = ModoColors.Mint)
                    }
                    StatusPill(l.text, l.color, l.icon, large = true)
                    if (action != null && (action.status == NavigationActionStatus.FAILED || action.status == NavigationActionStatus.UNKNOWN)) {
                        Spacer(Modifier.width(10.dp))
                        ModoButton("재시도", { vm.retryNavigation(action.id) }, kind = ButtonKind.SECONDARY, icon = Icons.Rounded.Replay, compact = true)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (summary.actions.isEmpty()) {
                ModoButton("각 차량에 경로 변경 요청", { vm.dispatchNavigation(plan.id) }, icon = Icons.Rounded.Navigation, modifier = Modifier.testTag("dispatch"))
            }
            if (myAction?.status == NavigationActionStatus.USER_CONFIRMATION_REQUIRED) {
                ModoButton("내 차량 경로 적용", { vm.confirmMyRoute(myAction.id) }, icon = Icons.Rounded.TouchApp)
            }
            if (summary.actions.isNotEmpty() && ui.selfId !in plan.arrivedVehicleIds) {
                ModoButton("합류 장소 도착", { vm.confirmRendezvousArrival(plan.id) }, kind = ButtonKind.SUCCESS, icon = Icons.Rounded.Flag)
                ModoButton("합류하지 못했어요", { vm.reportMyRecovery(RecoveryReason.MISSED_RENDEZVOUS) }, kind = ButtonKind.GHOST, icon = Icons.Rounded.SyncProblem)
            }
        }
    }
}

@Composable
private fun PhaseStepper(phase: RoutePhase) {
    val order = listOf(RoutePhase.AGREED, RoutePhase.WAITING_CONFIRMATION, RoutePhase.REQUESTING, RoutePhase.ALL_APPLIED, RoutePhase.NEEDS_RETRY)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("phase-${phase.name}")) {
        order.forEach { p ->
            val active = p == phase
            val color = when {
                !active -> ModoColors.TextFaint
                p == RoutePhase.NEEDS_RETRY -> ModoColors.Red
                p == RoutePhase.ALL_APPLIED -> ModoColors.Mint
                p == RoutePhase.WAITING_CONFIRMATION -> ModoColors.Amber
                else -> ModoColors.Blue
            }
            Text(
                p.label,
                color = color,
                fontSize = 16.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) color.copy(alpha = 0.15f) else Color.Transparent)
                    .border(1.dp, if (active) color else ModoColors.Outline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

// endregion

// region Screen 06 — Group Recovery

@Composable
fun RecoveryStage(ui: ModoUiState, vm: JourneyViewModel) {
    val state = ui.journey ?: return
    val session = activeRecovery(state) ?: return
    val previous = session.previousProposalId?.let { id -> state.proposals.firstOrNull { it.id == id } }
    val labels = session.affectedVehicleIds.joinToString(", ") { "차량 ${state.labelOf(it)}" }
    val situation = when (session.reason) {
        RecoveryReason.MISSED_RENDEZVOUS -> "${labels}가 기존 합류 장소에 합류하지 못했습니다."
        RecoveryReason.NAVIGATION_NOT_APPLIED -> "${labels}의 경로가 합류 장소로 변경되지 않았어요."
        RecoveryReason.ROUTE_DEVIATION -> "${labels}가 계획한 경로를 벗어났어요."
        RecoveryReason.MANUAL_HELP_REQUEST -> "${labels}가 도움을 요청했어요."
    }
    val suggestion = session.proposedMeetingPoint ?: vm.suggestFor(session.affectedVehicleIds)
    Column(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.testTag("stage-recovery")) {
        StageHeader("GROUP RECOVERY", "계획이 어긋났어요", accent = ModoColors.Red)
        ModoCard(accent = ModoColors.Red) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SyncProblem, null, tint = ModoColors.Red, modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(situation, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("recovery-situation"))
                    previous?.let {
                        Text("기존 계획 #${it.planNumber} · ${it.location.name}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (session.status == RecoveryStatus.REPLANNING) {
            val proposal = state.activeProposal()
            if (proposal != null) {
                Text("새로운 합류 계획에 대한 그룹의 결정을 기다리고 있어요.", style = MaterialTheme.typography.titleLarge)
                ProposalVoting(proposal, state, ui.selfId, vm)
            }
        } else {
            Text("새로운 합류 장소를 정할까요?", style = MaterialTheme.typography.headlineLarge)
            if (suggestion != null) {
                PlaceRow(suggestion, onSelect = { vm.propose(suggestion, session.id) }, actionLabel = "재합류 제안", highlight = true)
                Text(
                    "추천 기준: 합류하지 못한 차량이 아직 지나지 않은 다음 휴게소 (직선거리 기준, 실시간 교통 미반영)",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModoButton(
                    "재합류 제안",
                    { suggestion?.let { vm.propose(it, session.id) } },
                    icon = Icons.Rounded.Groups,
                    enabled = suggestion != null,
                    modifier = Modifier.testTag("recovery-propose"),
                )
                ModoButton("현재 계획 유지", { vm.keepCurrentPlan(session.id) }, kind = ButtonKind.SECONDARY)
                ModoButton("나중에 확인", { vm.deferRecovery(session.id) }, kind = ButtonKind.GHOST, icon = Icons.Rounded.Schedule)
            }
        }
    }
}

// endregion
