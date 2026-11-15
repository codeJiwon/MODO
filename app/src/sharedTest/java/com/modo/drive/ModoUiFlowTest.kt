package com.modo.drive

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.modo.drive.navigation.ModoApp
import com.modo.drive.presentation.JourneyViewModel
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 화면 단위 UI 테스트. JVM(Robolectric, `./gradlew :app:testDebugUnitTest`)과
 * 에뮬레이터(`./gradlew :app:connectedDebugAndroidTest`)에서 같은 코드로 실행된다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ModoUiFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var vm: JourneyViewModel

    @Before
    fun setUp() {
        vm = JourneyViewModel(TestEnvironmentFactory())
        compose.setContent { ModoApp(vm = vm, onRequestLocationConsent = {}) }
    }

    private fun waitText(text: String, substring: Boolean = false) =
        compose.waitUntilAtLeastOneExists(hasText(text, substring = substring), timeoutMillis = 10_000)

    private fun waitTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), timeoutMillis = 10_000)

    private fun SemanticsNodeInteraction.tap() = performScrollTo().performClick()

    private fun startDemo() {
        compose.onNodeWithTag("demo-experience").performClick()
        waitTag("screen-journey")
    }

    private fun jumpTo(index: Int) {
        compose.runOnIdle { vm.demoJumpTo(index) }
        compose.waitUntil(10_000) { vm.ui.value.demo?.currentIndex == index && vm.ui.value.demo?.running == false }
        compose.waitForIdle()
    }

    @Test
    fun welcome_showsBrandAndActions() {
        waitTag("screen-welcome")
        compose.onNodeWithText("MODO").assertExists()
        compose.onNodeWithText("Move On, Drive On").assertExists()
        compose.onNodeWithText("따로 달려도, 함께 도착하는 여행.").assertExists()
        compose.onNodeWithText("여행 시작하기").assertExists()
        compose.onNodeWithText("초대 코드로 참여").assertExists()
        compose.onNodeWithText("Demo Experience").assertExists()
    }

    @Test
    fun journeyRoom_showsGroupVehiclesAndDemoLabel() {
        startDemo()
        compose.onNodeWithTag("group-name").assertExists()
        compose.onNodeWithText("강릉 여행").assertExists()
        compose.onNodeWithText("공동 목적지 강릉역").assertExists()
        compose.onNodeWithText("A · 내 차량").assertExists()
        compose.onNodeWithText("B · 친구 차량").assertExists()
        compose.onNodeWithText("C · 친구 차량").assertExists()
        compose.onNodeWithText("DEMO · Simulated vehicle data").assertExists()
        compose.onNodeWithTag("stage-room").assertExists()
    }

    @Test
    fun mobilityIntent_restRequestFromVehicleB() {
        startDemo()
        compose.onNodeWithTag("demo-next").performClick()
        waitTag("request-text")
        compose.onNodeWithText("\"우리 잠깐 쉬었다 가자.\"").assertExists()
        compose.onNodeWithText("휴식 요청").assertExists()
        compose.onNodeWithText("차량 B가 휴식을 요청했어요.").assertExists()
        compose.onNodeWithText("합류 장소를 정할까요?").assertExists()
    }

    @Test
    fun smartRendezvous_proposeAndApprove() {
        startDemo()
        jumpTo(1)
        compose.onNodeWithTag("open-picker").tap()
        waitTag("stage-rendezvous")
        compose.onNodeWithText("합류 장소 선택").assertExists()
        compose.onNodeWithTag("place-rest-hoengseong").assertExists()
        compose.onAllNodesWithText("이곳을 제안").assertCountEquals(3)
        compose.runOnIdle { vm.propose(com.modo.drive.demo.DemoData.hoengseong) }
        waitText("합류 장소 제안")
        compose.onNodeWithText("차량 A — 동의").assertExists()
        compose.onNodeWithText("차량 C — 대기").assertExists()

        compose.runOnIdle { vm.demoRespond("B", true) }
        waitText("차량 B — 동의")
        compose.runOnIdle { vm.demoRespond("C", true) }
        waitTag("stage-route")
        compose.onNodeWithTag("phase-AGREED").assertExists()
    }

    @Test
    fun groupRouteAction_separatesAgreementFromApplication() {
        startDemo()
        jumpTo(4)
        waitTag("stage-route")
        compose.onAllNodesWithText("그룹 합의 완료").onFirst().assertExists()
        compose.onNodeWithText("실제 적용 2/3").assertExists()
        compose.onNodeWithText("운전자 확인 대기").assertExists()
        compose.onNodeWithTag("phase-WAITING_CONFIRMATION").assertExists()

        compose.runOnIdle { vm.demoSetNavigation("C", com.modo.domain.model.NavigationActionStatus.REQUESTED) }
        waitText("실제 적용 3/3")
        compose.onNodeWithTag("phase-ALL_APPLIED").assertExists()
    }

    @Test
    fun groupRecovery_missedRendezvousAndReplan() {
        startDemo()
        jumpTo(5)
        waitTag("stage-recovery")
        compose.onNodeWithText("차량 C가 기존 합류 장소에 합류하지 못했습니다.").assertExists()
        compose.onNodeWithText("새로운 합류 장소를 정할까요?").assertExists()
        compose.onNodeWithText("현재 계획 유지").assertExists()
        compose.onNodeWithText("나중에 확인").assertExists()

        compose.onNodeWithTag("recovery-propose").tap()
        waitText("새로운 합류 계획에 대한 그룹의 결정을 기다리고 있어요.")
        compose.onNodeWithText("평창휴게소").assertExists()
        compose.runOnIdle {
            vm.demoRespond("B", true)
        }
        compose.waitUntil(5_000) { vm.ui.value.demo?.running == false }
        compose.runOnIdle { vm.demoRespond("C", true) }
        waitTag("replan-confirmed")
        compose.onNodeWithText("새로운 합류 계획이 확정되었어요.").assertExists()
        compose.onNodeWithText("각 차량이 안전한 경로로 이동합니다.").assertExists()
    }

    @Test
    fun journeyCompleted_showsClosingMessage() {
        startDemo()
        jumpTo(8)
        waitTag("screen-completed")
        compose.onNodeWithText("모두 도착했어요.").assertExists()
        compose.onNodeWithText("따로 달려도, 함께 도착하는 여행.").assertExists()
        compose.onNodeWithText("Move On, Drive On.").assertExists()
    }

    @Test
    fun resetDemo_restoresJourneyRoom() {
        startDemo()
        jumpTo(5)
        compose.onNodeWithTag("demo-toggle").performClick()
        compose.onNodeWithTag("reset-demo").tap()
        compose.waitUntil(10_000) { vm.ui.value.demo?.currentIndex == 0 && vm.ui.value.demo?.running == false }
        waitTag("stage-room")
    }
}
