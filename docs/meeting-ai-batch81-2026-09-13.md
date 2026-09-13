# 第 81 批：有界回放引擎与 Android 音频输出

CapturePlaybackEngine 为每次显式播放/跳转创建一次会话，最多保留当前分片与一个预取分片；相邻且序号连续才自动衔接，缺口停止。停止/取消释放输出并清空音频数组，迟到预取不能重启播放。每 2 秒检查访问权，检查超时 2.5 秒，本地授权有效期最多 5 秒；账号、页面生命周期及授权状态由调用端提供实时判断。输出不前进时有界终止。

AndroidCapturePlaybackOutput 使用 AudioTrack MODE_STATIC、原始 16k PCM 与保音调倍速，支持分片内精确偏移。播放时申请音频焦点，失焦（含 duck）和耳机断开广播均释放缓冲，不会自动恢复；关闭可重复调用。接口规则参考 [AudioTrack](https://developer.android.com/reference/android/media/AudioTrack) 与 [音频焦点](https://developer.android.com/media/optimize/audio-focus) 官方文档。

验证：8 项引擎测试及 7 项回放协议回归，共 15 项 JVM 测试通过；2 项真实 AudioTrack/音频焦点测试在无音频设备输出的隔离模拟器中通过，仅使用静音合成 PCM。Debug/test APK 与设计 token 检查通过。未调用网络、模型或麦克风；实体设备耳机/蓝牙仍待用户部署测试。播放器 UI 与页面生命周期接入继续下一批。
