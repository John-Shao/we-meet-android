# 原生转写无语音提示

仅当最新任务为 `incomplete`、`error_code=no_speech_detected` 且 `final_count=0`，显示“未识别到有效语音”，提示检查录音、音量和麦克风后再决定是否重新转写。普通失败、部分文字、取消/成功状态保留原有显示；不新增自动转写或重试。

后端采用新增可选错误原因，旧 API 仍兼容。服务端/worker 的严格归类、回执和旧原文保护见 we-meet 仓库 `docs/research/miaoji-asr-no-speech-2026-09-21.md`。需要先发布对应 backend/agents；历史通用失败不追溯改写。

验证：CaptureAsrStatusTest 2 项、CaptureTranscriptionRepositoryTest 7 项及 assembleDebug 通过。debug APK 已构建，未安装到共享模拟器；新后端发布后的真实 App 页面验收和正式分发待完成。
