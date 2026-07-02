# We Meet Android — 对齐 Web 端：IM 补齐 + 通讯录 + 日历 + Tab 改造

> 本文是二期开发的实施方案（M0–M6 里程碑，已于 2026-07-02 全部落地，App 提交 `b4201ba`、SDK 提交 `32dac0e`）。
> 关联文档（we-meet 仓库）：`docs/extensions/App端补齐Web端功能_二期_IM通讯录日历.md`（设计）、
> `docs/extensions/App端补齐Web端功能_二期_部署步骤.md`（部署）。

## Context

Web 端（`D:\workspace\we-meet\we-meet\src\frontend`）已有消息/通讯录/日历/视频会议 4 大模块；App 端视频会议已基本对齐，但 **IM 只是 1:1 纯文本 MVP，通讯录和日历零代码**。后端 API 已全部就绪（directory、calendar-events、im 富消息桥接），jusi-light-im 服务端能力齐全，主要是纯客户端开发 + Android IM SDK 升级（alpha.1 → 对齐 web alpha.7）。

**已确认决策**：范围 = IM 一期核心 + 通讯录 + 日历（审批不做）；Tab = 消息·日历·会议·通讯录·我的（AI 移入「我的」页）；IM 分两期，本计划只做一期（群聊+图片/文件+已读回执+会话列表完善），二期（语音/引用/撤回/表情回应/@提及/转发）后续再排。

涉及两个仓库：`d:\workspace\we-meet\we-meet-android`（主体）和 `D:\workspace\jusi-light-im\sdk\android`（SDK，composite build 引入，改动即时生效）。

**实现时的权威参考**：web SDK `D:\workspace\jusi-light-im\sdk\web\src\{types,rest,client}.ts`；web IM hooks `we-meet\src\frontend\src\features\im\hooks\*.ts`（事件处理语义照搬）；web contacts/calendar `src\features\{contacts,calendar}`。

---

## 跨方案衔接决策（已定）

1. **ContactPicker 放在新模块 `:core-directory`**，`:app` 和 `:feature-im` 都依赖它。`ImDeps` 改为 `interface ImDeps : DirectoryDeps`（authedOkHttp/baseUrl 签名一致，WeMeetApp 现有实现自动满足），feature-im 直接调用 ContactPicker，不走 lambda 注入。
2. **通讯录「发消息」直接导航到应用级聊天路由** `im_chat/{cid}`（IM 改造后聊天页是 AppNav 路由），不做 pendingMainTab/pendingOpenImCid 机制。返回键回到成员详情，符合飞书行为。
3. **消息 Tab 未读角标**：由进程级 `ImSession`（M2 引入）暴露 `totalUnread: StateFlow<Long>`，MainTabScreen 直接 collect（:app 依赖 :feature-im，可直接访问）。

---

## 第一步 — 设计方案落档

将本计划整理为正式设计文档写入 `D:\workspace\we-meet\we-meet\docs\extensions\App端补齐Web端功能_二期_IM通讯录日历.md`（与既有《App端补齐Web端功能_总览.md》同目录、同命名风格），内容含：背景与差距表、跨方案衔接决策、M0–M6 里程碑、接口契约（ContactPicker/ImSession/新增路由）、验证清单。实现过程中若有偏差同步更新该文档。

## M0 — jusi-light-im Android SDK 升级（先行，独立仓库分支）

目录 `D:\workspace\jusi-light-im\sdk\android\sdk-im\src\main\kotlin\com\jusi\lightim\`：

- **Types.kt**：`ConversationSummary` 增加带默认值字段（保持源兼容）：`name=""`, `members=emptyList()`, `owner_uid`, `pinned/muted/mute_at_all=false`, `last_message/last_message_ts/last_sender_uid/last_content_type=null`。新增 `FrameType.CONV`、`ConvOutPayload(event,cid,conv_type,name,members)`、`ReadMarker(uid,seq)`、`ConvMember(uid,role,joined_at,nickname)`。
- **rest/ImService.kt** 新增：`GET /v1/conversations/{cid}/members`、`GET /v1/conversations/{cid}/reads`、`DELETE /v1/conversations/{cid}`（⚠️ 带 body 需 `@HTTP(method="DELETE", hasBody=true)`，body `{transfer_to?}`）、`PATCH .../owner`、`PATCH .../settings`（partial 用 `Map<String,Any>` 只装非空字段）、`POST .../clear`。
- **rest/RestClient.kt**：对应包装方法，沿用现有 401-refresh-once 模式。
- **Client.kt**：新增 `conversationEvents: SharedFlow<ConvOutPayload>`（dispatchFrame 处理 CONV）；委托方法 listMembers/listReads/leaveConversation/transferOwner/setConversationSettings/clearHistory。`sendText(cid, body, clientMsgId, contentType)` 已支持 contentType，图片/文件复用。
- 版本号 `0.1.0-alpha.7`；扩展 RestClientTest（MockWebServer 覆盖 DELETE-with-body、partial PATCH）与 ClientTest（conv 帧分发）。

## M1 — 基础：`:core-directory` 模块 + 5 Tab 改造

### :core-directory（新 Gradle 模块，namespace `com.we.meet.core.directory`）

`settings.gradle.kts` 加 `include(":core-directory")`；`app` 和 `feature-im` 的 build.gradle.kts 加依赖。build.gradle.kts 克隆 feature-im 的（去 jusi SDK，加 coil-compose、retrofit、moshi）。

```
core-directory/src/main/java/com/we/meet/core/directory/
├── DirectoryDeps.kt        # { authedOkHttp: OkHttpClient; baseUrl: String }
├── net/DirectoryNetwork.kt # 照抄 ImNetwork.kt 模式
├── data/DirectoryDtos.kt   # DepartmentDto, MemberDto, 分页信封；⚠️ 除 id 外全部 nullable/默认值
│                           #   （ApiClient 用 KotlinJsonAdapterFactory 反射，缺字段即抛）
├── data/DirectoryApi.kt    # GET directory/departments/(?parent=)、departments/{id}/members/
│                           #   ?include_subtree=true、members/(?q=&department=&page=)、members/{userId}/
├── data/DirectoryRepository.kt  # listAllDepartments(翻页到 next=null, cap 20页, 按 depth/sort_order 排)、
│                           #   departmentMembers/searchMembers(分页 hasMore)
└── ui/
    ├── MemberAvatar.kt     # AsyncImage + 首字符占位；⚠️ cache key 固定为 "avatar:$userId"
    │                       #   （presigned URL 每次签名不同，禁止用 URL 做 key）
    └── ContactPicker.kt    # ModalBottomSheet ~85% 高；Single/Multi 模式、debounce 300ms 搜索、
                            #   excludeSelf/excludeUserIds、返回 List<PickedMember>
```

```kotlin
enum class ContactPickerMode { Single, Multi }
data class PickedMember(val userId: String /* we-meet uuid */, val displayName: String,
                        val email: String?, val avatarUrl: String?)
@Composable fun ContactPicker(deps: DirectoryDeps, mode: ContactPickerMode,
    excludeSelf: Boolean = true, excludeUserIds: Set<String> = emptySet(),
    onConfirm: (List<PickedMember>) -> Unit, onDismiss: () -> Unit)
```

状态用 remember 持有（非 ViewModel，避免 sheet 内 ViewModelStoreOwner 问题）。

### Tab 改造（app/src/main/java/com/we/meet/ui/main/MainTabScreen.kt）

- 新 `enum class MainTab { Messages, Calendar, Meeting, Contacts, Profile }`；TabItem 加 `badgeCount`，CompactTabBar 图标右上角画红点数字（99+ 截断）。
- Tab 顺序：消息(`tab_messages`) · 日历(`tab_calendar`✳) · 会议(`tab_meeting`) · 通讯录(`tab_contacts`✳) · 我的(`tab_profile`)。默认选中 Meeting(index 2)，现有 `coerceIn` 已防旧索引越界。
- AI Tab 删除：ProfileScreen 加「AI 助手」行（`profile_ai_entry`✳）→ 新回调 `onOpenAiHub`；AppNav 加路由 `ai_hub`（带返回栏的 Scaffold 包现有 `AiHubScreen`，其 onOpenAssistantCall 保持指向 `assistant_call`）。MainTabScreen 删 `onOpenAssistantCall` 参数。
- MainTabScreen 新增回调：`onOpenAiHub`、`onMemberClick(userId)`、`onEventClick(eventId)`、`onCreateEvent(epochDay)`、`onOpenChat(cid)`、`onNewChat()`。
- AppNav 新路由：`ai_hub`、`member_detail/{userId}`、`event_detail/{eventId}`、`create_event?epochDay={epochDay}`（M2 再加 IM 路由）。日历/通讯录 tab 内容先放占位，M4/M5 填充。
- `WeMeetApp` 声明 `DirectoryDeps`（现有 override 已满足），注册共享 `directoryRepository`。

## M2 — IM 重构核心（:feature-im + AppNav）

### 桥接 API 扩展（feature-im/data/ImApi.kt + 新 ImDtos.kt）

新增（对照 web `features/im/api/*`）：`POST api/v1.0/im/users/resolve/`、`im/conversations/group/`、`add-members/`、`remove-member/`、`update/`、`announce-leave/`、`im/images/upload-url/`、`im/files/upload-url/`、`im/images/resolve/`；`createDirect` 增加 `peer_user_id` 变体（ContactPicker 返回 we-meet user id）。

### 会话层（最大结构改动）

- **新 `ImSession.kt`**：进程级单例 `ImSession.get(deps)`，从 ImTabViewModel 上收 Client/ImTokenRepository/selfUid/connect/retry 的所有权；**断线重同步**（state 从 RECONNECTING→CONNECTED 时刷新会话列表 + 发 onResynced tick，WS 断档消息不会自动补）；暴露 `totalUnread` 给 Tab 角标；**登出时 `ImSession.shutdown()`**（MainTabScreen onSignedOut 处调用，否则下个用户继承上个用户的 socket 和缓存）。
- **新 `data/ConversationRepository.kt`**：`conversations: StateFlow`，排序照抄 web `sortConversations`（pinned 优先 + lastMessageTs 降序）；live 事件移植 `useConversations.ts`：messages→原地 bump，未知 cid→全量刷新，reads 中 **仅 `uid == selfUid`** 才清本地未读（否则别人已读会清掉自己的角标），conversationEvents→刷新；mutations：setPinned/setMuted（乐观更新）、deleteOrLeave、refresh。
- **新 `data/UserDirectory.kt`**：uid→profile 批量解析缓存（ConcurrentHashMap + in-flight 去重 + 1h TTL + version StateFlow 触发重组）。
- **新 `data/MediaResolver.kt`**：object_key→presigned GET URL 缓存（expiresAt = now+50min，懒重签）。
- **新 `data/ChatUploadRepository.kt`**：`uploadImage(uri)`（gif 透传≤10MiB；其余最长边>1600 或 >2MiB 时缩放至≤1600px + `Bitmap.compress(WEBP_LOSSY, 85)`）、`uploadFile(uri)`（OpenableColumns 取名/大小，cap 50MiB，body=JSON `{key,name,size}`）。⚠️ **presigned PUT 用裸 `OkHttpClient()`，绝不能走 authedOkHttp**（AuthInterceptor 加 Authorization 头会破坏 S3 签名）。

### 消息内容模型（二期扩展点）

新 `model/MessageContent.kt`：`sealed interface MessageContent { Text / Image(objectKey) / File(key,name,size) / Unsupported(contentType, body) }` + `MessageContentParser.parse(contentType, body)`。渲染在 `ui/chat/MessageBubble.kt` 单一 `when` 分发。二期每种新类型 = 一个子类 + 一个 parser 分支 + 一个 renderer 分支。同一 parser 驱动列表预览本地化文案（`[图片]`/`[文件] name`）。

### 导航切换（决策：应用级路由，聊天页全屏无 Tab 栏）

- AppNav 新路由：`im_chat/{cid}`、`im_group_info/{cid}`、`im_new_chat`、`im_add_members/{cid}`（cid URL-encode）。
- 消息 Tab 渲染新 `ConversationListScreen(deps, onOpenChat, onNewChat)`；**删除 `ImTabRoot.kt` / `ImTabViewModel.kt`**。

### ViewModel 拆分（沿用现有 Factory(context, deps) 模式，无 DI 框架）

- `vm/ConversationListViewModel.kt`：ConversationRepository + UserDirectory → `ConversationRowUi`（标题=直聊对端解析名/群 name，头像，预览 `发送者: 文本`，时间标签，未读，pin/mute 标识）。
- `vm/ChatViewModel.kt`（按 cid Factory）：seq 排序内存分页（初始 50 条，`beforeSeq` 向上翻页，mid 去重——自己的消息 ack+广播双到达）；live 收 messages（按 cid 过滤）；**markRead 仅在 RESUMED 时**（repeatOnLifecycle，否则后台聊天页吃掉未读）；已读回执（listReads 快照 + reads 帧单调合并，直聊显示已读/未读，群显示 n人已读）；sendText/sendImage/sendFile（先传后发，UI 挂 pending 项）；conv 事件（被移出→toast+返回，updated→刷标题）；收 onResynced→重拉最新页。内存上限 ~500 条。
- `vm/GroupInfoViewModel.kt`（按 cid）：roster（listMembers + UserDirectory）、owner 判定、加人/踢人/改名/announce-leave+退群/转让/清空记录，conversationEvents 联动刷新。

### 屏幕（feature-im/ui/）

- `list/ConversationListScreen.kt`：顶栏 + "+"、连接状态条（复用现有 ConnectionStatusBar 移到 ui/common/）、会话行（Coil 头像/首字占位、未读角标、muted 弱化）、**长按 ModalBottomSheet 菜单**（置顶/免打扰/删除或退出，owner 退群走转让-或-解散确认）。
- `chat/ChatScreen.kt`：LazyColumn `reverseLayout=true` + 顶部翻页哨兵、MessageBubble（群聊他人消息带名字头像）、自己最新消息下已读标签（群点开 `ReadReceiptSheet`）、输入栏（文本 + 附件按钮 → `PickVisualMedia` / `OpenDocument`）。
- `chat/ImageLightbox.kt`（全屏 + 捏合缩放，最简实现）、FileBubble 点击 → MediaResolver 解析 URL → `Intent.ACTION_VIEW`。
- `group/GroupInfoScreen.kt`、`newchat/NewChatScreen.kt`（ContactPicker Multi：1 人→direct→跳聊天，≥2→起名弹窗→group→跳聊天）、`newchat/AddMembersScreen.kt`（exclude 现有成员——roster 是 IM uid，需先经 UserDirectory 换成 we-meet id）。

### Coil 缓存策略（feature-im 加 coil 依赖）

聊天图片 `memoryCacheKey/diskCacheKey = objectKey`（对象不可变，重签 URL 仍命中）；头像 key = URL 去 query（`substringBefore('?')`）或 `avatar:$userId`，与 core-directory 保持一致。

## M3 — IM 收尾（依赖 M2）

图片/文件消息、已读回执 UI、群管理界面、新建会话流已在 M2 结构中列出——M2 先落地"文本消息 + 列表完善 + 导航切换"这个可验收切面，M3 补齐 image/file 上传渲染、ReadReceiptSheet、GroupInfo 全操作、NewChat/AddMembers。（M2/M3 是同一结构的两个验收批次，边界可按实际进度调整。）

## M4 — 通讯录（:app/ui/contacts/）

- `ContactsTabScreen.kt` + `ContactsViewModel`（HOME back-stack entry 作用域，切 Tab 状态不丢）：顶部标题+内联搜索框（非空进搜索模式，debounce 300ms）；浏览模式 = 面包屑（组织 > 部门 > 子部门，可点回退）+ 当前节点子部门行 + 成员行（include_subtree）；根级显示全部成员；`BackHandler(deptStack.isNotEmpty())` 逐级返回；空态/错误重试。
- `MemberDetailScreen.kt`（路由 `member_detail/{userId}`）+ VM：大头像、名字、职务、部门、邮箱、「发消息」按钮（`is_self` 隐藏）→ `POST api/v1.0/im/conversations/direct/ {peer_user_id}`（新 `app/data/api/ImBridgeApi.kt`，与 feature-im 的 peer_uid 调用点分开）→ 成功后 `navigate(Routes.imChat(cid))`。
- 部门下钻用 Tab 内本地状态（不占路由，Tab 栏保持可见，对齐飞书）。

## M5 — 日历（:app/ui/calendar/）

视图决策：**月历网格 + 选中日 agenda 列表 + FAB**（API 无日期范围过滤、分桶本来就在客户端；自绘时间网格成本高收益低）。

- `data/api/CalendarApi.kt` + `dto/CalendarDtos.kt`（字段对照 web `ApiCalendar.ts`，全部 nullable/默认值）：`GET/POST calendar-events/`、`GET {id}/`、`POST {id}/rsvp/`；PATCH/DELETE 声明为 stretch（仅 organizer 菜单，可砍）。`ApiClient` 加 `calendarApi`/`imBridgeApi`。
- `CalendarModels.kt`：`OffsetDateTime.parse(...).toInstant()` → 设备时区 ZonedDateTime 显示；**全天事件日期计算用 `event.timezone`**（否则跨时区 ±1 天）；跨天事件展开到覆盖的每一天，全天置顶。
- `CalendarTabScreen.kt` + VM：翻页拉全量（cap 5 页/500 条）；月网格（6×7 Box，今日圈、选中实心、≤3 事件点）+ 今天按钮 + agenda（时间列 + 标题 + RSVP 标识 + 有 room_slug 显示摄像头图标）；FAB → `create_event?epochDay=`；`LifecycleResumeEffect` 回到页面时刷新（create/detail pop 后自动生效）。
- `CreateEventScreen.kt`：标题/描述/全天 Switch/M3 DatePickerDialog + 自包 TimePicker 弹窗（M3 无 TimePickerDialog，~20 行包装）/提醒下拉（无/0/5/10/15/30/60/1440 分钟，默认 10）/参与人 chips + **ContactPicker(Multi)**；校验 end>start；全天按 web 惯例发独占次日午夜。
- `EventDetailScreen.kt`（路由）：标题（cancelled 划线）、完整时间、组织者、描述、提醒、参与人 RSVP 列表；**RSVP SegmentedButton**（接受/待定/拒绝，organizer 隐藏，乐观更新失败回滚）；**「加入会议」**（room_slug 非空）→ 现有 `Routes.joinPreview(slug)` 流程。

## M6 — 字符串 + 验证

### 字符串（5 locale：values, -de, -fr, -nl, -zh-rCN；zh-rCN 定基调）

- :app：`tab_calendar`、`tab_contacts`、`profile_ai_entry`、contacts_*（~12 键）、calendar_*/event_*（~28 键）。
- :core-directory：picker_*（~6 键）。
- :feature-im：~40 新键（预览占位、长按菜单、群信息、已读回执 `%1$d人已读`、上传错误、新建会话），删除废弃的 raw-uid 弹窗键。

### 验证

构建：`./gradlew :app:assembleDebug`（JDK 17；加 `:core-directory` 后首次构建 config-cache 失效会慢）；SDK 测试在 jusi-light-im 仓库 `./gradlew test`。`adb install -r app/build/outputs/apk/debug/app-debug.apk`。

手工脚本（真机 ×2 或 真机+Web，连 meet.we-meet.online）：
1. **Tab**：5 Tab 顺序正确，默认落会议页，原会议流程无回归；我的→AI 助手→打电话链路通；Web 给自己发消息、人在会议 Tab → 消息角标 +1。
2. **IM**：列表显示真名/头像/预览/时间；断网恢复→状态条循环+重同步；Web↔App 互发文本/图片（>2MiB JPEG 验证 webp 压缩、gif 动图）/文件（50MiB+ 被拒）；300+ 条会话向上翻页无重复；直聊已读标签实时翻转、群 n人已读与名单一致；建群（发首条消息前两端就显示群名——conv 帧）、加人/踢人（被踢端实时消失）/改名/转让/退群/解散；置顶/免打扰/删除生效；挂机超过 IM token TTL 后静默重连。
3. **通讯录**：部门两级下钻+面包屑+系统返回逐级；搜索防抖过滤；成员详情→发消息→直达聊天页→发送→Web 可见；空部门组织不崩；头像闲置 10min+ 后仍能渲染（presign 过期 vs 缓存）。
4. **日历**：Web 建的事件在 App 显示点+agenda（双端时区一致）；App 创建带参与人+提醒 10 分钟→Web 可见、参与人账号可 RSVP→组织者端可见；关联会议一键入会→进房→离开；无 room_slug 不显示入会按钮；跨天/全天事件覆盖天都出现。

### 主要风险

- **双仓配对**：SDK 分支先落地，App 侧 `ConversationSummary.copy(...)` 调用点靠默认值保持源兼容；两边 PR 注明配对关系。
- **presigned URL 三坑**：Coil 必须用稳定 cache key；PUT 不能走 authedOkHttp；URL 不持久化。
- **Moshi 反射**：新 DTO 除 id 外全部 nullable/默认值。
- **重连补偿是必需品不是润色**：ImSession 的 resync（列表刷新+最新页重拉+reads 快照）。
- **与 MainTabScreen/ImDeps 的改动集中在 M1/M2 前期**，避免自我冲突；`/calendar-events/` 无范围过滤（cap 500，后端 follow-up）。
