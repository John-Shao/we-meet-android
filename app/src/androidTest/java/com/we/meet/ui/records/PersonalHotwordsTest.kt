package com.we.meet.ui.records

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.R
import com.we.meet.data.api.RecordingUploadApi
import com.we.meet.data.repository.RecordingUploadRepository
import com.we.meet.ui.theme.WeMeetTheme
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class PersonalHotwordsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)
    private val writes = CopyOnWriteArrayList<String>()
    @Volatile private var code = 200
    private val viewer = mutableStateOf("owner")
    private val disabled = mutableStateOf(false)
    private var applied: String? = null
    private fun show(current: String = "Manual\nQwen") {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "PUT") writes += okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
            val data = if (viewer.value == "owner") """{"words":["Qwen","妙记"],"revision":1}""" else """{"words":[],"revision":0}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (request.method == "PUT") code else 200).message("fixture")
                .body(data.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://fixture.invalid/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
            .build().create(RecordingUploadApi::class.java)
        val repo = RecordingUploadRepository(api, currentViewer = { viewer.value })
        compose.setContent { WeMeetTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            PersonalHotwords(repo, viewer.value, current, disabled.value) { applied = it }
        } } }
        compose.onNodeWithText(label(R.string.personal_hotwords_title)).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(label(R.string.personal_hotwords_editor)).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun openingDoesNotWriteAndMergeIsExplicit() {
        show()
        assertTrue(writes.isEmpty()); assertNull(applied)
        compose.onNodeWithText(label(R.string.personal_hotwords_apply)).performScrollTo().performClick()
        assertEquals("Manual\nQwen\n妙记", applied)
        assertTrue(writes.isEmpty())
    }
    @Test fun conflictKeepsDraftWithoutChangingUpload() {
        show(); code = 409
        compose.onNodeWithText(label(R.string.personal_hotwords_editor)).performScrollTo().performTextReplacement("Draft")
        compose.onNodeWithText(label(R.string.personal_hotwords_save)).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(label(R.string.personal_hotwords_conflict)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Draft").assertExists()
        assertNull(applied); assertEquals(1, writes.size)
        assertTrue(writes.single().contains("\"expected_revision\":1"))
    }
    @Test fun oversizedMergeIsRejectedWithoutTruncation() {
        show((0..99).joinToString("\n"))
        compose.onNodeWithText(label(R.string.personal_hotwords_apply)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.personal_hotwords_limit)).assertExists()
        assertNull(applied)
    }
    @Test fun submittedUploadLocksLibraryAndAccountSwitchClearsIt() {
        show()
        compose.runOnIdle { disabled.value = true }
        compose.onNodeWithText(label(R.string.personal_hotwords_apply)).assertIsNotEnabled()
        compose.runOnIdle { disabled.value = false; viewer.value = "reader" }
        compose.onNodeWithText(label(R.string.personal_hotwords_editor)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.personal_hotwords_title)).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(label(R.string.personal_hotwords_editor)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.personal_hotwords_apply)).assertIsNotEnabled()
        assertNull(applied)
    }
}
