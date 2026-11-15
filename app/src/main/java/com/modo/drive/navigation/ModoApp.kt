package com.modo.drive.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modo.domain.model.JourneyStatus
import com.modo.drive.presentation.AppScreen
import com.modo.drive.presentation.JourneyViewModel
import com.modo.drive.presentation.components.BannerBar
import com.modo.drive.presentation.components.ModoButton
import com.modo.drive.presentation.screens.CompletedScreen
import com.modo.drive.presentation.screens.CreateJourneyScreen
import com.modo.drive.presentation.screens.JoinJourneyScreen
import com.modo.drive.presentation.screens.JourneyScreen
import com.modo.drive.presentation.screens.WelcomeScreen
import com.modo.drive.presentation.theme.ModoColors
import com.modo.drive.presentation.theme.ModoTheme
import com.modo.drive.presentation.theme.ScaledCanvas

/**
 * 화면 전환은 Navigation 라이브러리 대신 [AppScreen] 상태로 결정한다.
 * 주행 중 조작을 줄이기 위해 Journey 화면 안의 단계(Stage)도 그룹 상태에 따라 자동으로 바뀐다.
 */
@Composable
fun ModoApp(vm: JourneyViewModel, onRequestLocationConsent: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    ModoTheme {
        Box(Modifier.fillMaxSize().background(ModoColors.Navy)) {
            ScaledCanvas { isWide ->
                Box(Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = ui.screen,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "screen",
                    ) { screen ->
                        when (screen) {
                            AppScreen.WELCOME -> WelcomeScreen(onStart = { vm.openCreate() }, onJoin = { vm.openJoin() }, onDemo = vm::startDemo)
                            AppScreen.CREATE -> CreateJourneyScreen(ui, vm::backToWelcome, vm::createLiveJourney)
                            AppScreen.JOIN -> JoinJourneyScreen(ui, vm::backToWelcome, vm::joinLiveJourney)
                            AppScreen.JOURNEY ->
                                if (ui.journey?.group?.status == JourneyStatus.ENDED) EndedScreen(onClose = vm::leaveJourney, onReset = if (ui.isDemo) vm::resetDemo else null)
                                else JourneyScreen(ui, vm, isWide, onRequestLocationConsent)
                            AppScreen.COMPLETED -> CompletedScreen(ui, onClose = vm::leaveJourney, onReplay = if (ui.isDemo) vm::resetDemo else null)
                        }
                    }
                    if (ui.screen != AppScreen.JOURNEY) {
                        BannerBar(ui.banner, vm::dismissBanner, vm::retryLastCommand, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }
}

@Composable
private fun EndedScreen(onClose: () -> Unit, onReset: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().testTag("screen-ended"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Text("여행 세션이 종료되었어요.", style = MaterialTheme.typography.displayMedium)
        Text("종료 후 도착하는 요청은 처리하지 않아요.", style = MaterialTheme.typography.bodyLarge)
        if (onReset != null) ModoButton("Reset Demo", onReset)
        ModoButton("처음 화면으로", onClose)
    }
}
