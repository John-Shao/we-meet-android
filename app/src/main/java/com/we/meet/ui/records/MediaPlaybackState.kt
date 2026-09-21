package com.we.meet.ui.records

/**
 * 录音回放与导入媒体两个播放器**共用的播放状态** —— 一处定义。
 *
 * 为什么要收这一处：两边原先各有一套字符串状态机，而**同名状态在两处含义不同** ——
 * `CaptureAudioPlayer` 的 `"loading"` 是「还没读到播放列表」，`UploadMediaPlayer` 的
 * `"loading"` 是「媒体还没准备完」。后果是同一条记录的两条入口行为不一致：在录音
 * 播放器里每一次拖动进度条（它会把状态置成 `"buffering"`）都会把整条控件换成一个
 * 转圈，而导入播放器只会在第一次准备时转一下。审计把这条记成
 * 「同一状态在两处语义不同」（`docs/reviews/meeting-modules-design-audit-2026-09-19.md` C11）。
 *
 * 六档语义与**渲染规则**（两个播放器必须一致）：
 *
 * | 状态 | 含义 | 渲染 |
 * | --- | --- | --- |
 * | [Loading] | 首屏还没拿到来源（播放列表 / 签名媒体） | 卡片内放一个内联转圈；此时还没有控件可画 |
 * | [Preparing] | 引擎还没就绪：首次准备**或**换段 / 拖动之后 | **控件照常显示**，转圈显示在控件上方，播放键显示「暂停」 |
 * | [Ready] | 就绪、未播放 | 播放键显示「播放」 |
 * | [Playing] | 正在播放 | 播放键显示「暂停」 |
 * | [Gap] | 当前位置没有音频（录音的空档） | 与 [Ready] 同，另加一句说明与「跳到下一段」 |
 * | [Error] | 失败 | 内联错误 + 重试 |
 *
 * 这里只定义状态与规则，不定义 UI —— 两个播放器的控件排布本来就不同（一个有视频
 * Surface，一个只有音频）。
 */
internal enum class MediaPlaybackState {
    Loading,
    Preparing,
    Ready,
    Playing,
    Gap,
    Error;

    /**
     * 播放键此刻该显示「暂停」吗。
     *
     * **含 [Preparing]**：准备中那一下点下去是「停下」，不是「再播一次」；两边一致。
     */
    val showsPause: Boolean
        get() = this == Playing || this == Preparing

    /**
     * 是否在控件上方显示一个内联转圈。
     *
     * [Preparing] 也算 —— 但**不再顶掉控件**（这是与改前最大的差别：录音播放器原先在
     * 换段/拖动时把整条控件换成转圈）。
     */
    val showsSpinner: Boolean
        get() = this == Loading || this == Preparing
}
