package com.we.meet.ui.records

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.MeetingRecordApi
import com.we.meet.data.api.RecordingUploadApi
import com.we.meet.data.api.RecordingUploadBegin
import com.we.meet.data.api.RecordingUploadFinish
import com.we.meet.data.api.RecordingUploadSign
import com.we.meet.data.api.RecordingUploadCapabilities
import com.we.meet.data.api.RecordingUploadComplete
import com.we.meet.data.api.RecordingUploadPresign
import com.we.meet.data.api.RecordingUploadRetry
import com.we.meet.data.api.RecordingUploadState
import com.we.meet.data.api.RecordingUploadTicket
import com.we.meet.data.api.dto.RecordCapabilitiesDto
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.api.dto.RecordMediaDto
import com.we.meet.data.api.dto.RecordOnlineTranscriptDto
import com.we.meet.data.api.dto.RecordOriginalSegmentDto
import com.we.meet.data.api.dto.RecordPageDto
import com.we.meet.data.api.dto.RecordSnapshotDto
import com.we.meet.data.api.dto.RecordSpeakerDto
import com.we.meet.data.api.dto.RecordSummaryContentDto
import com.we.meet.data.api.dto.RecordSummaryPointDto
import com.we.meet.data.api.dto.RecordSummaryVersionDto
import com.we.meet.data.api.dto.RecordTitleRequestDto
import com.we.meet.data.api.dto.RecordUploadDto
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.data.repository.RecordingUploadRepository
import com.we.meet.ui.theme.WeMeetTheme
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Not an assertion suite: renders the 会议 → 录制 / 记录 / 纪要 surfaces with fixed
 * fixtures and writes PNGs to the app's external files dir so the layout can be
 * reviewed on a device without a live backend (`adb pull .../files/ux`).
 */
class MeetingRecordsSnapshotTest {
    @get:Rule val compose = createComposeRule()

    private val viewer = "11111111-1111-1111-1111-111111111111"
    private val session = "00000700-1111-1111-1111-111111111111"
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun uuid(n: Int) = "%08d-1111-1111-1111-111111111111".format(n)

    private fun meeting(id: String, title: String, at: String, summary: Boolean = true, ongoing: Boolean = false) =
        RecordDto(id, "meeting", title, at, 1, RecordCapabilitiesDto(readSummary = true, readTranscript = true, rename = true),
            meetingSessionId = session, isOngoing = ongoing, hasSummary = summary, retentionMode = "media", owner = "王敏")

    private fun captured(id: String, title: String, at: String) =
        RecordDto(id, "audio_recording", title, at, 1, RecordCapabilitiesDto(readSummary = true, readTranscript = true, rename = true),
            captureId = uuid(id.hashCode() and 0xffff), hasSummary = true, retentionMode = "media", owner = "李强")

    private fun imported(id: String, title: String, at: String, media: String, status: String) =
        RecordDto(id, "upload", title, at, 1, RecordCapabilitiesDto(readSummary = true, readTranscript = true),
            upload = RecordUploadDto(media, "$title.$media", 23_000_000, status), retentionMode = "media", owner = "赵磊")

    private val catalogue = listOf(
        meeting(uuid(1), "产品双周评审会", "2026-09-18T01:30:00Z"),
        captured(uuid(2), "客户拜访录音", "2026-09-17T09:05:00Z"),
        imported(uuid(3), "季度复盘会议", "2026-09-17T02:00:00Z", "video", "succeeded"),
        imported(uuid(4), "电话会议录音", "2026-09-16T23:10:00Z", "audio", "processing"),
        meeting(uuid(5), "技术方案讨论", "2026-09-16T06:40:00Z", summary = false),
        meeting(uuid(6), "临时同步", "2026-09-15T08:00:00Z", ongoing = true),
        imported(uuid(7), "访谈素材 A", "2026-09-14T03:20:00Z", "audio", "failed"),
        captured(uuid(8), "需求澄清录音", "2026-09-13T07:15:00Z"),
    )

    private val summary = RecordSummaryVersionDto(
        id = uuid(80), stage = "final", inputSnapshotId = uuid(81), inputRevision = 1, isCurrent = true,
        createdAt = "2026-09-18T02:05:00Z", deliveryStatus = "complete", asrStatus = "finished",
        sourceThroughMs = 125_000,
        content = RecordSummaryContentDto(
            overview = "本次评审确认了三个发布阻塞项的处理顺序，并把灰度范围收敛到两个部门。",
            decisions = listOf(RecordSummaryPointDto("本周内完成风控规则灰度，范围限定在两个部门。", emptyList(), ownerText = "王敏", dueText = "9 月 19 日")),
            actionItems = listOf(
                RecordSummaryPointDto("补齐压测报告并同步给安全团队。", emptyList(), ownerText = "李强", dueText = "9 月 20 日"),
                RecordSummaryPointDto("整理客户反馈清单，标注优先级。", emptyList(), ownerText = "赵磊"),
            ),
            chapters = listOf(RecordSummaryPointDto("发布节奏与灰度范围", emptyList())),
            openQuestions = listOf(RecordSummaryPointDto("海外节点的合规评估由谁负责？", emptyList())),
        ),
    )

    private inner class FakeApi(
        private val records: List<RecordDto> = catalogue,
        private val versions: List<RecordSummaryVersionDto>? = null,
    ) : MeetingRecordApi {
        override suspend fun records(scope: String, source: String?, hasSummary: Boolean?, query: String?, cursor: String?, isOngoing: Boolean?) =
            RecordPageDto(
                records.filter { (source == null || it.sourceType == source) && (isOngoing == null || it.isOngoing == isOngoing) },
                if (cursor == null && records.isNotEmpty()) "next-page" else null,
            )

        override suspend fun record(recordId: String) = records.first { it.id == recordId }

        override suspend fun rename(recordId: String, body: RecordTitleRequestDto) = records.first { it.id == recordId }
    override suspend fun media(recordId: String, download: Boolean?) = error("Media not configured")
        override suspend fun transcriptExport(url: String) = error("Export not configured")
    override suspend fun correctOriginal(recordId: String, segmentId: String, body: com.we.meet.data.api.dto.RecordCorrectionRequest) = error("Correction not configured")
    override suspend fun revertOriginal(recordId: String, segmentId: String, expectedRevision: Int) = error("Correction not configured")

        override suspend fun summaries(recordId: String, cursor: String?, versionId: String?): RecordPageDto<RecordSummaryVersionDto> =
            RecordPageDto(versions ?: listOf(summary), null)

        override suspend fun snapshot(recordId: String, snapshotId: String) = RecordSnapshotDto(snapshotId, 1, emptyList())

        override suspend fun transcripts(recordId: String, revision: Int, query: String?, cursor: String?) =
            RecordPageDto(listOf(RecordOnlineTranscriptDto(uuid(900), session, "王敏", "我们先过一遍上周遗留的两个风险项。", "zh", "2026-09-18T01:31:00Z")), null)

        override suspend fun originals(recordId: String, revision: Int, query: String?, speakerId: String?, cursor: String?, atMs: Long?) =
            RecordPageDto(emptyList<RecordOriginalSegmentDto>(), null)

        override suspend fun speakers(recordId: String, cursor: String?) = RecordPageDto(emptyList<RecordSpeakerDto>(), null)

        override suspend fun attributeSpeaker(
            recordId: String,
            speakerId: String,
            body: com.we.meet.data.api.dto.RecordAttributionRequest,
        ): RecordSpeakerDto = error("Speaker attribution not configured")

        override suspend fun attributionCandidates(
            recordId: String,
            query: String?,
        ): com.we.meet.data.api.dto.RecordAttributionCandidatePageDto =
            com.we.meet.data.api.dto.RecordAttributionCandidatePageDto()

        override suspend fun resolve(roomId: String, sessionId: String?) = records.first()
    }

    private fun repository(records: List<RecordDto> = catalogue) = MeetingRecordRepository(FakeApi(records), { viewer })

    private inner class FakeUploadApi : RecordingUploadApi {
        override suspend fun capabilities() = RecordingUploadCapabilities(true, 200L * 1024 * 1024, listOf("mp3", "m4a", "wav", "mp4", "mov"))
        override suspend fun upload(key: RequestBody, audio: MultipartBody.Part, context: RequestBody, hotwords: RequestBody) =
            RecordingUploadState(uuid(1), "queued", 1)
        override suspend fun state(recordId: String) = RecordingUploadState(recordId, "succeeded", 1)
        override suspend fun retry(recordId: String, body: RecordingUploadRetry) = RecordingUploadState(recordId, "queued", 2)
        override suspend fun multipartBegin(body: RecordingUploadBegin) = error("Chunked upload not configured")
        override suspend fun multipartResume(sessionId: String) = error("Chunked upload not configured")
        override suspend fun multipartSign(sessionId: String, body: RecordingUploadSign) = error("Chunked upload not configured")
        override suspend fun multipartComplete(sessionId: String, body: RecordingUploadFinish) = error("Chunked upload not configured")
        override suspend fun multipartAbort(sessionId: String) = error("Chunked upload not configured")
        override suspend fun presign(body: RecordingUploadPresign): RecordingUploadTicket = error("Direct upload not configured")
        override suspend fun complete(body: RecordingUploadComplete): RecordingUploadState = error("Direct upload not configured")
    }

    private fun uploadRepository() = RecordingUploadRepository(FakeUploadApi(), currentViewer = { viewer })

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ux").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun recordingSection() {
        compose.setContent { WeMeetTheme {
            RecordingHomeContent(Result.success(catalogue), true, {}, {}, {}, {}, {},
                importAction = { modifier -> RecordingUploadAction(uploadRepository(), viewer, {}, modifier, tile = true) })
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(label(R.string.records_upload)).fetchSemanticsNodes().isNotEmpty() }
        shot("10-recording")
    }

    @Test fun recordingSectionStates() {
        var result by mutableStateOf<Result<List<RecordDto>>?>(Result.success(emptyList()))
        compose.setContent { WeMeetTheme { RecordingHomeContent(result, true, {}, {}, {}, {}, {}) } }
        shot("11-recording-empty")
        compose.runOnIdle { result = Result.failure(IllegalStateException("offline")) }
        shot("12-recording-error")
    }

    @Test fun recordLibrary() {
        compose.setContent { WeMeetTheme { RecordLibraryScreen(repository(), viewer, false, {}, {}, onOpenNavDrawer = {}, onSearchMeetingAi = {}) } }
        shot("20-records-list")
        compose.onNodeWithContentDescription(label(R.string.records_grid_view)).performClick()
        shot("21-records-grid")
    }

    @Test fun recordLibraryEmpty() {
        compose.setContent { WeMeetTheme { RecordLibraryScreen(MeetingRecordRepository(FakeApi(emptyList()), { viewer }), viewer, false, {}, {}, onOpenNavDrawer = {}, onSearchMeetingAi = {}) } }
        shot("22-records-empty")
    }

    @Test fun minutesLibrary() {
        compose.setContent { WeMeetTheme { RecordLibraryScreen(repository(), viewer, true, {}, {}, onOpenNavDrawer = {}, onSearchMeetingAi = {}) } }
        shot("30-minutes-list")
        compose.onNodeWithContentDescription(label(R.string.records_grid_view)).performClick()
        shot("31-minutes-grid")
    }

    @Test fun recordDetailSummary() {
        compose.setContent { WeMeetTheme { RecordDetailScreen(repository(), viewer, uuid(1), {}) } }
        compose.onNodeWithText(label(R.string.records_minutes)).performClick()
        shot("40-record-summary")
    }

    @Test fun recordDetailOriginals() {
        compose.setContent { WeMeetTheme { RecordDetailScreen(repository(), viewer, uuid(1), {}) } }
        shot("50-record-originals")
    }

    /** 还没有纪要时空态自带「刷新」,底栏不该再出现一个同名动作。 */
    @Test fun recordDetailWithoutSummary() {
        compose.setContent { WeMeetTheme { RecordDetailScreen(MeetingRecordRepository(FakeApi(catalogue, emptyList()), { viewer }), viewer, uuid(1), {}) } }
        compose.onNodeWithText(label(R.string.records_minutes)).performClick()
        shot("41-record-summary-empty")
    }

    @Test fun recordDetailInfo() {
        compose.setContent { WeMeetTheme { RecordDetailScreen(repository(), viewer, uuid(1), {}) } }
        compose.onNodeWithText(label(R.string.records_info)).performClick()
        shot("60-record-info")
    }

    /** 说话人 tab 只对本地录音/导入件出现(线上会议没有独立说话人端点)。 */
    @Test fun recordDetailSpeakers() {
        compose.setContent { WeMeetTheme { RecordDetailScreen(repository(), viewer, uuid(2), {}) } }
        compose.onNodeWithText(label(R.string.records_speakers)).performClick()
        shot("61-record-speakers")
    }

    /** 「生成信息」展开后要交代覆盖范围:阶段 / 已观察到的时间点 / 送达 · 覆盖度 / 识别。 */
    @Test fun recordDetailSourceInfo() {
        compose.setContent { WeMeetTheme { RecordDetailScreen(repository(), viewer, uuid(1), {}) } }
        compose.onNodeWithText(label(R.string.records_minutes)).performClick()
        compose.onNodeWithText(label(R.string.minutes_source_info)).performScrollTo().performClick()
        // 展开出来的几行在折叠按钮下方,滚到它们再拍。
        compose.onNodeWithText(label(R.string.records_coverage_unverified), substring = true).performScrollTo()
        shot("42-record-source-info")
    }
}
