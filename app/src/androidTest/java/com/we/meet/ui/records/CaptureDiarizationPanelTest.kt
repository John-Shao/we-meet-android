package com.we.meet.ui.records

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.R
import com.we.meet.data.api.CaptureTranscriptionApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.auth.PrivateLogin
import com.we.meet.data.repository.CaptureDiarizationRepository
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** Actual Compose, Retrofit, session repository and encrypted intent store. No external calls. */
@RunWith(AndroidJUnit4::class)
class CaptureDiarizationPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var resources = context
    private fun text(id: Int) = resources.getString(id)
    private class Fixture {
        val owner=UUID.randomUUID().toString(); val capture=UUID.randomUUID().toString()
        val asr=UUID.randomUUID().toString(); val job=UUID.randomUUID().toString()
        @Volatile var login="fixture-original-login"
        @Volatile var revision=1
        @Volatile var status: String? = null
        @Volatile var available=true
        @Volatile var loseFirst=false
        @Volatile var reject=false
        @Volatile var denyRead=false
        val changed=AtomicInteger()
        val paid=CopyOnWriteArrayList<Pair<String,String>>()
        val requests=CopyOnWriteArrayList<String>()
        val moshi=Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        private fun job()=CaptureDiarizationJobDto(job,1,status ?: "queued",asr,1,if(status == "succeeded") 2 else 0)
        private val http=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            assertEquals("fixture.invalid",request.url.host)
            assertEquals(owner,request.header("X-Voiceprint-Owner"))
            assertEquals("fixture-original-login",request.tag(PrivateLogin::class.java)?.session)
            assertEquals("no-store",request.header("Cache-Control"))
            requests += request.url.encodedPath
            var code=200
            val value: Any = when {
                request.method == "GET" -> {
                    if(denyRead) code=403
                    CaptureDiarizationStateDto(available,available && status == null,revision,asr,
                        job.takeIf { status == "succeeded" },status?.let { listOf(job()) } ?: emptyList())
                }
                request.url.encodedPath.endsWith("/cancel/") -> { status="canceled"; CaptureDiarizationCanceledDto(job()) }
                else -> {
                    val body=Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    val key=requireNotNull(request.header("Idempotency-Key"))
                    paid += key to body
                    if(reject) { code=409; mapOf("code" to "fixture_conflict") }
                    else {
                        status="queued"
                        if(loseFirst && paid.size == 1) code=502
                        CaptureDiarizationCreatedDto(job(),false,CaptureDiarizationReceiptDto(key,mapOf("capture_id" to capture)))
                    }
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .body(moshi.adapter(Any::class.java).toJson(value).toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repository=CaptureDiarizationRepository(Retrofit.Builder().baseUrl("https://fixture.invalid/").client(http)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(CaptureTranscriptionApi::class.java),{owner},{login})
    }
    private fun waitText(id: Int) { compose.waitUntil(30000) { compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty() } }
    private fun click(id: Int) {
        waitText(id)
        compose.waitUntil(30000) { compose.onAllNodes(hasText(text(id)) and isEnabled() and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(id)).performScrollTo().performClick()
    }
    private fun show(fixture: Fixture, editing: State<Boolean> = mutableStateOf(false), chinese: Boolean=false) {
        val config=Configuration(context.resources.configuration).apply { if(chinese) setLocale(Locale.SIMPLIFIED_CHINESE) }
        resources=context.createConfigurationContext(config)
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalContext provides resources,LocalConfiguration provides config,
                LocalDensity provides Density(density.density,if(chinese) 1.5f else density.fontScale)) {
                WeMeetTheme(darkTheme=chinese) { Surface { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    CaptureDiarizationPanel(fixture.repository,fixture.owner,fixture.capture,editing.value) { fixture.changed.incrementAndGet() }
                } } }
            }
        }
        waitText(R.string.capture_diarization_title)
        compose.waitUntil(30000) { compose.onAllNodes(hasText(text(R.string.capture_diarization_refresh)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun explicitConfirmationStartsExactlyOnePaidRequest() {
        val fixture=Fixture(); show(fixture)
        compose.onNodeWithText(text(R.string.capture_diarization_start)).assertIsNotEnabled()
        assertTrue(fixture.paid.isEmpty())
        click(R.string.capture_diarization_accept); click(R.string.capture_diarization_start)
        waitText(R.string.capture_diarization_cancel)
        assertEquals(1,fixture.paid.size)
        assertEquals("""{"expected_revision":1}""",fixture.paid.single().second)
        compose.onNodeWithText(text(R.string.capture_diarization_retry)).assertIsNotEnabled()
    }
    @Test fun backgroundRestoresEncryptedNonceWithoutAutoReplayAndOriginalBodyWins() {
        val fixture=Fixture().apply { loseFirst=true }; show(fixture)
        click(R.string.capture_diarization_accept); click(R.string.capture_diarization_start)
        waitText(R.string.capture_diarization_recover)
        val first=fixture.paid.single()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        fixture.revision=9
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitText(R.string.capture_diarization_recover)
        assertEquals(1,fixture.paid.size)
        click(R.string.capture_diarization_recover)
        compose.waitUntil(30000) { fixture.paid.size == 2 && compose.onAllNodesWithText(text(R.string.capture_diarization_recover)).fetchSemanticsNodes().isEmpty() }
        assertEquals(first,fixture.paid[1])
        compose.onNodeWithText(text(R.string.capture_diarization_unknown)).assertDoesNotExist()
    }
    @Test fun cancellationIsAvailableWithCreationPaused() {
        val fixture=Fixture().apply { status="running"; available=false }; show(fixture)
        click(R.string.capture_diarization_cancel)
        compose.waitUntil(30000) {
            compose.onAllNodesWithText(text(R.string.capture_diarization_canceled), substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(fixture.paid.isEmpty())
    }
    @Test fun definitiveConflictRequiresANewConfirmation() {
        val fixture=Fixture().apply { reject=true }; show(fixture)
        click(R.string.capture_diarization_accept); click(R.string.capture_diarization_start)
        waitText(R.string.capture_diarization_conflict)
        compose.onNodeWithText(text(R.string.capture_diarization_start)).assertIsNotEnabled()
        assertEquals(1,fixture.paid.size)
    }
    @Test fun editingAndFailedPrivateReadBlockWritesAndHideHistory() {
        val editing=mutableStateOf(true)
        val fixture=Fixture(); show(fixture,editing)
        compose.onNodeWithText(text(R.string.capture_diarization_accept)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.capture_diarization_start)).assertIsNotEnabled()
        compose.runOnIdle { editing.value=false }
        click(R.string.capture_diarization_accept)
        fixture.denyRead=true
        click(R.string.capture_diarization_refresh)
        waitText(R.string.capture_diarization_denied)
        compose.onNodeWithText(text(R.string.capture_diarization_start)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.capture_diarization_history)).assertDoesNotExist()
        assertTrue(fixture.paid.isEmpty())
    }
    @Test fun sameAccountNewLoginClosesPrivateState() {
        val fixture=Fixture(); show(fixture)
        fixture.login="new-synthetic-login"
        compose.waitUntil(5000) { compose.onAllNodesWithText(text(R.string.capture_diarization_title)).fetchSemanticsNodes().isEmpty() }
        assertTrue(fixture.paid.isEmpty())
    }
    @Test fun chineseDarkLargeTextKeepsConfirmationAndActionsReadable() {
        val fixture=Fixture(); show(fixture,chinese=true)
        compose.onNodeWithText(text(R.string.capture_diarization_accept)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.capture_diarization_start)).performScrollTo().assertIsDisplayed()
        File(context.getExternalFilesDir(null),"capture-diarization-zh-dark-large.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
        assertTrue(fixture.paid.isEmpty())
    }
}
