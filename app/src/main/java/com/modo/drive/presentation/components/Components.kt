package com.modo.drive.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.modo.drive.domain.port.SpokenLine
import com.modo.drive.presentation.Banner
import com.modo.drive.presentation.theme.ModoColors

enum class ButtonKind { PRIMARY, SECONDARY, SUCCESS, DANGER, GHOST }

/** 차량용 큰 터치 영역 버튼 (최소 64dp). */
@Composable
fun ModoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val (bg, fg, border) = when (kind) {
        ButtonKind.PRIMARY -> Triple(ModoColors.Blue, Color.White, Color.Transparent)
        ButtonKind.SECONDARY -> Triple(ModoColors.SurfaceHighest, ModoColors.Text, ModoColors.Outline)
        ButtonKind.SUCCESS -> Triple(ModoColors.Mint, ModoColors.Navy, Color.Transparent)
        ButtonKind.DANGER -> Triple(ModoColors.Red.copy(alpha = 0.16f), ModoColors.Red, ModoColors.Red.copy(alpha = 0.5f))
        ButtonKind.GHOST -> Triple(Color.Transparent, ModoColors.TextMuted, ModoColors.Outline)
    }
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = if (compact) 52.dp else 64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(bg.copy(alpha = bg.alpha * alpha))
            .border(1.dp, border.copy(alpha = border.alpha * alpha), RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) 18.dp else 26.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg.copy(alpha = alpha), modifier = Modifier.size(if (compact) 22.dp else 26.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg.copy(alpha = alpha), textAlign = TextAlign.Center)
    }
}

/** 상태 표시: 아이콘 + 텍스트를 함께 쓰고 색으로 톤을 구분한다. */
@Composable
fun StatusPill(text: String, color: Color, icon: ImageVector? = null, modifier: Modifier = Modifier, large: Boolean = false) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = if (large) 16.dp else 12.dp, vertical = if (large) 8.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(if (large) 22.dp else 18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            color = color,
            fontSize = if (large) 19.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun VehicleBadge(label: String, color: Color, size: Int = 52) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f))
            .border(2.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = (size * 0.44f).sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun ModoCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    padding: PaddingValues = PaddingValues(24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(ModoColors.Surface)
            .border(1.dp, accent?.copy(alpha = 0.55f) ?: ModoColors.Outline, RoundedCornerShape(24.dp))
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, modifier = modifier)
}

/** 화면 구석의 시뮬레이션 표시. 실제 차량 데이터로 오인되지 않도록 데모 모드에서 항상 표시한다. */
@Composable
fun DemoWatermark(modifier: Modifier = Modifier) {
    Text(
        "DEMO · Simulated vehicle data",
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ModoColors.Navy.copy(alpha = 0.7f))
            .border(1.dp, ModoColors.Amber.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = ModoColors.Amber.copy(alpha = 0.85f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
    )
}

@Composable
fun BannerBar(banner: Banner?, onDismiss: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = banner != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val b = banner ?: return@AnimatedVisibility
        val color = when (b.tone) {
            Banner.Tone.INFO -> ModoColors.Blue
            Banner.Tone.SUCCESS -> ModoColors.Mint
            Banner.Tone.WARNING -> ModoColors.Amber
            Banner.Tone.ERROR -> ModoColors.Red
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(ModoColors.SurfaceHighest)
                .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
                .padding(start = 22.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(14.dp))
            Text(b.message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
            if (b.retryable) {
                Spacer(Modifier.width(12.dp))
                ModoButton("다시 시도", onRetry, kind = ButtonKind.SECONDARY, icon = Icons.Rounded.Replay, compact = true)
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.size(52.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, contentDescription = "알림 닫기", tint = ModoColors.TextMuted) }
        }
    }
}

/** 음성 안내 자막. 데모에서는 소리 대신 이 자막만 표시된다. */
@Composable
fun SpokenCaption(line: SpokenLine?, modifier: Modifier = Modifier) {
    if (line == null) return
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ModoColors.Surface.copy(alpha = 0.92f))
            .border(1.dp, ModoColors.Outline, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.VolumeUp, contentDescription = null, tint = ModoColors.Blue, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            line.text + if (line.simulated) "  (음성 안내 시뮬레이션)" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = ModoColors.TextMuted,
        )
    }
}

@Composable
fun KeyValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(ModoColors.Outline))
}
