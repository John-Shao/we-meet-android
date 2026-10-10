# “拜拜”偶现未挂断定位（2026-10-10）

故障发生在实时模型的工具选择阶段：模型收到告别语后，有时生成普通告别回复，没有产生 `end_call` 工具请求。客户端只有收到完整工具请求后才调用现有结束通话流程，因此此次没有进入挂断与资源释放逻辑。强化提示词并未消除此情况；本次只补充定位探针，未修改生产通话行为。

Pixel 7 Pro / AOQ 合成语音进行四次独立通话对照：两次先询问“拜拜是什么意思？”再说“拜拜”均成功；两次新通话直接说“拜拜”，一次失败、一次成功。由此排除“必须先解释词义才能触发”的假设，但不能推断模型内部的具体决策原因。

失败用例完整证据：

- 22:31:55.646：客户端确认加载强化后的告别规则，且工具列表包含 `end_call`；会话连接及配置成功。
- 22:31:57.646：用户最终转写为“拜拜。”。
- 22:31:58.454：AI 最终回复“拜拜，祝你今天愉快！”。
- 22:32:01.083：收到正常 `response.done`。
- 22:32:25.723：同一客户端仍为 `Active(mode=Voice)`；`endTools=0`、`seenTools=0`，响应状态 `hasTools=false, done=true, cancelled=false, feedbackFailed=false`。事件流没有 `response.function_call_arguments.done`。

成功对照收到 `response.function_call_arguments.done` 后，约 2.9 秒正常结束，工具调用一次、租约关闭一次。工具协调器 33 项单元测试通过，覆盖完整事件的三种入口、重复去重、立即结束和取消隔离。此前完整八项真机复测为七项通过、一项失败；后端相关 61 项测试通过，这些通过结果不能代替告别指令的稳定性验收。

原始日志位于工作区 `temp/goodbye-diagnosis-standalone-1-events.log` 和对应 `-test.log`，汇总见 `temp/goodbye-diagnosis-results.json`。探针只打印合成语音文字、协议事件类型和工具状态，不打印配置正文、凭据、照片或原始音频。

[阿里云 Function Calling 文档](https://www.alibabacloud.com/help/zh/model-studio/qwen-function-calling)说明，工具执行需要模型返回工具请求；Qwen-Omni-Realtime 不支持 `tool_choice` 和 `parallel_tool_calls`，因此不能通过强制指定工具参数解决本问题。

修复方向：对最终用户转写中明确的完整告别短句做客户端兜底，复用同一结束通话流程并去重；复杂意图继续交由模型。必须覆盖否定、询问、翻译、引用、旧会话回调及重复事件，避免按包含“再见／拜拜”做宽泛匹配；不能根据 AI 自己说出告别语触发挂断。此方向已实施，行为和验收见[告别通话说明](ai-call-goodbye-commands.md)。
