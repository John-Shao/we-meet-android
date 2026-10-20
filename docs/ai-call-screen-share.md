# AI 通话屏幕共享（Android）

「打电话」里的屏幕共享，让 AI 在实时语音通话中看到用户的屏幕，用来问「这个按钮在哪」「这一步怎么操作」。交互与豆包一致：**通话中发起 → 系统录屏授权（单个应用 / 整个屏幕）→ AI 持续看画面 → 随时停止**。

本文记录实现边界、数据流和验收步骤。代码位于 `feature-assistant` 的 `aicall` 包。不需要新增后端接口；屏幕状态通过既有的 `session.update` 在客户端同步给模型。

## 1. 用户体验

1. 通话接通（`AiCallStatus.Active`）后，底部控制区上方出现 **共享屏幕** 按钮。
2. 点击后先弹出预共享说明：AI 助手可以看到屏幕、屏幕内容会发送给 AI 服务、共享期间可随时停止；摄像头开着时追加「摄像头会关闭，停止后恢复」。
3. 确认后进入系统 MediaProjection 授权框：
   - Android 14+：可直接选「单个应用」或「整个屏幕」；选单个应用后系统继续弹出应用选择器。
   - Android 13 及以下：系统只支持整个屏幕。
4. 授权成功后：
   - 单个应用：系统已经把目标应用切到前台，App 不抢焦点。
   - 整个屏幕：App 停留在通话页，不自动跳桌面，避免覆盖单应用模式的选择。
   - 顶部状态显示「AI 正在查看你的屏幕」；状态卡主按钮是「打开应用」，次按钮是「停止共享」，并显示隐私说明。
   - 单应用共享时，如果用户切回 We Meet 或目标应用被切到后台，状态改为「共享应用已隐藏」，画面暂停；切回目标应用后自动恢复。
   - 常驻通知显示「屏幕共享中」，提供「停止共享」。
5. 以下任一方式结束共享，通话本身保持：
   - App 内「停止共享」
   - 通知里的「停止共享」
   - 系统录屏状态胶囊 / 锁屏等外部撤销（`MediaProjection.Callback.onStop`）
   - 结束后在共享按钮上方出现一次短暂的「屏幕共享已结束」提示，让用户知道是停止而不是启动失败。
6. 停止共享后，如果之前在视频通话且用户已回到通话页，会重新打开摄像头；在后台停止则等用户回到通话页再恢复。
7. 结束或挂断通话时，采集、虚拟显示和前台服务类型一并释放。

## 2. 平台差异

| 能力 | Android 10–13 (API 29–33) | Android 14+ (API 34) |
|---|---|---|
| 整个屏幕 | 支持 | 支持 |
| 单个应用 | 系统不开放 | 支持（`MediaProjectionConfig.createConfigForUserChoice()`） |
| 前台服务类型 | `mediaProjection` | `mediaProjection`，且必须在 `getMediaProjection()` 之前启动 |
| 内容尺寸/可见性回调 | 无 | `onCapturedContentResize` / `onCapturedContentVisibilityChanged` |

**一次授权只能采集一次。** 每次开始共享都要重新走 `createScreenCaptureIntent()`；Android 14 对同一个 result Intent 再次调用 `getMediaProjection()` 会直接失败。

## 3. 架构与数据流

```
MediaProjection consent Intent
        │
        ▼
ScreenShareCapture ── ScreenCapturerAndroid ── SurfaceTextureHelper (EGL)
        │                         │
        │                         └─ VideoFrame (OES texture)
        ▼
 ┌──────────────┴───────────────┐
 │ WebRTC                       │ AOQ
 │ VideoSource(isScreencast)    │ CameraFrameRouter → I420
 │ VideoTrack → videoSender     │ pushExternalVideoCapturedFrame
 └──────────────────────────────┘
```

- **共享的是原来那一条视频轨**，不是新增一条。摄像头和屏幕互斥：开始共享前会先关闭摄像头，共享期间视频按钮禁用；停止共享后可以再开摄像头。这样两端（WebRTC SDP / AOQ track）都不需要重新协商。
- WebRTC 只有协商到 H.264 时才创建视频 sender；没有 H.264 的设备上屏幕共享按钮会提示「当前连接方式不支持共享屏幕」。
- AOQ 在 ARM 进程内把屏幕帧转成 I420 后走既有的 external video 通道，编码参数为 800 kbps / `modelUploadFps`（默认 2 fps）。
- 画面尺寸取默认显示的真实尺寸，长边压到 1280 并向下取偶数（H.264 不接受奇数边），见 `ScreenShareSizing`。
- WebRTC 在收到 `onCapturedContentResize` 后调用 `changeCaptureFormat`，跟随单应用窗口尺寸；AOQ 的编码器在开始时固定画布，收到 resize 只记录日志，由 SDK 缩放内容，避免通话中重启外部帧编码器。
- 模型上传帧率沿用通话设置里的「模型上传帧率」；屏幕采集固定 10 fps，再由 `CameraFrameRouter` 节流。
- 单应用被切到后台时，`onCapturedContentVisibilityChanged(false)` 会暂停 WebRTC 视频轨 / AOQ 上帧，并通过 `session.update` 告诉模型「共享应用当前不可见」。

## 4. 关键实现点

### 4.1 前台服务类型先于 MediaProjection

`AssistantForegroundService` 原本已带 `microphone|camera|mediaPlayback`。开始共享时：

1. `AssistantForegroundSession.screenShare(true)` 重新下发一次服务启动，`onStartCommand` 用新的类型集合再次 `startForeground()`，补上 `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`；
2. 确认服务升级完成后，客户端才调用 `ScreenCapturerAndroid.startCapture()`，内部才会 `getMediaProjection()`。

顺序反了在 Android 14 上会抛 `SecurityException`。停止时先停采集，再把服务类型降回去。

如果服务已经在运行，只是 `startForeground` 的类型升级失败，现在只让屏幕共享失败，不会挂断整通电话；只有首次前台化失败才会结束会话。降级失败时会用不包含 `mediaProjection` 的类型重试一次，避免类型残留到通话结束。

### 4.2 授权入口

`AssistantCallScreen` 持有 `ActivityResultContracts.StartActivityForResult`：

```kotlin
if (Build.VERSION.SDK_INT >= 34) {
    manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
} else {
    manager.createScreenCaptureIntent()
}
```

没有自建应用选择器：单个应用的选择和「共享期间隐藏敏感内容」都由 Android 14 系统面板负责，避免我们维护一份应用列表和额外的隐私授权。

### 4.3 外部撤销与状态串行化

`ScreenCapturerAndroid` 的回调运行在 `SurfaceTextureHelper` 的线程上。回调里**不能**同步调用 `stopCapture()`（它会向同一线程 post 并阻塞），实现里只把事件 dispatch 回主线程：

```
onStop → ViewModel.screenShareRevoked → 状态置 false → 主线程 setScreenShareEnabled(false)
```

主动停止时先置 `stopping` 标志，避免 `mediaProjection.stop()` 触发的回调把「用户主动停止」误判成系统撤销。

`ScreenShareCapture` 有 `Starting/Active/Stopping` 状态：启动窗口内到达的 `onStop` 会让本次 start 直接失败；`onCapturedContentResize` / `onCapturedContentVisibilityChanged` 会先缓存，等客户端把会话标记为 live 后再在主线程重放，避免首帧 resize/可见性丢失。

ViewModel 用 `Mutex` + 递增 token 串行化 start/stop/系统撤销；系统撤销或停止会让启动中的协程失效，避免旧协程事后把状态又设回共享中。

### 4.4 模型感知与工具切换

开始/停止共享、以及单应用可见性变化时，客户端发送 `session.update`。其中开始/停止由 ViewModel 在 UI 状态落定后统一发送，客户端只负责可见性变化，避免同一次切换发两条相同更新：

- instructions 增加「用户正在共享屏幕，优先分析屏幕内容；不要要求打开摄像头或拍照」；
- 共享期间移除 `set_camera_enabled` / `get_camera_state` / `take_photo` 工具，只保留 `end_call`；
- 停止共享后恢复原来的工具集。

如果 AI 正在回复，更新会等 `response.done` 再发，避免 provider 拒绝运行中的工具变更；被拒绝的屏幕状态更新只记日志，不会中断通话。

### 4.5 与摄像头语音指令的冲突

摄像头工具在共享期间被移除；即使收到延迟到达的请求，`isScreenSharing` 守卫也会返回 `cancelled`，不会切换视频源。`take_photo` 同样被移除并加了守卫，避免共享屏幕时误开摄像头。

## 5. 代码地图

| 文件 | 作用 |
|---|---|
| `feature-assistant/.../aicall/model/ScreenShareSizing.kt` | 显示尺寸 → 采集尺寸；长边 1280、偶数边 |
| `feature-assistant/.../aicall/rtc/ScreenShareCapture.kt` | 一次 MediaProjection 会话；resize/visibility 回调 |
| `feature-assistant/.../aicall/rtc/OmniCallTools.kt` | 共享期间切换 instructions/tools |
| `feature-assistant/.../aicall/rtc/AoqVideoFramePacker.kt` | 任意 `VideoFrame` → AOQ I420（摄像头/屏幕共用） |
| `feature-assistant/.../aicall/rtc/OmniWebRtcClient.kt` | 屏幕 VideoSource/Track；resize、可见性、模型状态同步 |
| `feature-assistant/.../aicall/rtc/OmniAoqClient.kt` | 屏幕帧 → external video；可见性与模型状态同步 |
| `feature-assistant/.../aicall/vm/AiCallViewModel.kt` | start/stop/撤销串行化，摄像头提示与恢复 |
| `feature-assistant/.../aicall/ui/components/ScreenSharePanel.kt` | 通话内入口与「正在共享」状态卡 |
| `feature-assistant/.../aicall/ui/AssistantCallScreen.kt` | 系统授权 launcher、摄像头关闭确认 |
| `feature-assistant/.../background/AssistantForegroundService.kt` | `mediaProjection` 前台服务类型、通知栏停止共享、失败降级 |
| `feature-assistant/src/main/AndroidManifest.xml` | `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限与服务类型声明 |

## 6. 验收清单

- [ ] Android 14 真机：通话中 → 共享屏幕 → 系统面板选「单个应用」→ 选一个应用 → AI 能描述该应用画面；App 未被系统切走。
- [ ] 单应用共享中切到别的应用 → 状态提示 / 模型被告知不可见，不再基于旧帧回答；切回后恢复上帧。
- [ ] 单应用窗口旋转 / 分屏 resize → WebRTC 画面跟随；AOQ 不崩溃、画面可读。
- [ ] Android 14 真机：选「整个屏幕」→ 状态卡点「打开其他应用」→ 打开浏览器/设置 → AI 能描述；从通知「停止共享」后画面停止上传。
- [ ] Android 13 及以下：只出现整屏授权；可以正常共享。
- [ ] 视频通话中点共享 → 出现「关闭摄像头」确认；取消则不变；确认后共享，停止共享且回到通话页后摄像头恢复。
- [ ] 共享期间摄像头按钮置灰；AI 说「打开摄像头」或「拍张照」时不会抢视频轨 / 不会上传相机照片。
- [ ] 共享中从系统录屏胶囊停止 → App 内状态卡消失、通话仍在、麦克风仍可用。
- [ ] 共享中挂断 → 通知消失，系统录屏状态结束，再次通话可重新授权。
- [ ] WebRTC 无 H.264 的设备：按钮提示不支持，不崩溃。
- [ ] 拒绝通知权限时，仍可从系统投屏胶囊停止共享。
- [ ] 连续 10 次开始/停止，无残留采集、无通知残留。

## 7. 已知限制与后续

- AOQ 的编码画布在开始共享时固定，单应用 resize 只做日志与 SDK 缩放；如果后续需要像素级跟随，需要在 resize 时安全重启 external video 编码器。
- 单个应用共享依赖 Android 14 系统能力；低版本 OEM 的「应用投屏」属于厂商私有实现，不接入。
- 只共享屏幕画面，不采集应用音频；如果「看视频/听会议」成为场景，需要另做 `AudioPlaybackCapture`。
- 后台停止依赖通知或系统投屏胶囊；通知权限被拒绝时，系统胶囊仍是兜底入口。
- 入口受 `BuildConfig.AI_CALL_SCREEN_SHARE` 控制，默认开启；可用 `-PAI_CALL_SCREEN_SHARE=false` 关闭，便于按 OEM 灰度。
- iOS 端不在本方案范围。
- 屏幕帧仍然走现有的视频轨；模型侧通过客户端 `session.update` 获知共享状态，不需要新增后端接口。

## 8. 本地模拟器验证记录（2026-10-19）

环境：`Pixel_API36`，Android 16 / API 36，x86_64，google_apis，`emulator-5554`。

已通过：

- `AiCallScreenShareUiTest`（7/7）：通话中入口、共享中「停止共享 + 隐私说明」、授权前的隐私/摄像头说明、开始状态与结束提示、摄像头开启时的摄像头后果、单应用隐藏/暂停状态、不支持连接的原因提示。
- `AiCallTranscriptUiTest`（3/3）：屏幕共享改动后，原有 AI 通话 UI 没有回归。
- `AssistantForegroundSessionTest`（5/5）：包括新增的 `screenShareTypeUpgradeFailureDoesNotEndTheCall`，验证 `mediaProjection` 类型升级失败/成功后都不会挂断通话。
- `adb install` + `MainActivity` 启动成功；安装包已授予 `FOREGROUND_SERVICE_MEDIA_PROJECTION`。
- `aapt2 dump xmltree` 确认 `AssistantForegroundService` 的 `foregroundServiceType` 为 `0xE2`（`mediaPlayback|microphone|camera|mediaProjection`）。

模拟器不能覆盖：

- 真实 AI 通话（登录、后端、LiveKit/AOQ 分配、模型会话）。
- AOQ 媒体链路（x86_64 AVD，AOQ 需要 ARM 进程）；模拟器上只能用 WebRTC。
- OEM 的 MediaProjection/FGS 行为、Honor 真机、低端机 ANR。
- 系统授权框「单个应用/整个屏幕」的自动化点击；当前只验证了入口和 API 调用路径，真机仍需手动走一遍。

## 9. HONOR 真机验证记录（2026-10-20）

环境：`HONOR AMM-AN00`，Android 16 / API 36，arm64-v8a，1200×2664，520dpi，`AVSCVB5326005041`。

已通过：

- `adb install` 后 `MainActivity` 冷启动成功。
- `AiCallScreenShareUiTest`（8/8）：新增视频模式控件可见性用例；其余主流程在默认/130%/200% 字体、320dp 宽度下通过。
- `AiCallTranscriptUiTest` + `AssistantForegroundSessionTest`（8/8）真机通过。
- `dumpsys package` 确认 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 等权限 `granted=true`。
- `aapt2 dump xmltree` 确认 `AssistantForegroundService` 的 `foregroundServiceType=0xE2`。
- 真机登录 AI 通话（AOQ）后，系统授权框正确显示「单个应用 / 整个屏幕」；单应用共享荣耀笔记启动成功，状态条/状态卡显示共享中。
- 切回 We Meet 后，单应用共享正确进入「共享应用已隐藏」状态，画面暂停；通知显示「屏幕共享中 · 播报已开启」及「停止共享」Action。
- 点击通知「停止共享」后，通知恢复为语音输入状态，`dumpsys media_projection` 为 `null`，共享停止。
- 整屏共享启动成功，模型能识别「当前看到的是屏幕共享画面」；App 内「停止共享」后显示「屏幕共享已结束」，共享按钮恢复。
- 真机日志确认 AOQ 屏幕帧链路可用。
- 共享中直接挂断：1 秒内回到通话结束态，`MediaProjection` 释放为 `null`，FGS 通知移除，未出现 ANR/`Input dispatching timed out`；随后立即重拨，新 AOQ 通话正常建立。
- 相机恢复：视频通话中打开相机 → 共享屏幕（日志 `camera disabled durationMs=477`）→ 在 App 内停止共享后自动恢复相机（`camera enabled durationMs=644`），预览、状态条和底部控件恢复。
- 后台相机恢复：停留在荣耀笔记时从通知栏「停止共享」，相机不会立即恢复；回到 We Meet 后 `camera enabled durationMs=939`，预览和控件恢复。
- 可读性结果：
  - 用户手动验证：debug 包、整屏共享相册里的 `Readability Check 2026` 截图，AI 能正确识别并读出文字；说明模型 OCR/视觉本身可用，静态图片路径正常。
  - 用户进一步对照：实时 Chrome 页面时，AI 先回答「屏幕上显示的是‘你太多了是吧’」，明显不是当前页面；打开同一页面的截图后，AI 正确读出 `Readability Check 2026 / Invoice 8472 / Total 1,284.50 USD / Room code 7391 - Falcon-91`。这进一步确认模型 OCR 正常。
  - 用户第二次实时测试：把 Chrome 页面切到前台后，AI 描述的是微信聊天列表（文件传输助手、班级群、幸福一家人等），而微信并不在前台。说明 AI 不是纯幻觉，而是拿到了**上一个前台应用的真实画面**；当前问题更像是实时屏幕帧/模型视觉上下文滞后，而不是 OCR 能力。
  - 用户单应用 Chrome 复测：AI 先描述「桌子、椅子、书本」等物体，随后明确说「我看不到具体的画面，所以没法描述」，没有读出任何网页文字。静态截图可读、单应用 Chrome 不可读，基本排除「整屏后台切换」因素，问题集中在 Chrome 实时画面进入模型这一段；无有效图像时的物体描述属于模型猜测。
  - 决定性帧 dump：单应用 Chrome 共享时，客户端在 1s/3s/5s/10s 实际送出的 4 帧均为同一张 576×1280 的清晰 `Readability Check 2026` 页面（SHA-256 完全一致），但 AI 仍回答「标题是发票，发票号 2024-01-001，总金额 500元」。客户端采集和帧内容已证明正确，问题在模型/agent 是否接收并使用屏幕帧，而不是 MediaProjection、Chrome 或 AOQ 采集。
  - WebRTC 4fps 复测：把 WebRTC 屏幕共享从设置中的 2fps 提升到 4fps 后，单应用 Chrome 仍回答「标题是发票，发票号 20231015001，总金额 ¥1,280.00」。WebRTC answer SDP 已包含 `m=video`，说明视频轨已协商；帧率不是根因，问题仍在模型/agent 对视频帧的使用。
  - 计算器实测（WebRTC 4fps，整屏共享）：8,472 →「八千四百七十二」；73,391 →「七万三千三百九十一」；1,284,506 →「一百二十八万四千五百零六」，三次全部正确。说明实时视频轨确实可用、模型能读大字号数字；网页小字号失败更可能是 576×1280 采集分辨率/字号导致的 OCR 可读性不足，而不是「模型完全没用视频」。
  - 1920 长边复测：把屏幕采集长边从 1280 提到 1920 后，同一实时网页仍回答「标题发票，发票号 12345678，总额 ¥999.00」，与页面不符；提高整体分辨率没有解决小字号问题，已回退到 1280。下一步应做文字区域局部放大/裁剪，或对文字型页面使用截图 + photo QA。
  - 因此 Chrome 实时页面是否被正确采集仍未确定；下一步必须比较「客户端实际送出的帧」与「模型回答对应的帧」，区分客户端采集滞后和模型视觉采样滞后。
  - 自动化测试通过 Chrome 实时页面时失败：2fps/4fps 均出现幻觉；4fps 下 `Big Number Test` 的 **8472** 能正确读出，但小字号英文页面仍回答成中文发票。
  - WebRTC 真实语音验证：用户口述问题被正确转写，说明 WebRTC 音频链路正常；整屏共享同一个实时 Chrome 页面时，AI 仍回答成中文发票（123456 / ¥1,000.00）。
  - 临时 frame dump：共享早期 dump 到的是 We Meet 通话页/系统授权框；计划在 Chrome 前台后 dump 第 24 帧未产生日志，提示实时 Chrome 画面可能没有被持续采集或没有进入模型。当前问题更像是 **Chrome/WebView 实时画面采集/传递**，而不是模型 OCR 能力。
  - 结论：客户端送出的帧正确，模型也能读实时计算器的大数字；Chrome 小字号文字仍失败。问题不是「视频完全没进模型」，而是当前 576×1280 屏幕采集在大字号下可读、小字号下视觉 grounding 不足。不能宣称小字号文字/发票号可读；下一步应提高文字区域清晰度（更高采集分辨率或局部放大）或使用 photo QA 兜底。
  - 最终结论（2026-10-20）：普通 Chrome 页面没有 `FLAG_SECURE`；同机微信、计算器等应用可被 MediaProjection 正常捕获，只有 Chrome 的虚拟显示画面停留在通话页/旧帧。新增 `AccessibilityService.takeScreenshot` 兜底后，单应用 Chrome 测试成功：聊天缩略图为完整 `Readability Check 2026` 页面，AI 正确回答「标题 Readability Check 2026，发票号 Invoice 8472，总金额 1,284.50 USD」。`read_screen` 的显示图与 photo QA 上传图是同一份归一化 JPEG。无障碍服务需用户手动开启，只在显式读屏时截图。
  - 此前「模型/agent 未使用视频」「只是小字号分辨率不足」的判断被最终结果修正：至少在本机 Chrome 路径上，根因是 MediaProjection 无法捕获 Chrome 实时画面；改用无障碍截图后功能正常。

真机尚未覆盖：

- 系统投屏胶囊的停止路径（Honor 状态栏胶囊未暴露可点击入口）。
- 低端机共享中挂断的 ANR 风险；HONOR 上未复现，但 `close()` 仍是主线程同步释放。
- 折叠屏/分屏、通知栏折叠态下「停止共享」是否仍可见。
- 屏幕文字在 2fps/1280 长边下的可读性；模型能识别画面类型，但未做精确文字问答基准。

待跟踪异常：

- 在一次「共享 → 挂断 → 立即重拨 → 打开摄像头」后，HONOR 上曾出现视频模式只剩转写、底部麦克风/挂断/视频按钮和状态条不可见、窗口呈现旧帧的现象；`am force-stop` 后重启恢复正常。按同一完整序列再次复测（共享 → 挂断 → 重拨 → 开摄像头）**未复现**，全新视频通话的视频预览、状态条和控件正常。当前归类为一次性窗口/TextureView 合成异常，暂未定位到稳定触发条件，继续观察。
