package com.modo.drive.presentation.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.modo.domain.engine.member
import com.modo.domain.model.JourneyState
import com.modo.drive.presentation.ModoUiState
import com.modo.drive.presentation.components.ButtonKind
import com.modo.drive.presentation.components.DemoWatermark
import com.modo.drive.presentation.components.ModoButton
import com.modo.drive.presentation.components.ModoCard
import com.modo.drive.presentation.components.VehicleBadge
import com.modo.drive.presentation.theme.ModoColors

/** 세 갈래의 길이 한 점으로 모이는 MODO 심볼 (자체 제작, 외부 브랜드 자산 미사용). */
@Composable
fun ModoSymbol(modifier: Modifier = Modifier, progress: Float = 1f) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.06f, cap = StrokeCap.Round)
        val meet = Offset(w * 0.5f, h * 0.36f)
        val paths = listOf(
            Path().apply { moveTo(w * 0.14f, h * 0.92f); cubicTo(w * 0.32f, h * 0.78f, w * 0.44f, h * 0.6f, meet.x, meet.y) } to ModoColors.Blue,
            Path().apply { moveTo(w * 0.5f, h * 0.96f); lineTo(meet.x, meet.y) } to ModoColors.Mint,
            Path().apply { moveTo(w * 0.86f, h * 0.92f); cubicTo(w * 0.68f, h * 0.78f, w * 0.56f, h * 0.6f, meet.x, meet.y) } to ModoColors.Amber,
        )
        clipRect(top = h * (1f - progress)) {
            paths.forEach { (p, c) -> drawPath(p, c, style = stroke) }
        }
        drawCircle(ModoColors.Text.copy(alpha = progress), radius = w * 0.09f, center = Offset(meet.x, meet.y - w * 0.12f))
    }
}

@Composable
fun WelcomeScreen(onStart: () -> Unit, onJoin: () -> Unit, onDemo: () -> Unit) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(ModoColors.SurfaceHigh, ModoColors.Navy), radius = 1400f))
            .testTag("screen-welcome"),
    ) {
        Row(Modifier.fillMaxSize().padding(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.1f)) {
                ModoSymbol(Modifier.size(132.dp), reveal.value)
                Spacer(Modifier.height(28.dp))
                Text("MODO", style = MaterialTheme.typography.displayLarge)
                Text("Move On, Drive On", fontSize = 30.sp, fontWeight = FontWeight.Light, color = ModoColors.Blue, letterSpacing = 2.sp)
                Spacer(Modifier.height(22.dp))
                Text("따로 달려도, 함께 도착하는 여행.", style = MaterialTheme.typography.headlineMedium, color = ModoColors.TextMuted)
                Spacer(Modifier.height(18.dp))
                Text(
                    "여러 대의 차량이 함께 이동할 때 생기는 요청·결정·경로 변경·합류 실패를 하나의 흐름으로 해결하는 그룹 드라이빙 코파일럿",
                    style = MaterialTheme.typography.bodyLarge,
                    color = ModoColors.TextFaint,
                    modifier = Modifier.widthIn(max = 620.dp),
                )
            }
            Column(Modifier.weight(0.8f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ModoButton("여행 시작하기", onStart, icon = Icons.Rounded.DirectionsCar, modifier = Modifier.fillMaxWidth().testTag("start-journey"))
                ModoButton("초대 코드로 참여", onJoin, kind = ButtonKind.SECONDARY, icon = Icons.Rounded.Group, modifier = Modifier.fillMaxWidth().testTag("join-journey"))
                ModoButton("Demo Experience", onDemo, kind = ButtonKind.GHOST, icon = Icons.Rounded.PlayCircle, modifier = Modifier.fillMaxWidth().testTag("demo-experience"))
                Text(
                    "Demo Experience는 서버·차량 연결 없이 시뮬레이션 데이터로 전체 시나리오를 보여줍니다.",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, testTag: String, keyboard: KeyboardOptions = KeyboardOptions.Default) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 17.sp) },
        singleLine = true,
        textStyle = TextStyle(fontSize = 24.sp, color = ModoColors.Text),
        keyboardOptions = keyboard,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ModoColors.Blue,
            unfocusedBorderColor = ModoColors.Outline,
            focusedLabelColor = ModoColors.Blue,
            unfocusedLabelColor = ModoColors.TextMuted,
            cursorColor = ModoColors.Blue,
        ),
        modifier = Modifier.fillMaxWidth().testTag(testTag),
    )
}

/** LIVE 모드: 그룹 동기화 서버에 새 여행을 만든다. */
@Composable
fun CreateJourneyScreen(ui: ModoUiState, onBack: () -> Unit, onCreate: (server: String, name: String, label: String, passengers: Int) -> Unit) {
    var server by rememberSaveable { mutableStateOf(ui.serverUrl) }
    var name by rememberSaveable { mutableStateOf("강릉 여행") }
    var label by rememberSaveable { mutableStateOf("A") }
    var passengers by rememberSaveable { mutableStateOf("3") }
    FormScaffold("여행 시작하기", "그룹을 만들고 초대 코드를 친구 차량에 공유하세요. 공동 목적지: 강릉역", onBack) {
        Field("여행 이름", name, { name = it }, "field-name")
        Field("내 차량 이름 (예: A)", label, { label = it.take(2) }, "field-label", KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
        Field("탑승 인원", passengers, { passengers = it.filter(Char::isDigit).take(1) }, "field-passengers", KeyboardOptions(keyboardType = KeyboardType.Number))
        Field("그룹 서버 주소", server, { server = it }, "field-server", KeyboardOptions(keyboardType = KeyboardType.Uri))
        ModoButton(
            if (ui.busy) "연결 중…" else "여행 만들기",
            { onCreate(server, name, label, passengers.toIntOrNull() ?: 1) },
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth().testTag("submit-create"),
        )
    }
}

/** LIVE 모드: 초대 코드로 다른 차량의 여행에 참여한다. */
@Composable
fun JoinJourneyScreen(ui: ModoUiState, onBack: () -> Unit, onJoin: (server: String, code: String, label: String, passengers: Int) -> Unit) {
    var server by rememberSaveable { mutableStateOf(ui.serverUrl) }
    var code by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("B") }
    var passengers by rememberSaveable { mutableStateOf("2") }
    FormScaffold("초대 코드로 참여", "친구에게 받은 6자리 초대 코드를 입력하세요.", onBack) {
        Field("초대 코드", code, { code = it.uppercase().take(8) }, "field-code", KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
        Field("내 차량 이름 (예: B)", label, { label = it.take(2) }, "field-label", KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
        Field("탑승 인원", passengers, { passengers = it.filter(Char::isDigit).take(1) }, "field-passengers", KeyboardOptions(keyboardType = KeyboardType.Number))
        Field("그룹 서버 주소", server, { server = it }, "field-server", KeyboardOptions(keyboardType = KeyboardType.Uri))
        ModoButton(
            if (ui.busy) "참여 중…" else "참여하기",
            { onJoin(server, code, label, passengers.toIntOrNull() ?: 1) },
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth().testTag("submit-join"),
        )
    }
}

@Composable
private fun FormScaffold(title: String, subtitle: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize().background(ModoColors.Navy).padding(48.dp)) {
        Column(Modifier.weight(0.8f)) {
            ModoButton("뒤로", onBack, kind = ButtonKind.GHOST, icon = Icons.Rounded.ArrowBack, compact = true)
            Spacer(Modifier.height(32.dp))
            Text(title, style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(12.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = ModoColors.TextMuted)
            Spacer(Modifier.height(20.dp))
            Text(
                "위치는 여행에 참여한 뒤, 직접 위치 공유에 동의한 경우에만 그룹에 공유돼요.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.width(40.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

// region Screen 07 — Journey Completed

@Composable
fun CompletedScreen(ui: ModoUiState, onClose: () -> Unit, onReplay: (() -> Unit)?) {
    val state: JourneyState = ui.journey ?: return
    val converge = remember { Animatable(0f) }
    LaunchedEffect(Unit) { converge.animateTo(1f, tween(1800, easing = FastOutSlowInEasing)) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ModoColors.Navy, ModoColors.SurfaceHigh)))
            .testTag("screen-completed"),
    ) {
        Column(
            Modifier.fillMaxSize().padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ArrivalAnimation(state, converge.value, Modifier.fillMaxWidth(0.62f).height(200.dp))
            Spacer(Modifier.height(30.dp))
            Text("모두 도착했어요.", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(12.dp))
            Text("따로 달려도, 함께 도착하는 여행.", style = MaterialTheme.typography.headlineMedium, color = ModoColors.TextMuted)
            Spacer(Modifier.height(6.dp))
            Text("Move On, Drive On.", fontSize = 26.sp, fontWeight = FontWeight.Light, color = ModoColors.Blue, letterSpacing = 2.sp)
            Spacer(Modifier.height(26.dp))
            ModoCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 28.dp, vertical = 16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(30.dp), verticalAlignment = Alignment.CenterVertically) {
                    Summary("목적지", state.group.destination.name)
                    Summary("차량", "${state.group.members.size}대")
                    Summary("인원", "${state.group.members.sumOf { it.passengerCount }}명")
                    Summary("합류 계획", "${state.proposals.count { it.status == com.modo.domain.model.ProposalStatus.APPROVED }}회")
                    Summary("재조율", "${state.recoverySessions.size}회")
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("도착은 각 차량에서 직접 확인한 결과예요.", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (onReplay != null) ModoButton("Reset Demo", onReplay, kind = ButtonKind.SECONDARY)
                ModoButton("처음 화면으로", onClose, kind = ButtonKind.GHOST)
            }
        }
        if (ui.isDemo) DemoWatermark(Modifier.align(Alignment.BottomStart).padding(18.dp))
    }
}

@Composable
private fun Summary(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

/** 세 차량이 각자의 길에서 목적지 한 점으로 모이는 차분한 마이크로 인터랙션. */
@Composable
private fun ArrivalAnimation(state: JourneyState, t: Float, modifier: Modifier) {
    val members = state.group.members
    val colors = listOf(ModoColors.Blue, ModoColors.Amber, ModoColors.Mint, ModoColors.TextMuted)
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val target = Offset(size.width / 2, size.height * 0.55f)
            members.forEachIndexed { i, _ ->
                val startY = size.height * (0.15f + 0.7f * i / (members.size - 1).coerceAtLeast(1))
                val start = Offset(0f, startY)
                val current = Offset(start.x + (target.x - start.x) * t, start.y + (target.y - start.y) * t)
                drawLine(colors[i % colors.size].copy(alpha = 0.35f), start, current, strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(colors[i % colors.size], radius = 11.dp.toPx(), center = current)
            }
            drawCircle(ModoColors.Mint.copy(alpha = 0.15f * t), radius = 46.dp.toPx() * t, center = target)
            drawCircle(ModoColors.Text, radius = 7.dp.toPx(), center = target)
        }
        Row(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            members.forEachIndexed { i, m ->
                VehicleBadge(state.member(m.id)?.vehicleLabel ?: "", colors[i % colors.size], size = 40)
            }
        }
    }
}

// endregion

@Suppress("unused")
private val pillShape = RoundedCornerShape(50)
