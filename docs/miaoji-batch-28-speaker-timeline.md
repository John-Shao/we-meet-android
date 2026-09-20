# 第二十八批：发言分布与回听

- 兼容新增 activity.timeline：展示已识别原文范围，保留静音间隔；不把最后转写结束时间称为媒体时长。
- Canvas 按真实区间比例绘制，点击落在发言区间时回听起点；展开列表提供完整触控和 TalkBack 入口，每页 10 个区间。
- 仅在原有播放能力可用时提供回调；只读时间区间不授权媒体。旧后端无字段时不新增入口；混合时钟、无效时间、超过预算时明确不可用。
- 新增 DTO 校验及 wire 兼容回归，3 项单元、3 项 Pixel 8 / Android 16 仪器测试通过；assembleDebug、checkDesignTokens 通过，APK 已安装。
- Web/后端对应 `docs/research/miaoji-batch-28-speaker-timeline.md`。生产待配套 backend/frontend 部署及 App 验收，正式 Android 分发尚未完成。
