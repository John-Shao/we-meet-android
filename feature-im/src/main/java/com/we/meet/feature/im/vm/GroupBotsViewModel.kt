package com.we.meet.feature.im.vm

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.R
import com.we.meet.feature.im.data.ImBotDto
import com.we.meet.feature.im.userMessageRes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 一个机器人在 UI 里的样子。与传输层的 `ImBotDto` 分开(后者是 internal),
 * 沿用 [GroupMemberUi] 的做法:屏幕不认识 DTO。
 */
data class BotUi(
    val id: String,
    val name: String,
    val description: String,
    val avatarUrl: String?,
    val colorIndex: Int,
    /** builtin = 内置助手:不可移除、不可改名,只能在本群停用。 */
    val isBuiltin: Boolean,
    val isActive: Boolean,
    /** 群主才拿得到 —— 非群主这些是 null。 */
    val webhookUrl: String?,
    val signVerifyEnabled: Boolean,
    val keywords: List<String>,
    val ipAllowlist: List<String>,
    val canManage: Boolean,
    /** 出站回调 (A3)。空串 = 没配 = 关。 */
    val callbackUrl: String,
    val callbackIncludeIdentity: Boolean,
    /** 连续失败多次后后端自己关掉的;重新保存地址即可恢复。 */
    val callbackEnabled: Boolean,
    /** 最近一次失败的桶,上一次成功则为空串。见 [callbackFailureLabel]。 */
    val callbackLastError: String,
)

/**
 * 失败桶 → 文案。桶由后端定(`services/bot_callback.FAILURE_BUCKETS`),
 * 客户端只负责翻译 —— 每一档对应群主的一个不同动作:等一会儿 / 找对方 /
 * 查网络 / 改地址。
 *
 * 认不出的桶按「对方拒绝」显示而不是隐藏:不认识的失败也是失败。
 */
@StringRes
internal fun callbackFailureLabel(bucket: String): Int = when (bucket) {
    "timeout" -> R.string.im_bots_callback_reason_timeout
    "unreachable" -> R.string.im_bots_callback_reason_unreachable
    "blocked" -> R.string.im_bots_callback_reason_blocked
    else -> R.string.im_bots_callback_reason_refused
}

internal fun ImBotDto.toUi(): BotUi = BotUi(
    id = id,
    name = name,
    description = description,
    avatarUrl = avatarUrl?.takeIf { it.isNotBlank() },
    colorIndex = avatarColorIndex,
    isBuiltin = kind == "builtin",
    isActive = isActive,
    webhookUrl = webhookUrl,
    signVerifyEnabled = signVerifyEnabled == true,
    keywords = keywords.orEmpty(),
    ipAllowlist = ipAllowlist.orEmpty(),
    canManage = canManage,
    callbackUrl = callbackUrl.orEmpty(),
    callbackIncludeIdentity = callbackIncludeIdentity == true,
    // 非群主全部拿 null。默认 true 是对的:没配地址时「已停用」的横幅不该出现。
    callbackEnabled = callbackEnabled != false,
    callbackLastError = callbackLastError.orEmpty(),
)

data class GroupBotsUiState(
    val bots: List<BotUi> = emptyList(),
    val loading: Boolean = true,
    /**
     * 群主才看得到「添加机器人」和凭据。判定不靠本地猜 —— 后端对非群主把凭据
     * 字段返回 null,列表里任意一条能管就说明是群主。
     */
    val canManage: Boolean = false,
    @StringRes val error: Int? = null,
)

/** 群机器人列表(对标飞书「群机器人」二级页)。 */
class GroupBotsViewModel internal constructor(
    private val session: ImSession,
    private val cid: String,
) : ViewModel() {

    private val _ui = MutableStateFlow(GroupBotsUiState())
    val ui: StateFlow<GroupBotsUiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    /**
     * 重新拉列表。列表页每次重新进入组合都会调 —— 从表单/详情返回时靠这个
     * 刷新,而不是靠一个会过期的本地缓存。
     */
    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching { session.bridge.listBots(cid) }
                .onSuccess { bots ->
                    _ui.update {
                        it.copy(
                            bots = bots.map { bot -> bot.toUi() },
                            loading = false,
                            canManage = bots.any { bot -> bot.canManage },
                        )
                    }
                }
                .onFailure { e ->
                    Log.w(TAG, "list bots failed", e)
                    _ui.update {
                        it.copy(loading = false, error = e.userMessageRes())
                    }
                }
        }
    }

    class Factory(private val deps: ImDeps, private val cid: String) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(GroupBotsViewModel::class.java))
            return GroupBotsViewModel(ImSession.get(deps), cid) as T
        }
    }

    private companion object {
        const val TAG = "GroupBotsVM"
    }
}

// ---- form ----

sealed interface BotFormEvent {
    /** 创建成功 —— 跳详情页(webhook 地址在那里),并把表单/选择两层 pop 掉。 */
    data class Created(val botId: String) : BotFormEvent
    data object Saved : BotFormEvent
}

data class BotFormUiState(
    val name: String = "",
    val description: String = "",
    val colorIndex: Int = 0,
    val busy: Boolean = false,
    @StringRes val error: Int? = null,
)

/** 自定义机器人表单 VM:新建与编辑同一套字段,同一个 VM。 */
class BotFormViewModel internal constructor(
    private val session: ImSession,
    private val cid: String,
    private val botId: String?,
) : ViewModel() {

    private val _ui = MutableStateFlow(BotFormUiState())
    val ui: StateFlow<BotFormUiState> = _ui.asStateFlow()

    private val _events = MutableSharedFlow<BotFormEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<BotFormEvent> = _events.asSharedFlow()

    val isEdit: Boolean get() = botId != null

    init {
        if (botId != null) {
            viewModelScope.launch {
                runCatching { session.bridge.listBots(cid) }
                    .onSuccess { bots ->
                        bots.firstOrNull { it.id == botId }?.let { bot ->
                            _ui.update {
                                it.copy(
                                    name = bot.name,
                                    description = bot.description,
                                    colorIndex = bot.avatarColorIndex,
                                )
                            }
                        }
                    }
            }
        }
    }

    fun onName(value: String) = _ui.update { it.copy(name = value.take(NAME_MAX)) }

    fun onDescription(value: String) =
        _ui.update { it.copy(description = value.take(DESC_MAX)) }

    fun onColor(index: Int) = _ui.update { it.copy(colorIndex = index) }

    fun submit() {
        val state = _ui.value
        val name = state.name.trim()
        if (name.isEmpty() || state.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            runCatching {
                if (botId == null) {
                    session.bridge.createBot(
                        cid = cid,
                        name = name,
                        description = state.description.trim(),
                        avatarColorIndex = state.colorIndex,
                    ).id
                } else {
                    session.bridge.updateBot(
                        id = botId,
                        name = name,
                        description = state.description.trim(),
                        avatarColorIndex = state.colorIndex,
                    )
                    null
                }
            }
                .onSuccess { newId ->
                    _ui.update { it.copy(busy = false) }
                    _events.tryEmit(
                        if (newId != null) BotFormEvent.Created(newId) else BotFormEvent.Saved,
                    )
                }
                .onFailure { e ->
                    Log.w(TAG, "bot form submit failed", e)
                    _ui.update { it.copy(busy = false, error = e.userMessageRes()) }
                }
        }
    }

    class Factory(
        private val deps: ImDeps,
        private val cid: String,
        private val botId: String?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BotFormViewModel::class.java))
            return BotFormViewModel(ImSession.get(deps), cid, botId) as T
        }
    }

    private companion object {
        const val TAG = "BotFormVM"

        // UTF-16 code units, matching the backend's check and the web form —
        // a name one端 accepts must not be rejected by another.
        const val NAME_MAX = 32
        const val DESC_MAX = 256
    }
}

// ---- detail ----

data class BotDetailUiState(
    val bot: BotUi? = null,
    val loading: Boolean = true,
    /** 明文密钥,只在用户点「显示」后才拉;隐藏时清空,不留在内存里。 */
    val secret: String? = null,
    /** 出站回调的验签密钥。与 [secret] 是两把,各自独立显示/隐藏。 */
    val callbackSecret: String? = null,
    val busy: Boolean = false,
    @StringRes val error: Int? = null,
    /** 已移除 —— 页面该 pop 回列表。 */
    val removed: Boolean = false,
)

/** 一个机器人:webhook 地址、三道闸门、移除。 */
class BotDetailViewModel internal constructor(
    private val session: ImSession,
    private val cid: String,
    private val botId: String,
) : ViewModel() {

    private val _ui = MutableStateFlow(BotDetailUiState())
    val ui: StateFlow<BotDetailUiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { session.bridge.listBots(cid) }
                .onSuccess { bots ->
                    _ui.update {
                        it.copy(
                            bot = bots.firstOrNull { b -> b.id == botId }?.toUi(),
                            loading = false,
                        )
                    }
                }
                .onFailure { e ->
                    Log.w(TAG, "bot detail load failed", e)
                    _ui.update { it.copy(loading = false, error = e.userMessageRes()) }
                }
        }
    }

    fun toggleSecret() {
        if (_ui.value.secret != null) {
            _ui.update { it.copy(secret = null) }
            return
        }
        mutate { _ui.update { it.copy(secret = session.bridge.botSecret(botId)) } }
    }

    fun toggleCallbackSecret() {
        if (_ui.value.callbackSecret != null) {
            _ui.update { it.copy(callbackSecret = null) }
            return
        }
        mutate {
            _ui.update { it.copy(callbackSecret = session.bridge.botCallbackSecret(botId)) }
        }
    }

    fun resetSecret() = mutate {
        // 重置后直接把新值亮出来 —— 换了密钥却不告诉人新值,等于只是把对方的
        // 服务弄坏了。
        _ui.update { it.copy(secret = session.bridge.resetBotSecret(botId)) }
    }

    fun setSignVerify(enabled: Boolean) = mutate {
        val bot = session.bridge.updateBot(id = botId, signVerifyEnabled = enabled)
        _ui.update { it.copy(bot = bot.toUi()) }
    }

    fun setActive(active: Boolean) = mutate {
        val bot = session.bridge.updateBot(id = botId, isActive = active)
        _ui.update { it.copy(bot = bot.toUi()) }
    }

    fun setKeywords(keywords: List<String>) = mutate {
        val bot = session.bridge.updateBot(id = botId, keywords = keywords)
        _ui.update { it.copy(bot = bot.toUi()) }
    }

    fun setIpAllowlist(entries: List<String>) = mutate {
        val bot = session.bridge.updateBot(id = botId, ipAllowlist = entries)
        _ui.update { it.copy(bot = bot.toUi()) }
    }

    /** 空串 = 关掉回调。地址不合法时后端 400,走 [mutate] 的错误分支当场提示。 */
    fun setCallbackUrl(url: String) = mutate {
        val bot = session.bridge.updateBot(id = botId, callbackUrl = url.trim())
        _ui.update { it.copy(bot = bot.toUi()) }
    }

    fun setCallbackIdentity(include: Boolean) = mutate {
        val bot = session.bridge.updateBot(id = botId, callbackIncludeIdentity = include)
        _ui.update { it.copy(bot = bot.toUi()) }
    }

    fun remove() = mutate {
        session.bridge.deleteBot(botId)
        _ui.update { it.copy(removed = true) }
    }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            try {
                block()
                _ui.update { it.copy(busy = false) }
            } catch (e: Throwable) {
                Log.w(TAG, "bot action failed", e)
                _ui.update { it.copy(busy = false, error = e.userMessageRes()) }
            }
        }
    }

    class Factory(
        private val deps: ImDeps,
        private val cid: String,
        private val botId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BotDetailViewModel::class.java))
            return BotDetailViewModel(ImSession.get(deps), cid, botId) as T
        }
    }

    private companion object {
        const val TAG = "BotDetailVM"
    }
}
