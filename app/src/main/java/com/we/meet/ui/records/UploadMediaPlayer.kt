package com.we.meet.ui.records

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.os.SystemClock
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

import com.we.meet.R
import com.we.meet.data.api.dto.RecordMediaDto
import com.we.meet.data.capture.UploadMediaEngine
import com.we.meet.data.capture.WholeFilePlayback
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens

import kotlinx.coroutines.delay

/** How often a playing file reports its clock. Matches the capture engine's cadence. */
private const val POSITION_POLL_MS = 250L
private const val PREPARATION_TIMEOUT_MS = 30_000L

/**
 * Whole-file playback for an imported recording.
 *
 * Reports the source clock through [onPosition] so the transcript can follow, and
 * accepts a seek request so a citation can address an exact millisecond. Both
 * are the same contract the capture player offers, so the reader's sync logic is
 * shared rather than reimplemented per source.
 */
@Composable
internal fun UploadMediaPlayer(
    media: RecordMediaDto,
    positionMs: Long?,
    seek: CaptureAudioSeek? = null,
    onSeekConsumed: () -> Unit = {},
    onPosition: (Long) -> Unit = {},
    onDuration: (Long?) -> Unit = {},
    /**
     * Fixtures inject a fake so the controls, the position contract and the seek
     * handling can be exercised without opening a real stream. Null means the
     * real engine, built against this composable's own application context.
     */
    createEngine: ((url: String, onInterrupted: () -> Unit) -> WholeFilePlayback)? = null,
    sourceId: String = media.name,
    /** Bounds native HTTP preparation retries; tests use a shorter real-clock deadline. */
    preparationTimeoutMs: Long = PREPARATION_TIMEOUT_MS,
) {
    val context = LocalContext.current.applicationContext
    val latestPosition by rememberUpdatedState(onPosition)
    val latestDuration by rememberUpdatedState(onDuration)
    val latestConsume by rememberUpdatedState(onSeekConsumed)
    var engine by remember(sourceId) { mutableStateOf<WholeFilePlayback?>(null) }
    var state by remember(sourceId) { mutableStateOf(MediaPlaybackState.Ready) }
    var position by remember(sourceId) { mutableLongStateOf(positionMs ?: 0L) }
    var duration by remember(sourceId) { mutableLongStateOf(0L) }
    var rate by remember(sourceId) { mutableFloatStateOf(1f) }
    var ratesVisible by remember(sourceId) { mutableStateOf(false) }
    var tick by remember(sourceId) { mutableIntStateOf(0) }
    var preparationStartedAt by remember(sourceId) { mutableLongStateOf(0) }
    var automaticRecoveries by remember(sourceId) { mutableIntStateOf(0) }

    var openedUrl by remember(sourceId) { mutableStateOf<String?>(null) }
    var surface by remember(sourceId) { mutableStateOf<Surface?>(null) }
    var aspect by remember(sourceId) { mutableFloatStateOf(16f / 9f) }
    val latestMedia by rememberUpdatedState(media)

    fun stop() {
        engine?.close()
        engine = null
        state = MediaPlaybackState.Ready
    }

    DisposableEffect(sourceId) {
        latestDuration(null)
        onDispose { engine?.close(); latestDuration(null) }
    }

    // Prepared lazily: opening the screen must not fetch a GB-scale file.
    fun start(from: Long, automatic: Boolean = false) {
        automaticRecoveries = if (automatic) automaticRecoveries + 1 else 0
        // A refreshed lease does not interrupt an existing stream. The next
        // explicit play/seek uses the latest URL while preserving source time.
        if (engine != null && openedUrl != latestMedia.url) stop()
        val current = engine ?: runCatching {
            openedUrl = latestMedia.url
            createEngine?.invoke(latestMedia.url) { stop() }
                ?: UploadMediaEngine(context, latestMedia.url) { stop() }
        }
            .onFailure { state = MediaPlaybackState.Error }
            .getOrNull() ?: return
        engine = current
        runCatching { current.setSurface(surface); current.play(from, rate) }
            .onSuccess {
                state = if (current.isPreparing()) MediaPlaybackState.Preparing else MediaPlaybackState.Playing
                if (state == MediaPlaybackState.Preparing) preparationStartedAt = SystemClock.elapsedRealtime()
                duration = current.durationMs()
                latestDuration(duration.takeIf(::validMediaDuration))
            }
            .onFailure { stop(); state = MediaPlaybackState.Error }
        tick++
    }

    fun report(milliseconds: Long) {
        position = milliseconds
        latestPosition(milliseconds)
    }

    // One poller for the whole playing lifetime rather than a loop per tick.
    LaunchedEffect(tick, state) {
        while (state.showsPause) {
            val current = engine ?: break
            if (current.failure() != null) {
                if (openedUrl != latestMedia.url && automaticRecoveries < 1) start(position, automatic = true)
                else { stop(); state = MediaPlaybackState.Error }
                break
            }
            if (current.isPreparing()) {
                // Native HTTP may repeatedly retry 403 without an error callback.
                // A healthy prepared stream remains untouched by lease refreshes.
                if (SystemClock.elapsedRealtime() - preparationStartedAt >= preparationTimeoutMs) {
                    if (openedUrl != latestMedia.url && automaticRecoveries < 1) start(position, automatic = true)
                    else { stop(); state = MediaPlaybackState.Error }
                    break
                }
                delay(POSITION_POLL_MS)
                continue
            }
            report(current.positionMs())
            duration = current.durationMs()
            latestDuration(duration.takeIf(::validMediaDuration))
            aspect = current.videoAspectRatio()
            if (state == MediaPlaybackState.Preparing) {
                state = MediaPlaybackState.Playing
                automaticRecoveries = 0
            }
            if (!current.isPlaying()) {
                state = MediaPlaybackState.Ready
                break
            }
            delay(POSITION_POLL_MS)
        }
    }

    LaunchedEffect(seek?.token) {
        val request = seek ?: return@LaunchedEffect
        if (state == MediaPlaybackState.Error) {
            latestConsume()
            return@LaunchedEffect
        }
        // A citation seek also starts playback, matching the capture player.
        start(request.milliseconds)
        report(request.milliseconds)
        latestConsume()
    }

    val videoMaxHeight =
        (LocalConfiguration.current.screenHeightDp * Dimens.MediaPreviewMaxHeightRatio).dp
    val positionLabel = stringResource(R.string.capture_playback_position)
    // 「正在播/刚开始播」才需要那块视频面。引擎在准备中就会把视频轨道的尺寸报回来,
    // 所以按比例量出来的 Surface 从第一帧起就是对的;纯音频与未播放态不占这块高度。
    val showVideo = media.mediaType == "video" && state.showsPause
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Dimens.SpaceM), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            if (showVideo) key(sourceId) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // 按比例把画布收缩到画面本身:外层不再 fillMaxWidth,否则竖屏视频
                    // (9:16)会在两侧各留一条与画面等高的黑边 —— 1080px 宽的屏上,
                    // 画面只有 30% 屏高那么高,却整行铺黑,等于白白吃掉两条黑框。
                    val videoHeight = minOf(maxWidth / aspect, videoMaxHeight)
                    Box(
                        Modifier.size(width = videoHeight * aspect, height = videoHeight)
                            .clipToBounds()
                            .background(MaterialTheme.colorScheme.scrim),
                        contentAlignment = Alignment.Center,
                    ) {
                        AndroidView(
                            modifier = Modifier.size(width = videoHeight * aspect, height = videoHeight),
                            factory = { viewContext -> SurfaceView(viewContext).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) { surface = holder.surface; engine?.setSurface(surface) }
                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { engine?.setSurface(holder.surface) }
                                    override fun surfaceDestroyed(holder: SurfaceHolder) { engine?.setSurface(null); surface = null }
                                })
                            } },
                        )
                    }
                }
            }
            if (state == MediaPlaybackState.Error) {
                WeMeetInlineErrorState(
                    onRetry = { stop(); start(position) },
                    message = stringResource(R.string.capture_playback_error),
                )
            } else {
                if (state.showsSpinner) WeMeetInlineLoading()
                // 一条紧凑控制条:播放键 + 时间 + 进度 + 倍速/快退/快进。
                // 时间行原先单独占一行(未播放时是「0:00 / 0:00」,看着像坏了),
                // 改用共享的 playbackClockLabel —— 元数据没到就只显示当前位置。
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Dimens.MinTouchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                ) {
                    IconButton(onClick = { if (state.showsPause) { engine?.pause(); state = MediaPlaybackState.Ready } else start(position) }) {
                        Icon(
                            if (state.showsPause) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            stringResource(if (state.showsPause) R.string.cd_records_pause else R.string.cd_records_play),
                            Modifier.size(Dimens.IconMedium),
                        )
                    }
                    Text(playbackClockLabel(sourceTime(position), duration.takeIf(::validMediaDuration)?.let { sourceTime(it) }), style = MaterialTheme.typography.labelSmall)
                    // 进度条自己只有一条细轨,靠外层 48dp 的容器把热区撑到规范要求。
                    Box(Modifier.weight(1f).heightIn(min = Dimens.MinTouchTarget), contentAlignment = Alignment.Center) {
                        Slider(
                            position.coerceAtMost(maxOf(1L, duration - 1)).toFloat(),
                            onValueChange = { value ->
                                stop()
                                report(value.toLong())
                            },
                            enabled = duration > 0,
                            valueRange = 0f..maxOf(1f, (duration - 1).toFloat()),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = positionLabel },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { ratesVisible = true }) { Text(stringResource(R.string.capture_playback_rate, rate)) }
                    IconButton(onClick = { start(maxOf(0L, position - 15_000)) }) {
                        Icon(Icons.Outlined.Replay, stringResource(R.string.cd_records_skip_back))
                    }
                    IconButton(onClick = { start(minOf(maxOf(0L, duration - 1), position + 15_000)) }) {
                        Icon(Icons.Outlined.FastForward, stringResource(R.string.cd_records_skip_forward))
                    }
                }
            }
        }
    }
    if (ratesVisible) {
        AlertDialog(
            onDismissRequest = { ratesVisible = false },
            title = { Text(stringResource(R.string.capture_playback_speed)) },
            text = {
                Column {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                        TextButton(onClick = { rate = speed; ratesVisible = false; if (state == MediaPlaybackState.Playing) start(position) }) {
                            Text(stringResource(R.string.capture_playback_rate, speed))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { ratesVisible = false }) { Text(stringResource(R.string.records_close)) } },
        )
    }
}

/**
 * 导入媒体播放器上那行时间。
 *
 * 时长是从引擎问出来的，**准备期间它是 0** —— 之前无条件拼成 `「0:00 / 0:00」`，
 * 看着像坏了，也让未播放态平白多占一行。这里收口：还没有可信时长时只显示当前位置。
 *
 * 只给导入媒体用：录音回放的时长来自播放列表清单，读到列表那一刻它就是真的。
 */
@Composable
private fun playbackClockLabel(position: String, duration: String?): String =
    if (duration == null) position
    else stringResource(R.string.capture_playback_clock, position, duration)
