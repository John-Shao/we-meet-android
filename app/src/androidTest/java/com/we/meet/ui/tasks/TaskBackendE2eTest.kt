package com.we.meet.ui.tasks

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.MainActivity
import com.we.meet.R
import com.we.meet.WeMeetApp
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * Opt-in device E2E against the configured backend.
 *
 * The test reuses an existing app session when possible. A clean Gradle device
 * install has no app data, so the outer rule falls back to the documented demo
 * OTP account before MainActivity launches. Override the defaults with runner
 * arguments `e2ePhone` and `e2eOtp` when running against another environment.
 * The test creates a uniquely named task, verifies completion can be toggled in
 * both directions, and deletes the task before returning.
 */
@RunWith(AndroidJUnit4::class)
class TaskBackendE2eTest {
    private val sessionRule = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val app = instrumentation.targetContext.applicationContext as WeMeetApp
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // The push SDK can request this as soon as an authenticated
                // Activity starts. Grant it before Compose launches so a clean
                // connected-test install is not covered by a system dialog.
                instrumentation.uiAutomation.grantRuntimePermission(
                    instrumentation.targetContext.packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            }
            if (app.tokenStore.isLoggedIn()) return

            val arguments = InstrumentationRegistry.getArguments()
            val phone = arguments.getString("e2ePhone") ?: DEFAULT_E2E_PHONE
            val otp = arguments.getString("e2eOtp") ?: DEFAULT_E2E_OTP
            runBlocking {
                app.authRepository.sendOtp(phone).getOrThrow()
                app.authRepository.verifyOtp(phone, otp).getOrThrow()
            }
        }
    }

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(sessionRule).around(composeRule)

    @Test
    fun createCompleteRestoreAndDeleteTask() {
        val title = "E2E task ${System.currentTimeMillis()}"
        val deleteTask = composeRule.activity.getString(R.string.task_delete)
        val deleteConfirmTitle = composeRule.activity.getString(R.string.task_delete_confirm_title)
        val delete = composeRule.activity.getString(R.string.task_delete_navigation_item)

        composeRule.onNodeWithTag("main-tab-tasks").performClick()
        waitForTag(TASK_CREATE_FAB_TEST_TAG)

        composeRule.onNodeWithTag(TASK_CREATE_FAB_TEST_TAG).performClick()
        waitForTag(TASK_CREATE_PAGE_TEST_TAG)
        composeRule.onNodeWithTag(TASK_CREATE_TITLE_TEST_TAG).performTextInput(title)
        composeRule.onNodeWithTag(TASK_CREATE_SUBMIT_TEST_TAG).performClick()

        waitForTag(TASK_DETAIL_TEST_TAG, timeoutMillis = 45_000)
        composeRule.onNodeWithTag(TASK_DETAIL_TITLE_TEST_TAG)
            .assertIsDisplayed()
        composeRule.onNodeWithText(title).assertIsDisplayed()

        waitForTag(taskDetailToggleTestTag(done = false))
        composeRule.onNodeWithTag(taskDetailToggleTestTag(done = false)).performClick()
        waitForTag(taskDetailToggleTestTag(done = true), timeoutMillis = 30_000)
        composeRule.onNodeWithTag(taskDetailToggleTestTag(done = true)).performClick()
        waitForTag(taskDetailToggleTestTag(done = false), timeoutMillis = 30_000)

        composeRule.onNodeWithTag(TASK_DETAIL_MORE_TEST_TAG).performClick()
        composeRule.onNodeWithText(deleteTask).performClick()
        waitForText(deleteConfirmTitle)
        composeRule.onNodeWithText(delete).performClick()

        waitForTag(TASK_LIST_TEST_TAG, timeoutMillis = 30_000)
        composeRule.onNodeWithTag(TASK_DETAIL_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(title).assertDoesNotExist()
    }

    private fun waitForTag(tag: String, timeoutMillis: Long = 30_000) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(text: String, timeoutMillis: Long = 30_000) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val DEFAULT_E2E_PHONE = "13800000009"
        const val DEFAULT_E2E_OTP = "123456"
    }
}
