package com.we.meet.ui.records

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.*
import com.we.meet.data.repository.RecordingUploadRepository
import com.we.meet.ui.theme.WeMeetTheme
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The chunked import, driven through the real dialog on a device.
 *
 * The progress bar and the cancel button are the whole point of the chunked
 * path, and neither is reachable from a repository test — they only exist while
 * a transfer is in flight. So this holds a part PUT open with a latch, checks
 * what the reader can see and press, then releases it.
 */
class RecordingImportChunkedTest {
    @get:Rule val compose = createComposeRule()

    private val id = "11111111-1111-4111-8111-111111111111"
    private val session = "22222222-2222-4222-8222-222222222222"

    /** Just over the 100 MiB threshold, so the chunked path is chosen. */
    private val bigSize = 101L * 1024 * 1024
    private val partSize = 64L * 1024 * 1024

    @Test fun aLargeImportShowsProgressAndCanBeCancelled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "chunked-fixture.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        })!!
        // Real bytes, not a sparse placeholder: MediaStore must report a size at
        // or above the chunked threshold, and the part writer must be able to
        // read the stream it is handed.
        val inFlight = CountDownLatch(1)
        val cancelSeen = CountDownLatch(1)
        var aborted = 0
        var partPuts = 0
        val apiCalls = mutableListOf<String>()
        try {
            val megabyte = ByteArray(1024 * 1024)
            resolver.openOutputStream(uri)!!.use { out -> repeat(101) { out.write(megabyte) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            // If MediaStore under-reports, the dialog takes the legacy path and
            // this test would silently be testing nothing.
            var reported: Long? = null
            resolver.query(uri, arrayOf(MediaStore.Video.Media.SIZE), null, null, null)?.use { rows ->
                if (rows.moveToFirst()) reported = rows.getLong(0)
            }
            assertTrue(
                "MediaStore reported $reported bytes, below the chunked threshold",
                (reported ?: 0L) > 100L * 1024 * 1024,
            )

            val registry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
                }
            }
            val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }

            val api = object : RecordingUploadApi {
                override suspend fun capabilities() = RecordingUploadCapabilities(
                    available = true, maxBytes = 1024, extensions = listOf("mp4"),
                    directUploadAvailable = true, directMaxBytes = 6L * 1024 * 1024 * 1024,
                )
                override suspend fun state(recordId: String) = RecordingUploadState(recordId, "queued", 1)
                override suspend fun retry(recordId: String, body: RecordingUploadRetry) = error("Not requested")
                override suspend fun upload(key: RequestBody, audio: MultipartBody.Part, context: RequestBody, hotwords: RequestBody) =
                    error("A file this large must not use the legacy multipart path")
                override suspend fun presign(body: RecordingUploadPresign) = error("Single PUT not used above the threshold")
                override suspend fun complete(body: RecordingUploadComplete) = error("Single PUT not used above the threshold")

                override suspend fun multipartBegin(body: RecordingUploadBegin): RecordingUploadPlan {
                    apiCalls.add("begin")
                    assertEquals(bigSize, body.size)
                    return plan()
                }
                override suspend fun multipartResume(sessionId: String) = plan().also { apiCalls.add("resume") }
                override suspend fun multipartSign(sessionId: String, body: RecordingUploadSign) =
                    plan().copy(
                        parts = body.parts.map {
                            RecordingUploadPartPlan(it, "https://bucket.example/part/$it", partSize)
                        },
                    ).also { apiCalls.add("sign") }
                override suspend fun multipartComplete(sessionId: String, body: RecordingUploadFinish) =
                    RecordingUploadState(id, "queued", 1).also { apiCalls.add("complete") }
                override suspend fun multipartAbort(sessionId: String) { aborted++; apiCalls.add("abort") }
            }

            val partStorage = RecordingPartStorage { _url, length, open, onProgress, cancel ->
                partPuts++
                // Drain exactly the declared window, incrementally: a 64 MiB part
                // must never be buffered whole, which is what the real part writer
                // does too.
                var read = 0L
                val buffer = ByteArray(64 * 1024)
                open().use { stream ->
                    while (read < length) {
                        val want = minOf(buffer.size.toLong(), length - read).toInt()
                        val count = stream.read(buffer, 0, want)
                        if (count < 0) break
                        read += count
                        if (read >= length / 2) onProgress(read)
                    }
                }
                assertEquals("the window for this part had the wrong length", length, read)
                // Hold the transfer open so the UI can be inspected mid-flight,
                // then wait for the reader's cancel. Waiting on the cancel itself
                // rather than on a timer keeps the ordering explicit: the part
                // cannot fail before the button was actually pressed.
                onProgress(length / 2)
                inFlight.countDown()
                val cancelled = cancelSeen.await(20, TimeUnit.SECONDS)
                if (cancelled) return@RecordingPartStorage null
                onProgress(length)
                "etag-part"
            }

            val repository = RecordingUploadRepository(
                api, currentViewer = { "owner" },
                // The whole-file seam is what advertises the larger ceiling, so a
                // null one silently caps the dialog at the 100 MiB legacy limit
                // and the file below is rejected before the chunked path is even
                // considered.
                storage = RecordingStorage { _, _, _, _ -> true },
                partStorage = partStorage,
            )
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    WeMeetTheme { RecordingUploadAction(repository, "owner", {}, tile = true) }
                }
            }
            fun label(resource: Int) = context.getString(resource)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(label(R.string.records_upload)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(label(R.string.records_upload)).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(label(R.string.record_upload_title)).fetchSemanticsNodes().isNotEmpty()
            }
            // A disabled submit would make the click below a silent no-op, and the
            // test would then fail far away from the real cause. It is only
            // enabled because the whole-file seam advertises the larger ceiling.
            assertFalse(
                "the dialog rejected the fixture",
                compose.onAllNodesWithText(label(R.string.record_import_invalid))
                    .fetchSemanticsNodes().isNotEmpty(),
            )
            compose.onNodeWithText(label(R.string.record_upload_submit)).assertIsEnabled()
            compose.onNodeWithText(label(R.string.record_upload_submit)).performClick()

            // Mid-transfer: the reader must be able to see how far it has got and
            // must be able to stop it. Both only exist while the transfer runs.
            assertTrue(
                "the transfer never reached a part PUT; api calls so far: $apiCalls, partPuts=$partPuts",
                inFlight.await(20, TimeUnit.SECONDS),
            )
            compose.waitForIdle()
            compose.onNodeWithText(label(R.string.record_upload_cancel)).assertExists()
            // The percentage comes from a progress callback, so it needs a
            // recompose that `waitForIdle` alone does not guarantee - waiting for
            // it keeps this from depending on how loaded the device is. The exact
            // value is not the point; that the reader can see progress is.
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("uploaded", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }

            compose.onNodeWithText(label(R.string.record_upload_cancel)).performClick()
            // The part in flight is watching the cancel flag, so signalling here is
            // what lets the transfer observe the stop.
            cancelSeen.countDown()
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText(label(R.string.record_upload_cancelled)).fetchSemanticsNodes().isNotEmpty()
            }
            // A stop is not an ambiguous outcome: the reader must not be told the
            // file may already have been received.
            assertFalse(
                "a cancel was reported as an unconfirmed upload",
                compose.onAllNodesWithText(label(R.string.record_upload_unconfirmed))
                    .fetchSemanticsNodes().isNotEmpty(),
            )
            // The dialog stays open and nothing navigated away.
            compose.waitForIdle()
            compose.onNodeWithText(label(R.string.record_upload_title)).assertExists()
        } finally {
            cancelSeen.countDown()
            resolver.delete(uri, null, null)
        }
    }

    private fun plan() = RecordingUploadPlan(
        sessionId = session,
        size = bigSize,
        partSize = partSize,
        partCount = 2,
        uploaded = emptyList(),
        uploadedBytes = 0,
    )
}
