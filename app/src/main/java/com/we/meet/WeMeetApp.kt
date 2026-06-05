package com.we.meet

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.we.meet.feature.assistant.AssistantDeps
import com.we.meet.data.api.ApiClient
import com.we.meet.data.auth.TokenStore
import com.we.meet.data.history.HistoryStore
import com.we.meet.data.repository.AuthRepository
import com.we.meet.data.repository.MeetingDetailRepository
import com.we.meet.data.repository.RoomAiRepository
import com.we.meet.data.repository.ProfileRepository
import com.we.meet.data.repository.QrLoginRepository
import com.we.meet.data.repository.RoomRepository
import com.we.meet.data.settings.SettingsStore
import com.we.meet.overlay.ScreenShareOverlay
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient

/**
 * Application class that owns the shared singletons for the app.
 *
 * MVP intentionally avoids a DI framework — the surface is small enough that
 * a hand-rolled service locator on [WeMeetApp] keeps the code obvious.
 * If the app grows beyond a few screens, swap this for Hilt without churning
 * the call sites: every screen reads dependencies from a single property.
 */
class WeMeetApp : Application(), ImageLoaderFactory, AssistantDeps {

    lateinit var tokenStore: TokenStore
        private set
    lateinit var apiClient: ApiClient
        private set
    lateinit var authRepository: AuthRepository
        private set
    lateinit var profileRepository: ProfileRepository
        private set
    lateinit var roomRepository: RoomRepository
        private set
    lateinit var meetingDetailRepository: MeetingDetailRepository
        private set
    lateinit var roomAiRepository: RoomAiRepository
        private set
    lateinit var qrLoginRepository: QrLoginRepository
        private set
    lateinit var historyStore: HistoryStore
        private set
    lateinit var settingsStore: SettingsStore
        private set

    /**
     * Holds a meeting slug pulled from an incoming App Links / deep-link
     * intent until AppNav can route the user to JoinPreview with it
     * prefilled. MainActivity writes; AppNav collects exactly once and
     * resets to null. Lives on the Application so it survives the
     * pre-NavHost composition gap on cold-start deep launches.
     */
    val pendingJoinSlug: MutableStateFlow<String?> = MutableStateFlow(null)

    override fun onCreate() {
        super.onCreate()
        tokenStore = TokenStore(this)
        apiClient = ApiClient(tokenStore)
        authRepository = AuthRepository(apiClient.authApi, tokenStore, apiClient.okHttp)
        profileRepository = ProfileRepository(
            apiClient.userApi,
            tokenStore,
            apiClient.okHttp,
            contentResolver,
        )
        roomRepository = RoomRepository(apiClient.roomApi)
        meetingDetailRepository = MeetingDetailRepository(apiClient.roomApi)
        roomAiRepository = RoomAiRepository(apiClient.okHttp)
        qrLoginRepository = QrLoginRepository(apiClient.qrLoginApi)
        historyStore = HistoryStore(this)
        settingsStore = SettingsStore(this)
        ScreenShareOverlay.init(this)
        // PostHog: no-op when WE_MEET_POSTHOG_KEY is blank (default).
        com.we.meet.analytics.Analytics.init(this)
    }

    // AssistantDeps — lets :feature-assistant reuse the host's authenticated
    // networking instead of owning its own auth/login. Read after onCreate().
    override val authedOkHttp: OkHttpClient
        get() = apiClient.okHttp
    override val baseUrl: String
        get() = BuildConfig.WE_MEET_BASE_URL

    /**
     * Custom Coil [ImageLoader] used by every [coil.compose.AsyncImage].
     *
     * `respectCacheHeaders = false` makes Coil ignore the server's
     * `Cache-Control` / `ETag` and always serve from the disk cache when an
     * entry exists, instead of revalidating over the network. This removes the
     * brief "loading" flash that otherwise appears the first time the Profile
     * screen opens after a cold start while online — the offline path already
     * renders instantly because it falls straight to disk cache.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .respectCacheHeaders(false)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(64L * 1024L * 1024L)
                    .build()
            }
            .build()
}
