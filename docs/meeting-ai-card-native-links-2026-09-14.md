# 会议助手卡片打开原生笔记和纪要

会议助手的「查看笔记」「查看纪要」使用 URL 类型卡片按钮。RichCardBubble 通过 Compose LocalUriHandler 打开 URL，原先使用系统默认实现，即交给浏览器；已有的 RecordLinks 原生解析仅在外部 Intent 进入 App 时生效，App 内卡片没有接入。

## 修复

- AppNav 为托管页面提供 RecordUriHandler，覆盖聊天和转发卡片的 URL 打开入口。
- 使用现有 RecordLinks 严格匹配当前 HTTPS 服务的域名、端口、记录路径及 UUID；保留可选 summary UUID，不把无法解析的版本替换为最新纪要。
- 匹配的链接通过已有 pendingRecordLink 流程进入原生 RecordDetailScreen，保留登录等待及详情权限核验。
- 不依赖系统 App Link 域名验证，也不需要修改机器人消息。已有卡片同样生效。
- 原生功能关闭或链接不匹配时，使用原来的 UriHandler。

## 验证与发布

- 9 项 JVM 测试通过：RecordLinksTest 5 项、RecordUriHandlerTest 4 项。
- 使用用户提供的两种 URL 验证：原生接收准确 recordId / summaryId，浏览器回调不触发。
- 覆盖外部域名、无效 summary 及功能关闭时的回退；主代码及 Android 测试代码编译通过。
- 未执行真机卡片点击回归。重新构建并覆盖安装 Android App 后，点击已有卡片的两个按钮，确认留在 App 内且返回键回到聊天；「查看纪要」应展示链接指定版本。
- 后端、机器人配置及 jusi-light-im SDK 无需更新。
