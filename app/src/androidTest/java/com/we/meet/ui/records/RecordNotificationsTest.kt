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
import com.we.meet.data.api.MeetingDeliveryApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingDeliveryRepository
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
class RecordNotificationsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "notice-ui-${UUID.randomUUID()}"
    private val record = RecordDto(UUID.randomUUID().toString(), "audio_recording", "Fixture", "2026-09-13T00:00:00Z", 1, RecordCapabilitiesDto(true, true, true))
    private val summary = UUID.randomUUID().toString()
    private val api = Fixture()
    private val opened = CopyOnWriteArrayList<String>()
    private fun label(id: Int) = context.getString(id)
    private fun await(id: Int) { compose.waitUntil(8000) { compose.onAllNodesWithText(label(id)).fetchSemanticsNodes().isNotEmpty() } }
    private fun show(dark: Boolean = false) {
        compose.setContent { WeMeetTheme(darkTheme = dark) { Surface { Column(Modifier.verticalScroll(rememberScrollState())) {
            RecordNotifications(viewer, record, MeetingDeliveryRepository(api) { viewer }, { viewer }) { opened += it }
        } } } }
    }
    private fun review() {
        await(R.string.record_notice_retry)
        compose.waitUntil(8000) { !compose.onNodeWithText(label(R.string.record_notice_retry)).fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        compose.onNodeWithText(label(R.string.record_notice_retry)).performScrollTo().performClick()
    }
    private fun confirm() { compose.onNode(hasText(label(R.string.record_notice_confirm)) and hasClickAction()).performClick() }
    private fun screenshot(name: String, dialog: Boolean = false) {
        File(context.getExternalFilesDir(null), "record-notice-$name.png").outputStream().use {
            (if (dialog) compose.onNode(isDialog()) else compose.onRoot()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun explicitConfirmationRetriesOriginalNoticeAndLinksExactVersion() {
        show(); await(R.string.record_notice_failed)
        assertTrue(api.sent.isEmpty())
        compose.onNodeWithText(label(R.string.record_notice_open_summary)).performScrollTo().performClick()
        assertEquals(listOf(summary), opened.toList())
        review(); assertTrue(api.sent.isEmpty()); screenshot("confirm", dialog = true)
        confirm(); await(R.string.record_notice_delivered)
        assertEquals(api.id, api.sent.single().first); assertEquals(1, api.sent.single().third.expectedAttempt)
    }
    @Test fun unknownRetrySurvivesBackgroundAndReconcilesEvenWhenNewDeliveryDisabled() {
        api.status = 503
        show(); review(); confirm(); await(R.string.summary_controls_reconcile)
        val first = api.sent.single()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        api.available = false
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.summary_controls_reconcile); assertEquals(1, api.sent.size)
        api.status = 200
        compose.onNodeWithText(label(R.string.summary_controls_reconcile)).performScrollTo().performClick()
        await(R.string.record_notice_delivered); assertEquals(first, api.sent.last())
    }
    @Test fun backgroundRechecksPermissionAndHidesPrivateRecipientAndNotice() {
        show(); await(R.string.record_notice_failed)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        api.denied = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.record_notice_read_error)
        compose.onNodeWithText(label(R.string.record_notice_open_summary)).assertDoesNotExist()
        compose.onAllNodesWithText("Fixture owner", substring = true).assertCountEquals(0)
        assertTrue(api.sent.isEmpty())
    }
    @Test fun changedAttemptDisablesStaleConfirmationWithoutSending() {
        show(); review()
        api.attempt = 2
        compose.waitUntil(8000) { compose.onAllNodesWithText(label(R.string.record_notice_changed)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(label(R.string.record_notice_confirm)) and hasClickAction()).assertIsNotEnabled()
        assertTrue(api.sent.isEmpty())
    }
    @Test fun uncertainDeliveryNeverLooksDeliveredAndReadsNeverRetry() {
        api.delivery = "uncertain"; api.errorCode = "message_conflict"
        show(dark = true); await(R.string.record_notice_uncertain)
        compose.onNodeWithText(label(R.string.record_notice_delivered)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.record_notice_retry)).assertDoesNotExist()
        screenshot("uncertain-dark"); assertTrue(api.sent.isEmpty())
    }
    private inner class Fixture : MeetingDeliveryApi {
        val id = UUID.randomUUID().toString()
        @Volatile var status = 200
        @Volatile var available = true
        @Volatile var denied = false
        @Volatile var delivery = "failed"
        @Volatile var attempt = 1
        @Volatile var errorCode = ""
        val sent = CopyOnWriteArrayList<Triple<String, String, SummaryNoticeRetryDto>>()
        private fun row() = SummaryNoticeDto(id, summary, delivery, attempt, errorCode, record.originAt)
        override suspend fun notices(record: String): SummaryNoticesDto {
            if (denied) throw HttpException(Response.error<Any>(403, "{}".toResponseBody()))
            return SummaryNoticesDto(available, "owner", true, listOf(row()), listOf(SummaryNoticeRecipientDto(id, "Fixture owner")))
        }
        override suspend fun retryNotice(record: String, notice: String, key: String, body: SummaryNoticeRetryDto): SummaryNoticeReceiptDto {
            sent += Triple(notice, key, body)
            if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
            attempt = 2; delivery = "delivered"
            return SummaryNoticeReceiptDto(row(), sent.size > 1)
        }
        override suspend fun exports(record: String): SummaryExportsDto = error("No document reads")
        override suspend fun preview(record: String, kind: String, source: String, language: String): SummaryExportPreviewDto = error("No document reads")
        override suspend fun retryPreview(record: String, export: String): SummaryExportRetryPreviewDto = error("No document reads")
        override suspend fun create(record: String, key: String, body: SummaryExportRequestDto): SummaryExportReceiptDto = error("No writes")
        override suspend fun retryExport(record: String, export: String, key: String, body: SummaryExportRetryDto): SummaryExportReceiptDto = error("No writes")
    }
}
