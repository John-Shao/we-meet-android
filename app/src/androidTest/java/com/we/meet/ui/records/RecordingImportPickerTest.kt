package com.we.meet.ui.records

import android.app.Activity
import android.content.Intent
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

class RecordingImportPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun importDirectlyOpensFilteredPickerAndCancelDoesNotSubmit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var launched: Intent? = null
        var uploads = 0
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                launched = contract.createIntent(context, input)
                dispatchResult(requestCode, Activity.RESULT_CANCELED, null)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val api = object : RecordingUploadApi {
            override suspend fun capabilities() = RecordingUploadCapabilities(true, 1024, listOf("wav", "mp4", "mov"))
            override suspend fun state(recordId: String): RecordingUploadState = error("Not requested")
            override suspend fun retry(recordId: String, body: RecordingUploadRetry): RecordingUploadState = error("Not requested")
            override suspend fun upload(key: RequestBody, audio: MultipartBody.Part, context: RequestBody, hotwords: RequestBody): RecordingUploadState {
                uploads++
                error("Cancel must not upload")
            }
        }
        val repository = RecordingUploadRepository(api) { "owner" }
        compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            WeMeetTheme { RecordingUploadAction(repository, "owner", { fail("Cancel must not navigate") }, tile = true) }
        } }
        val label = context.getString(R.string.records_upload)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label).performClick()
        compose.runOnIdle {
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, launched?.action)
            val types = launched?.getStringArrayExtra(Intent.EXTRA_MIME_TYPES).orEmpty().toSet()
            assertTrue(types.containsAll(listOf("audio/wav", "video/mp4", "video/quicktime")))
            assertFalse(types.contains("*/*"))
            assertEquals(0, uploads)
        }
        compose.onNodeWithText(context.getString(R.string.record_upload_title)).assertDoesNotExist()
    }
}
