# 第 72 批：Android PCM 采集与停止排空

新增 AudioRecord 适配器和可测试的阻塞 PCM pump。要求真实 16 kHz、单声道、PCM16，以 100 ms 读块组装 5 秒持久分片；存储同步完成后清空暂存 PCM，网络补传由外部协调。停止会解除阻塞读取、保存已读尾段并释放设备。尾段不足整毫秒时补最多 15 个零采样（小于 1 ms），不丢弃已读取样本。

系统静音、输入配置不匹配、输入设备切换和读取错误会中断，不能自动重新打开麦克风。存储失败不重放失败片段或继续采集；账号授权变化时停止并拒绝写入。硬件适配器只在后续前台服务取得权限后构造，目前未接 UI，未实际开麦。

验证：8 项 PCM 测试与 9 项传输回归、Debug 构建、设计 token 检查通过。覆盖可变读长、跨片顺序、尾部填充、输入失败、存储失败、授权撤销、停止前启动竞态、阻塞读取停止和内存清零。真实设备锁屏、蓝牙、来电和静音行为留待部署测试。

API 参考：[Android 共享音频输入](https://developer.android.com/media/platform/sharing-audio-input)、[AudioRecord](https://developer.android.com/reference/android/media/AudioRecord)。
