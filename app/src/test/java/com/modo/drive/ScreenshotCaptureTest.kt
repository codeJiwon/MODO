package com.modo.drive

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.modo.domain.model.NavigationActionStatus
import com.modo.drive.demo.DemoData
import com.modo.drive.navigation.ModoApp
import com.modo.drive.presentation.JourneyViewModel
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 문서용 스크린샷 생성기. 실제 Compose UI 코드를 Robolectric(JVM)에서 1280×720dp(=2560×1440px @xhdpi)로 렌더링한다.
 * 기본 테스트 실행에서는 건너뛰고, `-Pmodo.screenshots=true`로 실행할 때만 docs/screenshots/에 저장한다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h720dp-land-xhdpi")
class ScreenshotCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: JourneyViewModel
    private val outDir = System.getProperty("modo.screenshotDir")?.let(::File)

    @Before
    fun setUp() {
        vm = JourneyViewModel(TestEnvironmentFactory())
        compose.setContent { ModoApp(vm = vm, onRequestLocationConsent = {}) }
    }

    private fun shot(name: String) {
        val dir = outDir ?: return
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        dir.mkdirs()
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun jumpTo(index: Int) {
        compose.runOnIdle { vm.demoJumpTo(index) }
        compose.waitUntil(10_000) { vm.ui.value.demo?.currentIndex == index && vm.ui.value.demo?.running == false }
    }

    @Test
    fun captureAllScreens() {
        if (outDir == null) return
        shot("01_welcome")
        compose.runOnIdle { vm.startDemo() }
        compose.waitUntilAtLeastOneExists(hasTestTag("screen-journey"), 10_000)
        shot("02_journey_room")
        jumpTo(1)
        shot("03_mobility_intent")
        jumpTo(2)
        shot("04_smart_rendezvous")
        jumpTo(4)
        shot("05_group_route_action")
        compose.runOnIdle { vm.demoSetNavigation(DemoData.C, NavigationActionStatus.FAILED) }
        compose.waitUntil(5_000) { vm.ui.value.demo?.running == false }
        shot("05b_route_action_failed_retry")
        jumpTo(5)
        shot("06_group_recovery")
        jumpTo(6)
        shot("06b_replan_confirmed")
        jumpTo(8)
        shot("07_journey_completed")
        jumpTo(2)
        compose.runOnIdle { vm.toggleDemoPanel(true) }
        shot("08_demo_controller")
    }
}
