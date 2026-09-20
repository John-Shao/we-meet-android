package com.we.meet.ui.records

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.MeetingRecordApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.ui.theme.WeMeetTheme
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.HttpException
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class RecordTrashTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val record = RecordDto(id, "upload", "Private record", "2026-09-20T00:00:00Z", 1, RecordCapabilitiesDto(trash = true), lifecycleRevision = 2)
    private val requests = CopyOnWriteArrayList<RecordLifecycleRequest>()
    private val cursors = CopyOnWriteArrayList<String>()
    @Volatile private var failed = false
    @Volatile private var uncertain = false
    @Volatile private var restored = false
    @Volatile private var done = 0
    private val api = java.lang.reflect.Proxy.newProxyInstance(MeetingRecordApi::class.java.classLoader, arrayOf(MeetingRecordApi::class.java)) { _, method, args ->
        when (method.name) {
            "trash" -> {
                if (failed) throw HttpException(Response.error<Any>(404, "{}".toResponseBody()))
                val cursor = args!![0] as String?
                cursors += cursor ?: "first"
                RecordPageDto(if (restored) emptyList() else listOf(RecordLifecycleDto(id, "Trashed record", "upload", "2026-09-20T00:00:00Z", 3)), if (cursor == null && !restored) "next" else null)
            }
            "lifecycle" -> {
                val input = args!![1] as RecordLifecycleRequest; requests += input
                if (failed) throw HttpException(Response.error<Any>(409, "{}".toResponseBody()))
                if (uncertain) { uncertain = false; throw java.io.IOException("Lost response") }
                restored = input.target == "active"
                RecordLifecycleDto(id, "Private record", "upload", if (restored) null else "2026-09-20T00:00:00Z", input.expectedRevision + 1)
            }
            else -> error("Unexpected read: ${method.name}")
        }
    } as MeetingRecordApi
    private val repository = MeetingRecordRepository(api) { "owner" }
    private fun label(key: Int) = context.getString(key)
    private fun await(key: Int) { compose.waitUntil(8000) { compose.onAllNodesWithText(label(key)).fetchSemanticsNodes().isNotEmpty() } }
    private fun click(key: Int) { await(key); compose.onNodeWithText(label(key)).performClick() }
    private fun show(library: Boolean = false, allowed: Boolean = true) {
        compose.setContent { WeMeetTheme { Surface {
            if (library) RecordTrashSheet("owner", repository) { done++ }
            else RecordTrashControl("owner", record.copy(capabilities = RecordCapabilitiesDto(trash = allowed)), repository) { done++ }
        } } }
    }
    @Test fun removalRequiresConfirmationAndUsesObservedRevision() {
        show(); click(R.string.record_trash_remove)
        assertTrue(requests.isEmpty())
        click(R.string.record_trash_confirm_remove)
        compose.waitUntil(8000) { done == 1 }
        assertEquals(listOf(RecordLifecycleRequest("trashed", 2)), requests.toList())
    }
    @Test fun missingCapabilityHidesRemoveAction() {
        show(allowed = false)
        compose.onNodeWithText(label(R.string.record_trash_remove)).assertDoesNotExist()
        assertTrue(requests.isEmpty())
    }
    @Test fun uncertainRetryKeepsOriginalRevision() {
        uncertain = true; show(); click(R.string.record_trash_remove); click(R.string.record_trash_confirm_remove)
        await(R.string.record_trash_uncertain); assertEquals(1, requests.size)
        click(R.string.record_trash_confirm_remove)
        compose.waitUntil(8000) { done == 1 }
        assertEquals(2, requests.size); assertEquals(requests[0], requests[1])
    }
    @Test fun conflictPreventsRepeatedWrite() {
        failed = true; show(); click(R.string.record_trash_remove); click(R.string.record_trash_confirm_remove)
        await(R.string.record_trash_conflict)
        compose.onNodeWithText(label(R.string.record_trash_confirm_remove)).assertIsNotEnabled()
        assertEquals(1, requests.size); assertEquals(0, done)
    }
    @Test fun trashPagesAndRestoresOnlyAfterConfirmation() {
        show(library = true); click(R.string.records_next)
        compose.waitUntil(8000) { cursors.contains("next") }
        click(R.string.record_trash_restore)
        assertTrue(requests.isEmpty())
        click(R.string.record_trash_confirm_restore)
        await(R.string.record_trash_empty)
        assertEquals(listOf(RecordLifecycleRequest("active", 3)), requests.toList())
    }
    @Test fun backgroundThenAccessLossHidesOldTrashMetadata() {
        show(library = true); await(R.string.record_trash_restore)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED); failed = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.record_trash_unavailable)
        compose.onNodeWithText("Trashed record").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.record_trash_restore)).assertDoesNotExist()
    }
}
