# 第 73 批：Android 录音前台服务

CaptureForegroundService 将按账号隔离的恢复日志、控制器、PCM 采集和上传串联。绑定只读取本地恢复状态；显式启动要求可见且 RESUMED 的 Activity、麦克风权限及当前账号，禁止与线上会议前台服务同时采集。前台通知立即显示且不包含会议标题，支持暂停；采集持有有时限的 CPU 唤醒锁，暂停先停止读取并排空尾段，然后释放锁、麦克风和前台通知。

服务 START_NOT_STICKY，不因系统重启自动开麦。停止请求使在途启动失效；账号退出停止输入并清除绑定界面的私有状态；上传失败保留本地数据并等待显式补传。错误仅公开状态，不输出凭据、正文或 PCM 日志。服务内部网络仅使用已有固定路径仓库。

新增独立构建保险丝 WE_MEET_CAPTURE_NATIVE=false，manifest 服务默认禁用、不可导出。UI 下一批接入。CaptureServiceHost 由 Application 提供当前账号和仓库，测试 runner 使用合成 PCM 与内存协议替身，完全绕开实际 AudioRecord、业务 Application、网络和模型。

验证：真实 Android 服务生命周期下 4 项测试通过（本地绑定不重启录音、通知暂停与排空封存、账号切换、启动响应迟到）；同批 17 项日志/恢复回归通过。Debug/test APK 和设计 token 检查通过。通知测试发现系统可能延迟显示，已明确设置立即显示，测试单独授予通知权限并等待可见回执。实际麦克风、锁屏续录、蓝牙和来电仍由用户部署后实测。

参考：[前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)、[共享音频输入](https://developer.android.com/media/platform/sharing-audio-input)。
