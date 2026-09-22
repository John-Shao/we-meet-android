package com.we.meet.ui.records

import android.content.ClipboardManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.BuildConfig
import com.we.meet.R
import com.we.meet.data.api.MeetingSharingApi
import com.we.meet.data.api.dto.MaterialAccessDto
import com.we.meet.data.api.dto.MaterialCandidatesDto
import com.we.meet.data.api.dto.MaterialChangeDto
import com.we.meet.data.api.dto.MaterialReceiptDto
import com.we.meet.data.api.dto.RecordCapabilitiesDto
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.api.dto.RecordPageDto
import com.we.meet.data.api.dto.SummaryShareAccessPageDto
import com.we.meet.data.api.dto.SummarySharePersonDto
import com.we.meet.data.api.dto.SummarySharePreviewDto
import com.we.meet.data.api.dto.SummaryShareReceiptDto
import com.we.meet.data.api.dto.SummaryShareRequestDto
import com.we.meet.data.api.dto.SummaryShareSelectionDto
import com.we.meet.data.repository.MeetingSharingRepository
import com.we.meet.ui.theme.WeMeetTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * 「复制记录链接」是 Web 有、App 原先没有的入口(Web `summarySharing.copyLink`)。
 * 链接形状由 [RecordLinksTest] 的往返用例守着,这里只验 UI 接得上:按钮在、
 * 点完有回执、进剪贴板的确实是同一串。
 */
class RecordSharingCopyLinkTest {
    @get:Rule val compose = createComposeRule()

    private val recordId = "11111111-1111-4111-8111-111111111111"
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun record() = RecordDto(recordId, "audio_recording", "Kickoff", "2026-09-18T01:30:00Z", 1,
        RecordCapabilitiesDto(readSummary = true, readTranscript = true))

    private inner class FakeSharingApi : MeetingSharingApi {
        override suspend fun access(record: String, cursor: String?) =
            SummaryShareAccessPageDto(available = true, canManage = true, results = emptyList(), nextCursor = null)
        override suspend fun candidates(record: String, scope: String, query: String, cursor: String?) =
            RecordPageDto(emptyList<SummarySharePersonDto>(), null)
        // 这条用例只碰「复制链接」,授权/预览路径不经过这里。
        override suspend fun preview(record: String, body: SummaryShareSelectionDto): SummarySharePreviewDto = error("unused")
        override suspend fun apply(record: String, key: String, body: SummaryShareRequestDto): SummaryShareReceiptDto = error("unused")
        // 同理:素材协作是另一个入口(ui/records/MaterialActions.kt),本用例不经过。
        override suspend fun materialAccess(record: String, scope: String): MaterialAccessDto = error("unused")
        override suspend fun materialCandidates(record: String, scope: String, query: String, cursor: String?, kind: String): MaterialCandidatesDto = error("unused")
        override suspend fun materialChange(record: String, scope: String, key: String, body: MaterialChangeDto): MaterialReceiptDto = error("unused")
        override suspend fun retryMaterialNotices(record: String, scope: String) = error("unused")
    }

    private fun show() {
        compose.setContent { WeMeetTheme {
            RecordSharing("owner", record(), MeetingSharingRepository(FakeSharingApi()) { "owner" }) { "owner" }
        } }
    }

    @Test fun copyLinkShowsTheReceipt() {
        show()
        compose.onNodeWithText(label(R.string.record_share_copy_link)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.record_share_copied)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.record_share_copy_link)).performClick()
        compose.onNodeWithText(label(R.string.record_share_copied)).assertIsDisplayed()
    }

    @Test fun clipboardCarriesTheDeepLinkShapeTheAppAccepts() {
        show()
        compose.onNodeWithText(label(R.string.record_share_copy_link)).performClick()
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(ClipboardManager::class.java)
        val copied = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
        assertNotNull("clipboard read blocked by the harness", copied)
        assertEquals(RecordLinks.share(recordId, BuildConfig.WE_MEET_BASE_URL), copied)
        assertNotNull(RecordLinks.parse(copied!!, BuildConfig.WE_MEET_BASE_URL))
    }
}
