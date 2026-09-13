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
class RecordExportsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "export-ui-${UUID.randomUUID()}"
    private val record = UUID.randomUUID().toString()
    private val source = ExportSource("human", UUID.randomUUID().toString(), "Human revision 2")
    private val api = Fixture()
    private val opened = CopyOnWriteArrayList<String>()
    private fun label(id: Int) = context.getString(id)
    private fun await(id: Int) { compose.waitUntil(8000) { compose.onAllNodesWithText(label(id)).fetchSemanticsNodes().isNotEmpty() } }
    private fun show(dark: Boolean = false) {
        compose.setContent { WeMeetTheme(darkTheme = dark) { Surface { Column(Modifier.verticalScroll(rememberScrollState())) {
            RecordExportWorkspace(viewer, record, listOf(source), MeetingDeliveryRepository(api) { viewer }, { viewer }) { opened += it }
        } } } }
    }
    private fun click(id: Int) {
        await(id)
        compose.waitUntil(8000) { !compose.onNode(hasText(label(id)) and hasClickAction()).fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        val node = compose.onNode(hasText(label(id)) and hasClickAction())
        if (id != R.string.record_export_confirm && id != R.string.record_export_confirm_retry) node.performScrollTo()
        node.performClick()
    }
    private fun preview() { click(R.string.record_export_choose); compose.onNodeWithText(source.label).performClick(); await(R.string.record_export_confirm) }
    private fun screenshot(name: String, dialog: Boolean = false) {
        File(context.getExternalFilesDir(null), "record-export-$name.png").outputStream().use {
            (if (dialog) compose.onNode(isDialog()) else compose.onRoot()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun previewDoesNotWriteAndExplicitConfirmationUsesItsHashAndOpensNativeDocument() {
        show(); preview()
        compose.waitUntil(8000) { compose.onAllNodesWithText("Frozen document content").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(api.creates.isEmpty()); assertEquals(1, api.previews)
        screenshot("preview", dialog = true)
        click(R.string.record_export_confirm); await(R.string.record_export_ready)
        assertEquals(SummaryExportRequestDto("human", source.id, "zh", api.hash), api.creates.single().second)
        click(R.string.record_export_open); assertEquals(listOf(api.document), opened.toList())
    }
    @Test fun unknownCreateRestoresExactBodyWithoutAutomaticCreation() {
        api.status = 503
        show(); preview(); click(R.string.record_export_confirm); await(R.string.record_export_reconcile_create)
        val frozen = api.creates.single()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        api.available = false
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.record_export_reconcile_create); assertEquals(1, api.creates.size)
        api.status = 200
        click(R.string.record_export_reconcile_create); await(R.string.record_export_ready)
        assertEquals(frozen, api.creates.last())
    }
    @Test fun retryUsesOriginalFrozenCopyAndExpectedAttempt() {
        api.delivery = "failed"
        show(); click(R.string.record_export_retry_preview)
        compose.waitUntil(8000) { compose.onAllNodesWithText("Original frozen content").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(api.retries.isEmpty())
        click(R.string.record_export_confirm_retry); await(R.string.record_export_ready)
        assertEquals(api.id, api.retries.single().first)
        assertEquals(SummaryExportRetryDto(1, api.frozenHash), api.retries.single().third)
        assertTrue(api.creates.isEmpty())
    }
    @Test fun revokedAccessClearsPreviewAndPreventsCreationAfterBackground() {
        show(); preview()
        compose.waitUntil(8000) { compose.onAllNodesWithText("Frozen document content").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        api.denied = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.record_export_read_error)
        compose.onNodeWithText("Frozen document content").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.record_export_confirm)).assertDoesNotExist()
        assertTrue(api.creates.isEmpty())
    }
    @Test fun previewFailureDisablesConfirmationWithoutWriting() {
        api.badPreview = true
        show(); preview(); await(R.string.record_export_read_error)
        compose.onNode(hasText(label(R.string.record_export_confirm)) and hasClickAction()).assertIsNotEnabled()
        assertTrue(api.creates.isEmpty())
    }
    @Test fun unknownOrConfigurationChangedDocumentCannotOpen() {
        api.delivery = "uncertain"; api.errorCode = "document_conflict"
        show(dark = true); await(R.string.record_export_uncertain)
        compose.onNodeWithText(label(R.string.record_export_ready)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.record_export_open)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.record_export_retry_preview)).assertDoesNotExist()
        screenshot("uncertain-dark")
        api.delivery = "ready"; api.canOpen = false
        await(R.string.record_export_ready)
        compose.onNodeWithText(label(R.string.record_export_open)).assertDoesNotExist()
        assertTrue(api.creates.isEmpty())
    }
    private inner class Fixture : MeetingDeliveryApi {
        val id = UUID.randomUUID().toString()
        val document = UUID.randomUUID().toString()
        val hash = "a".repeat(64)
        val frozenHash = "b".repeat(64)
        @Volatile var status = 200
        @Volatile var available = true
        @Volatile var denied = false
        @Volatile var badPreview = false
        @Volatile var delivery: String? = null
        @Volatile var attempt = 1
        @Volatile var canOpen = true
        @Volatile var errorCode = ""
        @Volatile var previews = 0
        val creates = CopyOnWriteArrayList<Pair<String, SummaryExportRequestDto>>()
        val retries = CopyOnWriteArrayList<Triple<String, String, SummaryExportRetryDto>>()
        private fun access() { if (denied) throw HttpException(Response.error<Any>(403, "{}".toResponseBody())) }
        private fun row() = SummaryExportDto(id, source.kind, source.id, "zh", requireNotNull(delivery), attempt,
            document.takeIf { delivery == "ready" }, delivery == "ready" && canOpen, errorCode, "2026-09-13T00:00:00Z")
        override suspend fun exports(record: String): SummaryExportsDto { access(); return SummaryExportsDto(available, if (delivery == null) emptyList() else listOf(row())) }
        override suspend fun preview(record: String, kind: String, source: String, language: String): SummaryExportPreviewDto {
            access(); previews++
            return SummaryExportPreviewDto("Minutes document", "Frozen document content", if (badPreview) "invalid" else hash, kind, source, language)
        }
        override suspend fun create(record: String, key: String, body: SummaryExportRequestDto): SummaryExportReceiptDto {
            creates += key to body
            if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
            delivery = "ready"; return SummaryExportReceiptDto(row(), creates.size > 1)
        }
        override suspend fun retryPreview(record: String, export: String): SummaryExportRetryPreviewDto {
            access(); return SummaryExportRetryPreviewDto(row(), "Original document", "Original frozen content", frozenHash)
        }
        override suspend fun retryExport(record: String, export: String, key: String, body: SummaryExportRetryDto): SummaryExportReceiptDto {
            retries += Triple(export, key, body)
            attempt = 2; delivery = "ready"; return SummaryExportReceiptDto(row(), false)
        }
        override suspend fun notices(record: String): SummaryNoticesDto = error("No notification reads")
        override suspend fun retryNotice(record: String, notice: String, key: String, body: SummaryNoticeRetryDto): SummaryNoticeReceiptDto = error("No notification writes")
    }
}
