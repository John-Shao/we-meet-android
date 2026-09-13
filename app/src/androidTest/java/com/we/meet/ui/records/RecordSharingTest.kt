package com.we.meet.ui.records

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.MeetingSharingApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingSharingRepository
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
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
class RecordSharingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "share-ui-${UUID.randomUUID()}"
    private val record = RecordDto(UUID.randomUUID().toString(), "meeting", "Fixture", "2026-09-13T00:00:00Z", 1, RecordCapabilitiesDto(true, true, true))
    private val api = Fixture()
    private fun label(id: Int) = context.getString(id)
    private fun await(id: Int) { compose.waitUntil(8000) { compose.onAllNodesWithText(label(id)).fetchSemanticsNodes().isNotEmpty() } }
    private fun show(dark: Boolean = false) {
        compose.setContent { WeMeetTheme(darkTheme = dark) { Surface { Column(Modifier.verticalScroll(rememberScrollState())) {
            RecordSharing(viewer, record, MeetingSharingRepository(api) { viewer }) { viewer }
        } } } }
    }
    private fun button(id: Int) = compose.onNode(hasText(label(id)) and hasClickAction())
    private fun click(id: Int, scroll: Boolean = false) {
        await(id)
        compose.waitUntil(8000) { !button(id).fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        if (scroll) button(id).performScrollTo()
        button(id).performClick()
    }
    private fun choose() { click(R.string.record_share_choose, true); compose.waitUntil(8000) { compose.onAllNodes(isToggleable() and hasText("Person one")).fetchSemanticsNodes().isNotEmpty() } }
    private fun select() { compose.onNode(isToggleable() and hasText("Person one")).performScrollTo().performClick() }
    private fun preview() { choose(); select(); click(R.string.record_share_review_selection); await(R.string.record_share_after_yes) }
    private fun screenshot(name: String) {
        File(context.getExternalFilesDir(null), "record-share-$name.png").outputStream().use {
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun peopleAreNeverPreselectedAndPreviewMustBeConfirmedBeforeSharing() {
        show(); choose(); button(R.string.record_share_review_selection).assertIsNotEnabled()
        assertTrue(api.previews.isEmpty()); assertTrue(api.applies.isEmpty())
        select(); click(R.string.record_share_review_selection); await(R.string.record_share_after_yes)
        assertEquals(listOf(api.person), api.previews.single().userIds); assertTrue(api.applies.isEmpty())
        screenshot("grant-preview")
        click(R.string.record_share_confirm); await(R.string.record_share_explicit_yes)
        assertEquals(SummaryShareRequestDto(listOf(api.person), "grant", api.hash), api.applies.single().second)
    }
    @Test fun candidatePaginationPreservesSelectionButChangingScopeClearsIt() {
        show(); choose(); select()
        click(R.string.records_next, true)
        compose.waitUntil(8000) { compose.onAllNodes(isToggleable() and hasText("Person two")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(isToggleable() and hasText("Person two")).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.record_share_selected, 2)).assertExists()
        click(R.string.record_share_participants, true)
        compose.waitUntil(8000) { api.searches.any { it.first == "participants" } }
        compose.onNodeWithText(context.getString(R.string.record_share_selected, 0)).assertExists()
        button(R.string.record_share_review_selection).assertIsNotEnabled()
        assertTrue(api.applies.isEmpty())
    }
    @Test fun unknownAppliedShareCanBeReconciledAfterExternalRevocationWithoutRegranting() {
        api.status = 503
        show(); preview(); click(R.string.record_share_confirm); await(R.string.summary_controls_reconcile)
        val original = api.applies.single()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        api.shared = false; api.available = false
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.summary_controls_reconcile); assertEquals(1, api.applies.size)
        api.status = 200
        click(R.string.summary_controls_reconcile, true); await(R.string.record_share_accepted)
        assertEquals(original, api.applies.last()); assertFalse(api.shared)
        compose.onNodeWithText(label(R.string.record_share_explicit_yes)).assertDoesNotExist()
    }
    @Test fun revokePreviewExplainsRemainingInheritedAndOriginalAccess() {
        api.shared = true; api.inherited = true
        show(dark = true); click(R.string.record_share_revoke, true)
        await(R.string.record_share_inherited); await(R.string.record_share_original_unchanged)
        compose.onNodeWithText(label(R.string.record_share_after_yes)).assertExists()
        screenshot("revoke-dark")
        assertTrue(api.applies.isEmpty())
        click(R.string.record_share_confirm_revoke); await(R.string.record_share_accepted)
        assertEquals("revoke", api.applies.single().second.operation)
    }
    @Test fun managerRevocationHidesSelectionAndDoesNotApplyOnReturn() {
        show(); preview()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        api.manager = false
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(8000) { compose.onAllNodesWithText(label(R.string.record_share_title)).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Person one").assertDoesNotExist()
        assertTrue(api.applies.isEmpty())
    }
    @Test fun invalidBroaderPreviewCannotBeConfirmed() {
        api.broader = true
        show(); choose(); select(); click(R.string.record_share_review_selection)
        await(R.string.record_share_read_error)
        button(R.string.record_share_confirm).assertIsNotEnabled()
        assertTrue(api.applies.isEmpty())
    }
    private inner class Fixture : MeetingSharingApi {
        val person = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val grant = UUID.randomUUID().toString()
        val hash = "a".repeat(64)
        @Volatile var status = 200
        @Volatile var available = true
        @Volatile var manager = true
        @Volatile var shared = false
        @Volatile var inherited = false
        @Volatile var broader = false
        val applies = CopyOnWriteArrayList<Pair<String, SummaryShareRequestDto>>()
        val previews = CopyOnWriteArrayList<SummaryShareSelectionDto>()
        val searches = CopyOnWriteArrayList<Triple<String, String, String?>>()
        private val receipts = mutableMapOf<String, SummaryShareReceiptDto>()
        override suspend fun access(record: String, cursor: String?): SummaryShareAccessPageDto = SummaryShareAccessPageDto(available && manager, manager,
            if (manager && shared) listOf(SummaryShareAccessDto(person, "Person one", true, true, inherited)) else emptyList())
        override suspend fun candidates(record: String, scope: String, query: String, cursor: String?): RecordPageDto<SummarySharePersonDto> {
            searches += Triple(scope, query, cursor)
            return if (cursor == null) RecordPageDto(listOf(SummarySharePersonDto(person, "Person one")), "second") else RecordPageDto(listOf(SummarySharePersonDto(second, "Person two")), null)
        }
        private fun result(record: String, body: SummaryShareSelectionDto) = SummarySharePreviewDto(record, "Fixture", null, body.operation, "all_record_summary_versions",
            body.userIds.map { SummaryShareRecipientDto(it, if (it == person) "Person one" else "Person two", true, shared, inherited, shared || inherited, inherited, inherited,
                body.operation == "grant", body.operation == "grant" || inherited, grant.takeIf { shared }, "2026-09-13T00:00:00Z".takeIf { shared }) }, false, broader, false, false, hash)
        override suspend fun preview(record: String, body: SummaryShareSelectionDto): SummarySharePreviewDto { previews += body; return result(record, body) }
        override suspend fun apply(record: String, key: String, body: SummaryShareRequestDto): SummaryShareReceiptDto {
            applies += key to body
            val existing = receipts.containsKey(key)
            val receipt = receipts.getOrPut(key) {
                val preview = result(record, SummaryShareSelectionDto(body.userIds, body.operation))
                shared = body.operation == "grant"
                SummaryShareReceiptDto(UUID.randomUUID().toString(), false, preview)
            }
            if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
            return receipt.copy(replayed = existing)
        }
    }
}
