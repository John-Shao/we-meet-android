package com.we.meet.ui.records

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.globalAskStream
import com.we.meet.feature.im.ui.search.AskEvent
import com.we.meet.ui.room.RoomAiSheet
import com.we.meet.ui.theme.WeMeetTheme
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeetingAiUxTest {
    @get:Rule val compose = createComposeRule()

    @Test fun quickPromptOnlySubmitsAfterTap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prompt = context.getString(R.string.room_ai_prompt_decisions)
        val submitted = mutableListOf<String>()
        compose.setContent { WeMeetTheme { RoomAiSheet(emptyList(), false, { submitted += it }, {}, {}) } }
        assertTrue(submitted.isEmpty())
        compose.onNodeWithText(prompt).performClick()
        assertEquals(listOf(prompt), submitted)
    }

    @Test fun meetingSearchSendsScopeAndParsesRecordCitation() = runBlocking {
        var captured: JSONObject? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = JSONObject(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8())
            val body =
                "data: {\"type\":\"meta\",\"citations\":[{\"n\":1,\"kind\":\"meeting\",\"title\":\"Review\",\"record_id\":\"record-1\",\"summary_id\":null,\"reviewed\":true,\"ability\":\"read_summary\"}]}\n\n" +
                    "data: {\"type\":\"done\",\"citations_used\":[1]}\n\n"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
            val events = globalAskStream(client, "https://fixture.invalid", "budget", "meetings", "2026-09-01", "2026-09-15").toList()
            val request = checkNotNull(captured)
            assertEquals("meetings", request.getString("scope"))
            assertEquals("2026-09-01", request.getString("date_from"))
            assertEquals("2026-09-15", request.getString("date_to"))
            val citation = (events.first() as AskEvent.Meta).citations.single()
            assertEquals("record-1", citation.recordId)
            assertNull(citation.summaryId)
            assertTrue(citation.reviewed)
    }
}
