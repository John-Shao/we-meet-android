package com.we.meet

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.we.meet.core.directory.DirectoryDeps
import com.we.meet.core.directory.data.DirectoryRepository
import com.we.meet.core.directory.data.StarredContacts
import com.we.meet.core.directory.net.DirectoryNetwork
import com.we.meet.feature.assistant.AssistantDeps
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.call.CallHost
import com.we.meet.feature.im.call.CallRoom
import com.we.meet.service.ConferenceForegroundService
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
import com.we.meet.push.PushTokenUploader
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
class WeMeetApp : Application(), ImageLoaderFactory, AssistantDeps, ImDeps, DirectoryDeps, CallHost {

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
    lateinit var directoryRepository: DirectoryRepository
        private set

    /**
     * Holds a meeting slug pulled from an incoming App Links / deep-link
     * intent until AppNav can route the user to JoinPreview with it
     * prefilled. MainActivity writes; AppNav collects exactly once and
     * resets to null. Lives on the Application so it survives the
     * pre-NavHost composition gap on cold-start deep launches.
     */
    val pendingJoinSlug: MutableStateFlow<String?> = MutableStateFlow(null)

    /**
     * Same pattern as [pendingJoinSlug] but for IM push deep links:
     * `wemeet://im?cid=...` (tapped offline-push notification). MainActivity
     * writes the conversation id; AppNav collects once, routes into the chat,
     * and resets to null.
     */
    val pendingChatCid: MutableStateFlow<String?> = MutableStateFlow(null)

    /**
     * True while MainActivity is started (visible). Set from its
     * onStart/onStop. The call-push receiver uses this to decide whether a
     * full-screen-intent notification is needed: in the foreground the seeded
     * [com.we.meet.feature.im.call.CallController] already flips the in-app
     * incoming-call screen, and a second ringing notification would double up.
     */
    @Volatile
    var isForeground: Boolean = false

    override fun onCreate() {
        super.onCreate()
        tokenStore = TokenStore(this)
        apiClient = ApiClient(tokenStore)
        authRepository = AuthRepository(
            apiClient.authApi,
            tokenStore,
            apiClient.okHttp,
            apiClient.keycloakOidc,
        )
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
        directoryRepository = DirectoryRepository(DirectoryNetwork.directoryApi(this))
        // 星标联系人:进程级单例,通讯录与会话列表共享同一份状态(见 StarredContacts)。
        StarredContacts.init(directoryRepository)
        ScreenShareOverlay.init(this)
        // PostHog: no-op when WE_MEET_POSTHOG_KEY is blank (default).
        com.we.meet.analytics.Analytics.init(this)
        initGetuiPush()
    }

    /**
     * Getui (个推) offline-push bootstrap. Wrapped in try/catch so a bad SDK
     * state (missing meta-data, OEM quirks, blocked push process) degrades to
     * "no offline push" instead of crashing app startup.
     *
     * `registerPushIntentService` is deprecated in gtsdk 3.3.15 but kept on
     * purpose: the manifest declares WeMeetGtIntentService as a plain <service>
     * with no Getui meta-data, so this runtime call is what actually tells the
     * SDK where to deliver cid/message callbacks — it is load-bearing, not
     * redundant. Migrating to manifest-only wiring must be verified with a live
     * device push (call-notification path is mid-审核) before it can be trusted,
     * so we @Suppress the warning rather than risk silently breaking push.
     */
    @Suppress("DEPRECATION")
    private fun initGetuiPush() {
        PushTokenUploader.init(this)
        com.we.meet.push.DeviceTimezoneReporter.init(this)
        try {
            com.igexin.sdk.PushManager.getInstance().initialize(this)
            com.igexin.sdk.PushManager.getInstance()
                .registerPushIntentService(this, com.we.meet.push.WeMeetGtIntentService::class.java)
        } catch (t: Throwable) {
            android.util.Log.w("WeMeetApp", "Getui push init failed", t)
        }
        // If a cid from a previous run is already on disk and the user is
        // logged in, this re-registers immediately (covers app-update /
        // token-cleared-server-side cases). No-ops otherwise.
        PushTokenUploader.uploadIfPossible()
        // 免打扰时段按 User.timezone 的墙上钟判断,所以设备时区必须上报,否则
        // 服务端拿 UTC 默认值解释,静默时段整体错位(见 DeviceTimezoneReporter)。
        com.we.meet.push.DeviceTimezoneReporter.reportIfNeeded()
    }

    // AssistantDeps / ImDeps — lets :feature-assistant and :feature-im reuse the
    // host's authenticated networking instead of owning their own auth/login.
    // `authedOkHttp` and `baseUrl` are shared by both contracts.
    override val authedOkHttp: OkHttpClient
        get() = apiClient.okHttp
    override val baseUrl: String
        get() = BuildConfig.WE_MEET_BASE_URL

    /** ImDeps — jusi-light-im server origin (no trailing slash). */
    override val jusiImBaseUrl: String
        get() = BuildConfig.JUSI_IM_BASE_URL

    // ---- CallHost (P1 一对一通话) — room ops for feature-im's CallController ----

    private val callDisplayName: String
        get() = tokenStore.nickname?.takeIf { it.isNotBlank() } ?: tokenStore.phone
            ?: getString(R.string.default_display_name)

    override suspend fun createCallRoom(name: String): CallRoom {
        val room = roomRepository.createRoom(callDisplayName, name).getOrThrow()
        val lk = room.livekit ?: error("create-room response missing livekit")
        return CallRoom(
            roomId = room.id,
            slug = room.slug ?: room.id,
            roomName = room.name ?: name,
            livekitUrl = lk.url,
            livekitToken = lk.token,
            createdAtMs = parseIsoMillisOrNow(room.created_at),
        )
    }

    override suspend fun resolveCallRoom(slug: String): CallRoom? {
        val room = roomRepository.getRoom(slug, callDisplayName).getOrNull() ?: return null
        // Ended room (caller gave up before we resolved) → call is over.
        if (!room.closed_at.isNullOrBlank()) return null
        val lk = room.livekit ?: return null // trusted rooms hand tokens to any logged-in user
        return CallRoom(
            roomId = room.id,
            slug = room.slug ?: slug,
            roomName = room.name ?: slug,
            livekitUrl = lk.url,
            livekitToken = lk.token,
            createdAtMs = parseIsoMillisOrNow(room.created_at),
        )
    }

    override suspend fun endCallRoom(roomId: String) {
        roomRepository.endRoom(roomId).getOrThrow()
    }

    /** The meeting FGS runs exactly while a LiveKit session is live → busy. */
    override fun isInMeeting(): Boolean = ConferenceForegroundService.isRunning

    /** P5 建议参会: invitee report — errors surface as Result.failure upstream
     * and the caller treats the whole thing as fire-and-forget. */
    override suspend fun reportSuggestedParticipants(
        slug: String,
        userIds: List<String>,
        source: String,
    ) {
        roomRepository.reportSuggestedParticipants(slug, userIds, source).getOrThrow()
    }

    private fun parseIsoMillisOrNow(iso: String?): Long {
        if (iso.isNullOrBlank()) return System.currentTimeMillis()
        val normalized = iso
            .replace(Regex("\\.\\d+"), "")
            .let { if (it.endsWith("Z")) it.dropLast(1) + "+0000" else it }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        return runCatching { fmt.parse(normalized)?.time }.getOrNull() ?: System.currentTimeMillis()
    }

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
