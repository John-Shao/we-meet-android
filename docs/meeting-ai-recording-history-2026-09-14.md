# 已完成录音进入历史会议

会议首页此前只合并本机会议历史和服务端房间列表，独立 AI 录音没有房间，因此保存后不会出现在历史会议中。

本次 Android 更新将当前账号拥有的已完成录音加入同一列表，按录音开始时间与现有会议历史混排。录音使用麦克风图标，副标题显示「录音」及本地时间，点击通过准确的 record ID 进入会议笔记；会议仍进入原会议详情。

## 数据与刷新

- 使用现有 `GET /api/v1.0/meeting-records/`，参数 `scope=owned&source_type=audio_recording&is_ongoing=false`，在服务端过滤后分页，每页最多 30 条。底部支持继续加载录音。
- 后端只有 finalize 完成的 capture 才进入 stopped；录制中、暂停、上传中和等待完成保存的录音不显示在历史会议中。
- 不要求已有转写或纪要，保存后即可访问，后续处理状态在笔记详情查看。
- 首页恢复可见时重新读取第一页；分页按记录 ID 去重，刷新取消旧请求，账号校验沿用 MeetingRecordRepository。
- 录音数据不写入旧 HistoryStore，不把 record ID 当作 room ID，不触发录音或模型调用。

## 验证与走查

- 21 项 JVM 测试通过：HistoryTimelineTest 4 项，MeetingRecordRepositoryTest 17 项。
- Android 主代码、测试代码编译通过（`:app:compileDebugAndroidTestKotlin`）。
- 核对现有后端完成状态、权限过滤、游标分页和准确记录导航；`git diff --check` 通过。
- 未执行真机 UI 回归。本次仅更新 Android，需重新构建并覆盖安装 APK；无需更新后端或 Secret。

部署后验证：完成一份录音保存，返回会议首页，确认录音显示在正确时间位置；点击查看该录音的原文、播放和纪要。再检查未完成保存的录音不会混入，较早录音可继续加载。
