package com.we.meet.ui.records

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
class RecordPurgeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val item = RecordLifecycleDto(id, "Permanent deletion fixture", "upload", "2026-09-20T00:00:00Z", 3)
    private val writes = CopyOnWriteArrayList<RecordPurgeRequest>()
    @Volatile private var receipt = RecordPurgeDto(id, "pending", 3, "2026-09-20T00:00:00Z")
    @Volatile private var uncertain = false
    @Volatile private var denied = false
    @Volatile private var allowed = true
    private val api = java.lang.reflect.Proxy.newProxyInstance(MeetingRecordApi::class.java.classLoader, arrayOf(MeetingRecordApi::class.java)) { _, method, args ->
        when (method.name) {
            "trash" -> RecordPageDto(listOf(if (writes.isEmpty()) item else item.copy(lifecycleRevision = 4, purge = receipt)), purgeAvailable = allowed)
            "purge" -> {
                writes += args!![1] as RecordPurgeRequest
                if (uncertain) { uncertain = false; throw java.io.IOException("lost response") }
                receipt = receipt.copy(state = "pending", canRetry = false)
                receipt
            }
            "purgeStatus" -> {
                if (denied) throw HttpException(Response.error<Any>(404, "{}".toResponseBody()))
                receipt
            }
            else -> error("Unexpected operation: ${method.name}")
        }
    } as MeetingRecordApi
    private val repo = MeetingRecordRepository(api) { "owner" }
    private fun label(key: Int) = context.getString(key)
    private fun await(key: Int) { compose.waitUntil(8000) { compose.onAllNodesWithText(label(key)).fetchSemanticsNodes().isNotEmpty() } }
    private fun click(key: Int) { await(key); compose.onNodeWithText(label(key)).performClick() }
    private fun show(row: RecordLifecycleDto = item, library: Boolean = false) {
        compose.setContent { WeMeetTheme { Surface {
            if (library) RecordTrashSheet("owner", repo) {}
            else RecordPurgeConfirmation("owner", row, repo) {}
        } } }
    }
    @Test fun permanentDeletionRequiresAcknowledgementAndExplicitConfirmation() {
        show()
        compose.onNodeWithText(label(R.string.record_purge_confirm)).assertIsNotEnabled()
        assertTrue(writes.isEmpty())
        compose.onNode(isToggleable()).performClick()
        click(R.string.record_purge_confirm)
        await(R.string.record_purge_pending)
        assertEquals(listOf(RecordPurgeRequest(3)), writes.toList())
        compose.onNodeWithText(label(R.string.record_purge_confirm)).assertDoesNotExist()
    }
    @Test fun lostResponseNeverAutomaticallyRepeatsAndRetryKeepsRevision() {
        uncertain = true; show()
        compose.onNode(isToggleable()).performClick(); click(R.string.record_purge_confirm)
        await(R.string.record_purge_uncertain); assertEquals(1, writes.size)
        click(R.string.record_purge_confirm); await(R.string.record_purge_pending)
        assertEquals(listOf(RecordPurgeRequest(3), RecordPurgeRequest(3)), writes.toList())
    }
    @Test fun failedReceiptRetriesOriginalIntentNotCurrentLifecycleVersion() {
        receipt = receipt.copy(state = "failed", canRetry = true)
        show(item.copy(lifecycleRevision = 4, purge = receipt))
        click(R.string.record_purge_retry); await(R.string.record_purge_pending)
        assertEquals(listOf(RecordPurgeRequest(3)), writes.toList())
    }
    @Test fun statusAccessLossHidesPrivateDetailAndWriteActions() {
        denied = true; show(item.copy(purge = receipt))
        await(R.string.record_trash_unavailable)
        compose.onNodeWithText(item.title).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.record_purge_confirm)).assertDoesNotExist()
        assertTrue(writes.isEmpty())
    }
    @Test fun oldBackendDoesNotOfferPermanentDeletion() {
        allowed = false; show(library = true)
        await(R.string.record_trash_restore)
        compose.onNodeWithText(label(R.string.record_purge_remove)).assertDoesNotExist()
    }
}
