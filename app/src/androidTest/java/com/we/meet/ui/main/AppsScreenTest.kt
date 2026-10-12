package com.we.meet.ui.main

import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.ui.theme.WeMeetTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class AppsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun favoritesCanBeAddedOpenedAndRemovedThroughDedicatedPage() {
        val opened = mutableListOf<AppModule>()
        var fullScreen = false
        compose.setContent {
            var pinned by remember { mutableStateOf(emptyList<String>()) }
            WeMeetTheme(darkTheme = false) {
                AppsContent(pinned, 3, { opened += it }, {}, {}, {},
                    onFullScreenVisibilityChanged = { fullScreen = it }, onPinnedChange = { pinned = it })
            }
        }
        compose.onNodeWithTag("apps-add-favorite").performClick()
        compose.runOnIdle { assertTrue(fullScreen) }
        compose.onNodeWithTag("app-pin-calendar").performScrollTo().performClick()
        compose.onNodeWithTag("app-pin-calendar").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<AppModule>(), opened) }
        compose.onNodeWithContentDescription(context.getString(com.we.meet.design.R.string.cd_back)).performClick()
        compose.runOnIdle { assertTrue(!fullScreen) }
        compose.onNodeWithTag("app-favorite-calendar").performClick()
        compose.runOnIdle { assertEquals(listOf(AppModule.Calendar), opened) }
        compose.onNodeWithTag("apps-add-favorite").performClick()
        compose.onNodeWithTag("apps-manage-toggle").performClick()
        compose.onNodeWithTag("app-remove-calendar").performScrollTo().performClick()
        compose.onNodeWithTag("app-remove-calendar").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(com.we.meet.design.R.string.cd_back)).performClick()
        compose.onNodeWithContentDescription(context.getString(com.we.meet.design.R.string.cd_back)).performClick()
        compose.onNodeWithTag("app-favorite-calendar").assertDoesNotExist()
        compose.onNodeWithTag("apps-add-favorite").assertExists()
    }

    @Test fun gridCategoriesRecentHistoryAndSearchRemainFunctional() {
        val opened = mutableListOf<AppModule>()
        var approvals = 0
        var calls = 0
        var translations = 0
        compose.setContent {
            var recent by remember { mutableStateOf(emptyList<String>()) }
            WeMeetTheme(darkTheme = true) {
                AppsContent(listOf("approval"), 3, { opened += it }, { approvals++ }, { calls++ }, { translations++ },
                    recent = recent, onOpened = { recent = (listOf(it) + recent).distinct() }, onPinnedChange = {})
            }
        }
        compose.onNodeWithTag("app-favorite-approval").performClick()
        compose.onNodeWithTag("app-catalog-calendar").performScrollTo().performClick()
        compose.onNodeWithTag("app-catalog-tasks").performScrollTo().performClick()
        compose.onNodeWithTag("app-catalog-contacts").performScrollTo().performClick()
        compose.onNodeWithTag("app-catalog-approval").performScrollTo().performClick()
        compose.onNodeWithTag("apps-category-4").performScrollTo().performClick()
        compose.onNodeWithTag("app-catalog-ai").assertDoesNotExist()
        compose.onNodeWithTag("app-catalog-calendar").assertDoesNotExist()
        compose.onNodeWithTag("app-catalog-ai-call").performScrollTo().performClick()
        compose.onNodeWithTag("app-catalog-bilingual").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(AppModule.Calendar, AppModule.Tasks, AppModule.Contacts), opened)
            assertEquals(2, approvals)
            assertEquals(1, calls)
            assertEquals(1, translations)
        }
        compose.onNodeWithTag("apps-category-2").performScrollTo().performClick()
        compose.onNodeWithTag("app-catalog-calendar").assertExists()
        compose.onNodeWithTag("app-catalog-approval").assertDoesNotExist()
        compose.onNodeWithTag("apps-category-1").performScrollTo().performClick()
        compose.onNodeWithTag("app-catalog-ai-call").assertExists()
        compose.onNodeWithTag("app-catalog-bilingual").assertExists()
        compose.onNodeWithTag("apps-add-favorite").performScrollTo().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.apps_search)).performClick()
        compose.onNodeWithTag("apps-search-field").performTextInput(context.getString(R.string.tab_calendar))
        compose.onNodeWithTag("app-pin-calendar").assertExists()
        compose.onNodeWithTag("app-pin-tasks").assertDoesNotExist()
    }

    @Test fun aiToolsCanBePinnedAndOpenedIndependently() {
        var calls = 0
        var translations = 0
        compose.setContent {
            var pinned by remember { mutableStateOf(emptyList<String>()) }
            WeMeetTheme(darkTheme = false) {
                AppsContent(pinned, 0, {}, {}, { calls++ }, { translations++ }, onPinnedChange = { pinned = it })
            }
        }
        compose.onNodeWithTag("apps-category-4").performScrollTo().performClick()
        screenshot("apps-ai-direct-light.png")
        compose.onNodeWithTag("apps-add-favorite").performScrollTo().performClick()
        compose.onNodeWithTag("apps-add-category-4").performScrollTo().performClick()
        compose.onNodeWithTag("app-pin-ai-call").performScrollTo().performClick()
        compose.onNodeWithTag("app-pin-bilingual").performScrollTo().performClick()
        compose.onNodeWithContentDescription(context.getString(com.we.meet.design.R.string.cd_back)).performClick()
        compose.onNodeWithTag("app-favorite-ai-call").performScrollTo().performClick()
        compose.onNodeWithTag("app-favorite-bilingual").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, calls); assertEquals(1, translations) }
        compose.onNodeWithTag("apps-add-favorite").performScrollTo().performClick()
        compose.onNodeWithTag("apps-manage-toggle").performClick()
        compose.onNodeWithTag("app-remove-ai-call").performScrollTo().performClick()
        compose.onNodeWithTag("app-remove-bilingual").assertExists()
        compose.onNodeWithContentDescription(context.getString(com.we.meet.design.R.string.cd_back)).performClick()
        compose.onNodeWithContentDescription(context.getString(com.we.meet.design.R.string.cd_back)).performClick()
        compose.onNodeWithTag("app-favorite-ai-call").assertDoesNotExist()
        compose.onNodeWithTag("app-favorite-bilingual").assertExists()
    }

    @Test fun lightAndDarkGridAndAddFavoritesScreenshots() {
        var dark by mutableStateOf(false)
        val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        })
        compose.setContent {
            var pinned by remember { mutableStateOf(listOf("calendar", "tasks", "approval")) }
            CompositionLocalProvider(LocalContext provides english, LocalConfiguration provides english.resources.configuration) {
                WeMeetTheme(darkTheme = dark) {
                    AppsContent(pinned, 3, {}, {}, {}, {}, onPinnedChange = { pinned = it })
                }
            }
        }
        compose.onNodeWithTag("apps-add-favorite").assertTextContains("Add")
        compose.onNodeWithTag("app-catalog-bilingual").performScrollTo().assertTextContains("Translate")
        compose.onNodeWithTag("apps-add-favorite").performScrollTo()
        screenshot("apps-grid-light-verified.png")
        compose.onNodeWithTag("apps-add-favorite").performClick()
        compose.onNodeWithText("Add favorites").assertExists()
        compose.onNodeWithText("Bilingual translation").performScrollTo().assertExists()
        screenshot("apps-add-light-verified.png")
        compose.onNodeWithContentDescription(english.getString(com.we.meet.design.R.string.cd_back)).performClick()
        compose.runOnIdle { dark = true }
        screenshot("apps-grid-dark-verified.png")
        compose.onNodeWithTag("apps-add-favorite").performClick()
        screenshot("apps-add-dark-verified.png")
    }

    @Test fun favoritesRetainAdditionAndDragOrderAfterReopening() {
        val preferences = context.getSharedPreferences("favorites-order-ui-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val store = AppFavoritesStore(preferences, "viewer")
        store.save(emptyList())
        var generation by mutableIntStateOf(0)
        compose.setContent {
            key(generation) {
                var pinned by remember { mutableStateOf(store.read()) }
                WeMeetTheme(darkTheme = false) {
                    AppsContent(pinned, 0, {}, {}, {}, {}, onPinnedChange = { pinned = it; store.save(it) })
                }
            }
        }
        compose.onNodeWithTag("apps-add-favorite").performClick()
        listOf("bilingual", "calendar", "approval").forEach {
            compose.onNodeWithTag("app-pin-$it").performScrollTo().performClick()
        }
        compose.runOnIdle { assertEquals(listOf("bilingual", "calendar", "approval"), store.read()) }
        compose.onNodeWithTag("apps-manage-toggle").performClick()
        screenshot("apps-manage-order.png")
        dragFavorite("bilingual", "approval")
        compose.runOnIdle { assertEquals(listOf("calendar", "approval", "bilingual"), store.read()) }
        dragFavorite("bilingual", "calendar")
        compose.runOnIdle { assertEquals(listOf("bilingual", "calendar", "approval"), store.read()) }
        compose.onNodeWithTag("app-remove-calendar").performClick()
        compose.onNodeWithTag("apps-manage-toggle").performClick()
        compose.onNodeWithTag("app-pin-calendar").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("bilingual", "approval", "calendar"), store.read()); generation++ }
        val first = compose.onNodeWithTag("app-favorite-bilingual").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("app-favorite-approval").fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithTag("app-favorite-calendar").fetchSemanticsNode().boundsInRoot
        assertTrue(first.left < second.left && second.left < third.left)
        preferences.edit().clear().commit()
    }

    @Test fun favoriteStorageMigratesLegacyOrderAndSeparatesAccounts() {
        val preferences = context.getSharedPreferences("favorites-order-migration-test", Context.MODE_PRIVATE)
        preferences.edit().clear().putStringSet("pinned:alice", setOf("ai", "approval", "calendar")).commit()
        val alice = AppFavoritesStore(preferences, "alice")
        val bob = AppFavoritesStore(preferences, "bob")
        assertEquals(listOf("calendar", "approval", "ai-call", "bilingual"), alice.read())
        assertTrue(!preferences.contains("pinned:alice"))
        alice.save(listOf("bilingual", "calendar", "ai-call", "approval"))
        assertEquals(listOf("bilingual", "calendar", "ai-call", "approval"), AppFavoritesStore(preferences, "alice").read())
        assertEquals(listOf("calendar", "tasks", "approval"), bob.read())
        alice.save(emptyList())
        assertEquals(emptyList<String>(), AppFavoritesStore(preferences, "alice").read())
        preferences.edit().clear().commit()
    }

    @Test fun draggingNearEdgeScrollsToOffscreenFavorites() {
        val original = listOf("calendar", "tasks", "contacts", "approval", "ai-call", "bilingual")
        var order by mutableStateOf(original)
        compose.setContent {
            WeMeetTheme(darkTheme = false) {
                Column(Modifier.height(260.dp)) {
                    ReorderFavoritesList(order.map { AppEntry(it, R.string.tab_calendar, R.string.apps_calendar_description, Icons.Filled.Apps) {} }) { order = it }
                }
            }
        }
        val source = compose.onNodeWithTag("app-reorder-calendar").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("apps-add-scroll").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("apps-add-scroll").performTouchInput {
            down(Offset(source.center.x - list.left, source.center.y - list.top))
            advanceEventTime(700)
        }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("apps-add-scroll").performTouchInput { moveTo(Offset(list.width / 2, list.height - 2), delayMillis = 100) }
        // Allow Android layout passes between frames as newly revealed rows are reordered.
        repeat(50) {
            compose.mainClock.advanceTimeBy(50)
            compose.waitForIdle()
        }
        compose.onNodeWithTag("apps-add-scroll").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(original.drop(1) + "calendar", order) }
    }

    private fun dragFavorite(from: String, to: String) {
        val source = compose.onNodeWithTag("app-reorder-$from").fetchSemanticsNode().boundsInRoot
        val target = compose.onNodeWithTag("app-reorder-$to").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("apps-add-scroll").fetchSemanticsNode().boundsInRoot
        val start = Offset(source.center.x - list.left, source.center.y - list.top)
        val end = Offset(target.center.x - list.left, target.center.y - list.top)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("apps-add-scroll").performTouchInput {
            down(start)
            advanceEventTime(700)
        }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("apps-add-scroll").performTouchInput { moveTo(end, delayMillis = 100) }
        compose.mainClock.advanceTimeBy(150)
        compose.onNodeWithTag("apps-add-scroll").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/WeMeetNavigation")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
