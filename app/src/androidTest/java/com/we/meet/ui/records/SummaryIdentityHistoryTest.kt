package com.we.meet.ui.records

import android.graphics.Bitmap
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.we.meet.R
import com.we.meet.data.api.dto.*
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SummaryIdentityHistoryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ref = RecordReferenceDto("old-segment", 2, 1000, 2000)
    private fun version(updated: Boolean) = RecordSummaryVersionDto(
        id = "old-version", stage = "final", inputSnapshotId = "old-snapshot", inputRevision = 2,
        isCurrent = false, createdAt = "2026-10-11T00:00:00Z", deliveryStatus = "complete",
        content = RecordSummaryContentDto("旧姓名的历史纪要内容", listOf(RecordSummaryPointDto("旧姓名的决定", listOf(ref))), emptyList(), emptyList(), emptyList()),
        identityUpdated = updated,
    )

    @Test fun updatedIdentityKeepsOldContentAndCitationInLargeTextDarkView() {
        var selected: RecordReferenceDto? = null
        val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val localized = compose.activity.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                WeMeetTheme(darkTheme = true) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                            SummaryCard(version(true), originals = true) { selected = it }
                        }
                    }
                }
            }
        }
        val notice = localized.getString(R.string.records_identity_updated)
        compose.onNodeWithText(notice).assertIsDisplayed()
        compose.onNodeWithText("旧姓名的历史纪要内容").assertExists()
        val root = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null), "summary-identity-history-large-dark.png").outputStream().use {
            root.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText(localized.getString(R.string.records_source_at, "0:01")).performScrollTo().performClick()
        assertEquals(ref, selected)
    }

    @Test fun historicalVersionWithoutIdentityChangeHasNoIdentityNotice() {
        compose.setContent { WeMeetTheme { SummaryCard(version(false), originals = false) {} } }
        compose.onNodeWithText(compose.activity.getString(R.string.records_identity_updated)).assertDoesNotExist()
        compose.onNodeWithText("旧姓名的历史纪要内容").assertExists()
    }
}
