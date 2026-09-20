package com.we.meet.ui.records

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.dto.RecordMediaDto
import com.we.meet.ui.theme.WeMeetTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Opt-in acceptance against naturally expired / newly issued production leases.
 * The runner receives only a loopback descriptor endpoint. A temporary host bridge
 * keeps signed URLs in memory; no credentials or URLs belong in args or reports.
 * Without an explicitly supplied bridge, this test does not contact production.
 */
@RunWith(AndroidJUnit4::class)
class ProductionMediaRenewalAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)
    private fun speed(value: Float) = context.getString(R.string.capture_playback_rate, value)

    @Test fun expiredProductionLeaseRecoversWithNewLeaseAndPreservesSpeedAndSeek() {
        val endpoint = InstrumentationRegistry.getArguments().getString("mediaLeaseDescriptor")
        assumeTrue("Requires an explicitly authorized temporary production lease bridge", endpoint != null)
        val uri = URI(requireNotNull(endpoint))
        require(uri.scheme == "http" && uri.host == "127.0.0.1") { "Descriptor must use the loopback bridge" }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        val descriptor = try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
        require(descriptor.getBoolean("naturally_expired")) { "Natural expiry must be verified before native acceptance" }
        val oldUrl = descriptor.getString("old_url")
        val freshUrl = descriptor.getString("fresh_url")
        require(URI(oldUrl).scheme == "https" && URI(freshUrl).scheme == "https")
        // Compare booleans so a failing assertion can never print signed URLs.
        assertTrue("Leases must differ", oldUrl != freshUrl)
        val media = mutableStateOf(RecordMediaDto(oldUrl, 3600, "video", "acceptance.mp4"))
        val positions = CopyOnWriteArrayList<Long>()
        val seek = mutableStateOf<CaptureAudioSeek?>(null)
        compose.setContent {
            WeMeetTheme {
                Surface {
                    UploadMediaPlayer(media.value, 4_000, seek.value,
                        onSeekConsumed = { seek.value = null }, onPosition = { positions += it })
                }
            }
        }
        compose.onNodeWithText(speed(1f)).performClick()
        compose.onNodeWithText(speed(1.5f)).performClick()
        compose.onNodeWithContentDescription(label(R.string.capture_playback_play)).performClick()
        compose.waitUntil(45_000) {
            compose.onAllNodesWithText(label(R.string.capture_playback_error)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { media.value = media.value.copy(url = freshUrl) }
        compose.onNodeWithText(label(com.we.meet.design.R.string.common_retry)).performClick()
        compose.waitUntil(30_000) { positions.any { it in 4_000..15_000 } }
        assertTrue("Recovery must start at the retained position, not from zero", positions.first() in 4_000..6_000)
        compose.onNodeWithText(speed(1.5f)).assertIsDisplayed()
        compose.runOnIdle { seek.value = CaptureAudioSeek(40_000) }
        // > 40,000 proves the real decoder advanced, rather than just the seek callback.
        compose.waitUntil(30_000) { positions.any { it > 40_000 } }
        compose.onNodeWithContentDescription(label(R.string.capture_playback_pause)).performClick()
        val paused = positions.last()
        compose.waitForIdle()
        assertEquals(paused, positions.last())
        compose.onNodeWithContentDescription(label(R.string.capture_playback_play)).assertIsDisplayed()
        compose.onNodeWithText(speed(1.5f)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.capture_playback_error)).assertDoesNotExist()
    }
}
