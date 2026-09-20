package com.we.meet.ui.home

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.service.CaptureServiceState
import com.we.meet.ui.records.CaptureContent
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeetingNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun drawerSelectsEachSectionAndExposesCurrentSelection() {
        var dismissed = false
        compose.setContent {
            var selected by remember { mutableStateOf(MeetingSection.VIDEO) }
            WeMeetTheme {
                MeetingNavigationDrawer(selected, MeetingSection.available(true, true),
                    onSelect = { selected = it }, onDismiss = { dismissed = true })
            }
        }
        for (section in MeetingSection.entries) {
            compose.onNodeWithTag("meeting-section-${section.name}").performClick().assertIsSelected()
            MeetingSection.entries.filter { it != section }.forEach {
                compose.onNodeWithTag("meeting-section-${it.name}").assertIsNotSelected()
            }
        }
        compose.waitForIdle()
        File(context.getExternalFilesDir(null), "meeting-navigation-drawer.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithContentDescription(context.getString(R.string.meeting_navigation_close)).performClick()
        assertTrue(dismissed)
    }

    @Test fun restoredUnavailableOrUnknownSectionFallsBackToVideo() {
        assertEquals(MeetingSection.VIDEO, MeetingSection.restore(null, true, true))
        assertEquals(MeetingSection.VIDEO, MeetingSection.restore("retired", true, true))
        assertEquals(MeetingSection.VIDEO, MeetingSection.restore("RECORDING", false, true))
        assertEquals(MeetingSection.VIDEO, MeetingSection.restore("MINUTES", true, false))
        assertEquals(MeetingSection.RECORDS, MeetingSection.restore("RECORDS", true, true))
        assertEquals(listOf(MeetingSection.VIDEO), MeetingSection.available(false, false))
    }

    @Test fun recordingMenuDoesNotStartAudioAndKeepsExplicitStart() {
        var backs = 0
        var starts = 0
        // 录制页是**二级页**(由 `Routes.CAPTURE` 全屏路由进入,不经 MainTabScreen):
        // 顶栏是「返回 + 标题」,不是「汉堡菜单」。此前它挂着一个从不被调用的
        // `onOpenNavDrawer`,于是同时保留了一级页与二级页两套写法 —— 那个参数已删。
        compose.setContent { WeMeetTheme {
            CaptureContent(CaptureServiceState("fixture", ready = true), false,
                onBack = { backs++ },
                onStart = { starts++ }, onPause = {}, onFinish = {}, onRetry = {}, onRecord = null)
        } }
        compose.onNodeWithContentDescription(context.getString(R.string.meeting_navigation)).assertDoesNotExist()
        // 返回箭头那句无障碍名住在 core-design 模块,不在 app 的资源里。
        compose.onNodeWithContentDescription(context.getString(com.we.meet.design.R.string.cd_back)).performClick()
        assertEquals(1, backs)
        assertEquals(0, starts)
        compose.onNodeWithText(context.getString(R.string.capture_start)).performClick()
        assertEquals(1, starts)
    }
}
