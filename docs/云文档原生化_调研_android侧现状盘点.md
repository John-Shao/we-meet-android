# we-meet-android 云文档原生化 —— 现状盘点(调研稿)

> 调研日期:2026-09-06 · 支撑文档:`docs/云文档模块原生化_可行性评估与设计方案.md`
> 路径均相对 we-meet-android/。本文只记录事实(带文件路径引用),不写建议。

## 1. 模块与构建

- 模块清单(settings.gradle.kts):`:app`、`:core-design`、`:core-directory`、`:feature-assistant`、`:feature-im`;另有 composite build 引入 `../jusi-light-im/sdk/android`,`dependencySubstitution` 把 `com.jusi.lightim:sdk-im` 替换为本地 `:sdk-im`(settings.gradle.kts:42-45;SDK 项目位于同 workspace `we-meet/jusi-light-im/`)。
- buildSrc:仅 kotlin-dsl + `DesignLintTask.kt`(无独立 convention plugin;根 `build.gradle.kts:10-36` 注册 `checkDesignTokens`,正则扫裸色值/裸 dp/中文字面量,与 `config/design-lint-baseline.txt` 比增量)。
- 关键版本(gradle/libs.versions.toml):AGP 8.7.3、Kotlin 2.0.21、Compose BOM 2024.10.01、navigation 2.8.4、lifecycle 2.8.7、coroutines 1.9.0、retrofit 2.11.0 + okhttp 4.12.0 + moshi 1.15.1(无 ktor)、coil 2.7.0、livekit 2.24.1/compose 2.2.1、Getui gtsdk 3.3.15.0/gtc 3.3.3.0、security-crypto 1.1.0-alpha06、camerax 1.4.0。
- compileSdk/targetSdk=34,minSdk=29(app/build.gradle.kts:48-50,由 24 提到 29 是为 AI 通话 audio/WebRTC 能力)。
- DI:**无任何框架**(无 Hilt/Koin/Dagger)。`WeMeetApp` 是服务定位器(WeMeetApp.kt:35 注释自述不用 DI 框架);feature 模块经 `Deps` 接口注入(AssistantDeps/ImDeps/DirectoryDeps/CallHost);ViewModel 用 `Provider.Factory`。
- 持久化:无 Room、无 DataStore。EncryptedSharedPreferences(TokenStore)、SharedPreferences(SettingsStore/ContactPrefs/DeletedMessageStore/ImInputStateStore/AiCallPreferences)、JSON 文件(HistoryStore)、内存 StateFlow(UserDirectory/GroupAvatarDirectory/MediaResolver)。

## 2. core-design

- 包结构:`com.we.meet.ui.theme`(Color/Type/Dimens/Shape/Theme)+ `com.we.meet.ui.components`(WeMeetTopBar/StateViews/Buttons/DestructiveConfirmDialog);namespace `com.we.meet.design`。
- 主题:M3 全部 role 显式声明(Seed 0xFF3370FF,Light/Dark);语义 token `WeMeetExtras`(Theme.kt:102,CompositionLocal):status/room/im(通话红绿 E5484D/30A46C)、aiCall、calendar(+RSVP 四态、对 Web EventRsvpStatus)、三套头像调色板(顺序跨端契约);`WeMeetTheme.isDark` 供 docs WebView 读深浅;`JusiTypography` 15 档对 Web typography.tokens.json + `WeMeetTextStyles`;Dimens 8dp 网格与 Room/Calendar/Chat/Task 分组;Shape 为 M3 圆角映射。
- 组件:统一 TopBar、三态全家桶(Loading/Empty/Error 各整屏 + inline)、Primary/Secondary/DangerButton、DestructiveConfirmDialog;**没有**统一 List item / Badge / Avatar / Sheet / Dialog(会话行、群头像在 feature-im/ui/common、core-directory/ui/MemberAvatar.kt 自建)。
- 纯 UI 模块(仅依赖 Compose);规范在根 `docs/设计规范.md`(§1 定 token 唯一真源)。app 用 `api()` 依赖,三个 feature/core 模块用 `implementation`。

## 3. feature-im 与 feature-assistant

- feature-im:`net/ImNetwork`(宿主 authedOkHttp 自建 Retrofit)、`data/`(ImApi/ImDtos/ImBridgeRepository/ConversationRepository/UserDirectory(1h TTL)/GroupAvatarDirectory/MediaResolver(~50min)/ChatUploadRepository/DeletedMessageStore/ImInputStateStore/DocsApi(GET /api/v1.0/docs/my-documents/))、`model/`(MessageContent sealed、RichText、RichCard、CardState、MentionAliases)、`vm/`(ConversationList/Chat/GroupInfo/GroupBots/DirectChatSettingsViewModel)、`ui/{list,chat,group,newchat,search,bot,call,common}`、进程级 `ImSession`(jusi Client、totalUnread、shutdown)。
- 实时:jusi-light-im SDK OkHttp WebSocket(`ws/WsClient.kt`,`?token=`;ImSession.kt:77-92 独立 OkHttp、pingInterval 20s、Backoff);REST 桥接;SSE 在 app 层 `data/api/GlobalAskSse.kt`(POST /api/v1.0/search/ask-stream/,callbackFlow 读 data:)。
- 列表页范式:`ConversationRepository` 内存 StateFlow 由 WS 帧驱动(bump/刷新/仅 uid==self 清未读),排序 pinned→lastMessageTs;`ChatViewModel` ChatUiState + LazyColumn reverseLayout 内存分页(50 条/页、上限 ~500)、markRead 仅 RESUMED、onResynced 补偿;统一 `MutableStateFlow<UiState>` + sealed Event + Factory,`collectAsStateWithLifecycle`。
- 本地化:5 套 locale 各 356 键。
- feature-assistant:`aicall/{data,model,ui,vm}`(AiAgentApi/AiAgentRepository/AiRoomRepository/RoomApi/AiCallPreferences;AssistantCallScreen/AiSettingsSheet/components AnimatedSphere/BottomControls/VideoPreview;AiCallViewModel);实时为 LiveKit 音频(建房→LiveKit.create 直连→POST /start-ai-agent/ 用 LiveKit token 鉴权),非 WS/SSE。

## 4. 主界面与导航

- `MainTabScreen.kt`:`enum MainTab { Messages, Calendar, Meeting, Contacts, Docs, Tasks }`(L96);TabItem(labelRes, icons, badgeCount, content);CompactTabBar(testTag main-tab-*);两个 ModalNavigationDrawer(资料页 + 任务导航抽屉)。
- 云文档 tab:WebView 实例提升(L160-161 `remember createDocsWebView(deferInitialLoad=true)`,`LaunchedEffect loadDocsTabEntry` 预加载 L162,登出 destroy L163-168);桥接挂在 MainTabScreen 层(L174-191):onShareDoc→ForwardPicker、onEmbedHello→replyDocsHostHello(features=[global-search,shell-nav],L593-602)、onOpenSearch→全局搜索;深浅切换注入 wemeet-theme(L194-200);分享到聊天(L518-561):ForwardPicker→sendMessage(doc-card)→grantDocAccess→wemeet-doc-access-updated。
- `AppNav.kt`:Routes+NavHost,路由含 login/home、settings 族、preview/room/waiting_room/history_detail/scheduled_detail/qr_scan、IM 组 11 条、member_detail/starred_contacts/my_groups、日历组 9 条、ai_hub/approval、`docs_viewer/{url}`(L945-954);深链消费 pendingJoinSlug/pendingChatCid/pendingCalendarShareToken;全局 SessionExpiredDialog。
- 原生模块风格(单 Activity + Navigation + VM/StateFlow + WeMeetTopBar + 三态组件):`calendar/`(CalendarTabScreen 月网格+agenda+FAB、CreateEventScreen、EventDetailScreen、FreeBusyCompareScreen、CalendarManagementScreens、CalendarShareScreen、CalendarOwnerShareScreen、CalendarSettingsScreen、reminder/ReminderScreen、views/ 8 个网格视图);`tasks/`(TaskScreen 单文件约 5900 行页内子页 TaskListPage/TaskDetailPage 等、TaskViewModel、TaskNavController 抽屉、TaskSettingsScreen、ConversationTasksSheet);`contacts/`(ContactsTabScreen 部门树、MemberDetailScreen、StarredContactsScreen、ExternalContactsSheet、ContactsViewModel);`meetingroom/`(MeetingRoomPicker、MeetingRoomsCalendarScreen+VM、MeetingRoomOverview、MeetingRoomTimeline、MeetingRoomBuildingPicker)。

## 5. 数据层

- `ApiClient`(app 级单例;独立 refreshOkHttp 防死锁 + 主 OkHttp InMemoryCookieJar/AuthInterceptor/SessionExpiredInterceptor/TokenRefreshAuthenticator;Moshi;12 个 API:auth/room/user/qrLogin/imBridge/calendar/meetingRoom/approval/push/search/docs/task + keycloakOidc + okHttp);dto 按域组织。
- `auth/`:TokenStore(EncryptedSharedPreferences 含 authFlow 标记)、AuthInterceptor(Bearer + No-Auth)、TokenRefreshAuthenticator(按 authFlow)、KeycloakOidc(PKCE,独立裸 OkHttp)、SessionExpiredInterceptor + SessionState、InMemoryCookieJar。
- `repository/`:Auth/Profile/Room/RoomAi/MeetingDetail/QrLogin/Task;`settings/SettingsStore`;`history/HistoryStore`(JSON 200 条);`analytics`(PostHog)。
- base URL:gradle.properties `WE_MEET_BASE_URL/KEYCLOAK_URL/DOCS_URL/OIDC_CLIENT_ID/WEB_LOGIN/GETUI_*` → app/build.gradle.kts `cfg()`(local.properties 优先)→ BuildConfig(含 JUSI_IM_BASE_URL 默认 https://im.we-meet.online)与 manifestPlaceholders。
- 推送:Getui(`push/WeMeetPushService` 空壳 :pushservice 进程、WeMeetGtIntentService、PushTokenUploader、DeviceTimezoneReporter、CallNotifier;WeMeetApp.initGetuiPush try/catch 降级)。
- 深链:MainActivity.handleDeepLink(`wemeet://im?cid=`、`wemeet://call?payload=`、`https://host/calendar/…`、`https://host/<8位数字>` DEEP_LINK_SLUG_REGEX)三类 intent-filter。

## 6. 云文档现状(重点)

- `ui/docs/` 仅两个文件:`DocsScreen.kt`(565 行)+ `DocsViewerScreen.kt`(96 行)。
- `DocsScreen.kt`:
  - `EMBED_UA_MARKER = "WeMeetApp/1.0 (embedded-docs)"`(L53,与 we-meet-docs `useIsEmbedded.tsx` 同步);
  - `DocsWebViewClient`(L64-190):UI 钩子 onHistoryChanged/onLoadingChanged/onMainFrameError/onShareDoc/onEmbedHello/onOpenSearch/onPanelState + client 状态字段 isLoading/everLoaded/leftPanelOpen(消除预加载竞态);拦截逻辑(L137-164):非 http(s) 且手势→外开浏览器;内部 host(docs+keycloak+meet 三 host 及注册域 INTERNAL_HOSTS/INTERNAL_DOMAINS L546-565)留 WebView;无手势跳转一律留内;http 错误/主 frame 错误分别记日志/触发错误态;
  - `DocsHostBridge`(L211-260):`addJavascriptInterface "WeMeetHost"`,仅一个 `postEvent(String)`(L268-271 明文:不要加新方法,扩展一律加 type);回调跳主线程后校验当前页 host 必须等于 docs 自己(防钓鱼);已处理 type:`wemeet-share-doc(docId/title/url)`、`wemeet-embed-hello`、`wemeet-open-search`、`wemeet-panel-state(leftPanelOpen/rightPanelOpen)`;未知 type 静默忽略;
  - `postToDocs` 原生→网页用 evaluateJavascript postMessage;原生发出 type:`wemeet-host-hello(protocol=1,platform=app,features=[global-search,shell-nav])`、`wemeet-theme(dark/light)`、`wemeet-doc-access-updated(docId)`、`wemeet-ui-command(close-left-panel)`;
  - `createDocsWebView`(L277-344):固定 MATCH_PARENT、UA 追加 `; theme=light|dark`(docs embedderTheme() 解析;?embed=1 死于重定向链)、WebViewClient 必须在 loadUrl 前设置、可选 deferInitialLoad;
  - 会话引导:`docsEntryUrl`(L356-362,POST api/v1.0/docs/session/ 换 60 秒单次票据,失败回退老入口)、`loadDocsTabEntry`(L370-377,next=/?embed=1&chrome=full&lang=…,Main.immediate)、`loadDocsDeepLinkEntry`(L388-392,chrome=none 阅读态 + 相对路径)、`docsUrl()`(authenticate?returnTo= 兜底)、`appLanguageTag()`(应用内语言小写化);
  - `DocsTabScreen`(L458-531):BackHandler 三级(关抽屉 postMessage close-left-panel→goBack→放行)、imePadding、DocsLoadStateOverlay(WeMeetLoading 仅首载 / WeMeetErrorState 重试)。
- `DocsViewerScreen.kt`:搜索命中/聊天 doc-card 查看器,独立 WebView(复用 createDocsWebView),Scaffold + WeMeetTopBar,退出即 destroy。
- 其它触点:`feature-im/ui/chat/DocCardBubble.kt`、`DocPickerDialog.kt`、`MessageSearchScreen.kt` 文档源(app/data/api/SearchApi.kt `/api/v1.0/docs/search/`);`feature-im/data/DocsApi.kt`(my-documents 代理)。

## 7. 字符串 / 本地化

- 5 套 locale 键数对齐:values(英)、values-zh-rCN、values-de、values-fr、values-nl;app 895、feature-im 356、feature-assistant 46、core-directory 6、core-design 4。
- 规范(docs/设计规范.md §4/§5 + DesignLintTask):所有用户可见文字进 strings.xml 中英双份、禁中文字面量、空态/错误态/破坏性弹窗文案规则、`cd_` 前缀 contentDescription、`// i18n-exempt` 例外(语言选择器、发给对方的消息体)。

## 8. 前置工作

- `docs/app-ux-device-matrix.md`:真机验收矩阵(字体缩放/横竖屏/折叠屏/insets/会中专项)。
- `docs/设计规范.md`:M3 + WCAG 2.2 AA、token 唯一真源(core-design 四文件)、组件/文案/无障碍/走查清单、DesignLintTask 棘轮、跨端强耦合点。
- `docs/对齐Web端_IM通讯录日历_实施方案.md`:二期 M0-M6 已落地(2026-07-02,b4201ba/32dac0e;ContactPicker 放 :core-directory、ImSession 进程级单例、SDK alpha.1→alpha.7 等),但其 Tab 决策「消息·日历·会议·通讯录·我的」与当前代码(消息/日历/会议/通讯录/云文档/任务)不一致,文档落后。
- ★搜索「原生 docs / 云文档 原生」:仓库内(注释 + md)未找到任何把云文档改为原生渲染的 TODO/计划/设计;现有 docs 设计全为 WebView 嵌入方案(we-meet 仓库 docs/phases/p3-docs-app.md、p3c-docs-session-bootstrap.md、docs/extensions/云文档登录态_部署步骤.md);注释中「原生」均指直连后端。

## 9. 其它约束与数量

- minSdk 实际 **29**(Android 10+),非 README/CLAUDE.md 所记 24 —— 以 app/build.gradle.kts 为准,无老设备兼容问题。
- WebView 相关代码量:云文档核心 `DocsScreen` 565 + `DocsViewerScreen` 96 ≈ 660 行;登录 `WebLoginScreen` 306 行;`MainTabScreen` 集成约 90 行;`AppNav` docs_viewer 约 25 行;会话/搜索 API 82 行;IM 侧 doc-card/选择器/搜索约 320 行 —— 合计约 1400 行直接相关,其中加载/桥接/会话引导约 780 行。
- 列表→详情闭环范式:通讯录 ContactsTabScreen→MemberDetailScreen、日历 CalendarTabScreen→EventDetailScreen(+Create/FreeBusy/Management)、会议 HomeScreen→HistoryDetailScreen/ScheduledDetailScreen、IM ConversationListScreen→ChatScreen(群机器人 4 路由闭环)、会议室→日程创建(meetingRoomId 预填);任务模块例外(TaskScreen 页内子页非路由)。
