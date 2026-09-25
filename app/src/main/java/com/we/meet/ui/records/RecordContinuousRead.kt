package com.we.meet.ui.records

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.we.meet.R
import com.we.meet.data.repository.RecordSourceChangedException
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import retrofit2.HttpException

/** Private bodies are memory-only and cleared on pause; only the read depth is saved. */
internal class RecordContinuousRead<T, P>(
    private val initial: P,
    private val next: (T) -> P?,
    private val merge: (List<T>) -> T,
) {
    var result by mutableStateOf<Result<T>?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    private var pages = emptyList<Pair<P, T>>()
    private var failedAppend = false
    private val requests = Channel<Boolean>(Channel.CONFLATED)
    val hasNext: Boolean get() = pages.lastOrNull()?.second?.let(next)?.let { cursor -> pages.none { it.first == cursor } } == true
    val pageCount: Int get() = pages.size
    fun refresh() = request(false)
    fun loadMore() { if (hasNext) request(true) }
    fun retry() = request(failedAppend)
    private fun request(append: Boolean) {
        if (busy) return
        busy = true
        requests.trySend(append)
    }
    fun clear() { pages = emptyList(); result = null; error = null; busy = false }
    suspend fun collect(depth: () -> Int, saved: (Int) -> Unit, read: suspend (P) -> Result<T>) {
        suspend fun load(append: Boolean) {
            busy = true
            error = null
            try {
                val fresh = if (append) pages.toMutableList() else mutableListOf()
                val target = if (append) pages.size + 1 else maxOf(1, pages.size, depth())
                while (fresh.size < target) {
                    val cursor = if (fresh.isEmpty()) initial else next(fresh.last().second) ?: break
                    if (fresh.any { it.first == cursor }) break
                    val value = read(cursor).getOrThrow()
                    fresh.add(cursor to value)
                }
                pages = fresh
                result = pages.takeIf { it.isNotEmpty() }?.let { Result.success(merge(it.map { page -> page.second })) }
                saved(pages.size.coerceAtLeast(1))
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                failedAppend = append
                error = failure
                if (pages.isEmpty() || failure is IllegalArgumentException || failure is RecordSourceChangedException || failure is HttpException && failure.code() in listOf(401, 403, 404, 409)) {
                    pages = emptyList()
                    result = Result.failure(failure)
                }
            } finally { busy = false }
        }
        // Pending UI requests from the previous foreground lifetime are obsolete.
        while (requests.tryReceive().isSuccess) { }
        load(false)
        for (append in requests) load(append)
    }
}

@Composable
internal fun <T, P> rememberRecordContinuousRead(
    vararg keys: Any?, initial: P, next: (T) -> P?, merge: (List<T>) -> T,
    intervalMs: Long? = null,
    read: suspend (P) -> Result<T>,
): RecordContinuousRead<T, P> {
    val state = remember(*keys) { RecordContinuousRead(initial, next, merge) }
    var depth by rememberSaveable(*keys) { mutableIntStateOf(1) }
    val latestRead by rememberUpdatedState(read)
    val latestInterval by rememberUpdatedState(intervalMs)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val poller = launch {
                while (true) {
                    delay(latestInterval ?: 15_000)
                    if (latestInterval != null && state.error == null) state.refresh()
                }
            }
            try { state.collect({ depth }, { depth = it }, latestRead) }
            finally { poller.cancel(); state.clear() }
        }
    }
    return state
}

/** LazyColumn renders only visible rows; prefetch within three items of its end. */
@Composable
internal fun <T, P> RecordAutoLoad(state: RecordContinuousRead<T, P>, list: LazyListState, disabled: Boolean = false) {
    val nearEnd by remember(list) { derivedStateOf {
        val layout = list.layoutInfo
        layout.totalItemsCount > 0 && (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 4
    } }
    LaunchedEffect(nearEnd, state.pageCount, state.busy, state.error, disabled) {
        if (nearEnd && !disabled && !state.busy && state.error == null) state.loadMore()
    }
}

@Composable
internal fun <T, P> RecordLoadMore(state: RecordContinuousRead<T, P>, disabled: Boolean = false) {
    when {
        state.busy -> WeMeetInlineLoading()
        state.error != null -> TextButton(onClick = state::retry, enabled = !disabled, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.records_load_retry)) }
        state.hasNext -> TextButton(onClick = state::loadMore, enabled = !disabled, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.records_load_more)) }
        else -> Text(stringResource(R.string.records_load_end), Modifier.fillMaxWidth().padding(Dimens.SpaceM), textAlign = TextAlign.Center)
    }
}
