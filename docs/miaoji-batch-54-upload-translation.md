# 第 54 批：上传全文翻译

上传记录现在有译文入口：中英文选择、显式生成/重试、完整原文对照、源时间回听、50 段分页及 TXT/SRT/VTT 导出。旧原文修订的译文标明过期并禁用导出/定位；非所有者只能读取有全文权限的材料。

依赖 we-meet 第 54 批 backend 接口及迁移 0187、启用 UPLOAD_TRANSCRIPT_TRANSLATION_ENABLED。此功能不使用实时翻译 WebSocket、agents 或 LiveKit Egress。

请求未知保留同一请求键核对；所有请求绑定当前账号/记录。无持久正文缓存，后台及权限失败清理正文。系统文件选择器返回后再取译文文件并鉴权；失败清理部分文件。

验证：JVM 翻译仓库 11 项、新 Compose 仪器测试 4 项（Pixel 8 / Android 16）通过，debug 构建通过。真实生产翻译、正式 APK 分发和物理设备验收仍待后端发布后完成。供应商测试使用 fixture，不能作为翻译质量结论。

## 2026-09-21 生产后续

第 55 批已确认 Helm 385 / backend、frontend `a616ec42e`，Android `48af303a` debug APK 在 Pixel 8 上完成真实生成、对照/回听、三个格式保存、旧版提示和重生成；API 验证权限撤销与回收恢复。专用样本永久删除 pending，北京时间 13:21:35 后复核。完整证据与未覆盖项见 we-meet 仓库 `docs/research/miaoji-batch-55-upload-translation-acceptance.md`。上述“待发布”是本批实施时的历史状态；正式 APK 分发仍待完成。
