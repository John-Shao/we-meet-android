# AI 通话停止与连接保持

2026-10-10，在 Pixel 7 Pro（Android 17 / API 37）的真实 AOQ 连接上复现：服务端回复已经结束，但客户端尚未处理结束状态，此时发送 `response.cancel`，返回 `invalid_request_error`，错误码、参数和关联客户端事件 ID 均为空，消息为 `Conversation has none active response`。旧实现未识别这条取消请求错误，调用通话失败回调。修复前 `OmniStopLiveTest.aoqStopKeepsConnection` 在“回复结束时停止”断言失败。

取消容错新增对实测格式的精确识别，仅接受 30 秒内有取消记录的该请求错误；不接受过期、无取消记录、归属其他请求、其他参数或服务端故障。AOQ 和 WebRTC 在发送取消之前登记客户端事件，避免回调先于登记。通话仍使用原连接和原租约，没有自动重拨。错误日志仅记录类型、代码和参数，不保留模型内容或错误消息。

依据：[阿里云客户端事件说明](https://help.aliyun.com/zh/model-studio/client-events#response.cancel) 明确指出，没有响应可取消时会返回错误事件。回复完成与停止请求之间可能出现这种时间差。

验证：100 项助手单元测试通过，包括新增实测错误格式、请求归属、过期和真实故障不被吞掉的回归。Debug App、AndroidTest、Release Kotlin 编译及设计规范检查通过。Pixel 上 AOQ、WebRTC 的真实连接测试均通过：活动回复连续停止、重复点击、服务端已结束而客户端状态尚未更新时停止，随后文字与音频继续响应；每次测试仅分配一个连接。测试采用合成文字，物理麦克风静音，不保存历史或拍照。
