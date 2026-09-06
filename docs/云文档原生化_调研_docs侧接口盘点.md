# we-meet-docs（La Suite Docs 分支）云文档模块分析报告

仓库：`D:\workspace\we-meet\we-meet-docs`（upstream = github.com/suitenumerique/docs，origin = John-Shao/we-meet-docs，本 fork 当前版本 app-impress 5.4.1）。路径均相对仓库根。

## A. 后端（src/backend，Django 5 + DRF，app 包名 impress/core）

### A1. Document 模型与存储（src/backend/core/models.py:935）
- 继承 `MP_Node`(treebeard) + `BaseModel`（UUID 主键、created_at/updated_at，models.py:74、935）。树字段：`path`(CharField(252), unique, db_collation="C")、`steplen=7`、字母表 base62、`numchild`、`node_order_by=[]`（手动排序，models.py:976-982）。`DocumentManager` 默认按 path 排序。
- 业务字段：`title`(255, null)、`excerpt`(300)、`link_reach`(restricted/public/authenticated, 默认 restricted)、`link_role`(reader/commenter/editor, 默认 reader)、`creator` FK(User, RESTRICT, null)、软删除 `deleted_at`/`ancestors_deleted_at`、`has_deleted_children`、`duplicated_from`、`attachments` ArrayField（S3 key 列表）（models.py:938-972）。
- 内容存储：S3（django-storages S3Storage，settings.py:159），`file_key = "{pk}/file"`（models.py:1058）；`content` 属性懒读 S3 并缓存，`save_content` 用 ETag/MD5 比对决定是否写（models.py:1015-1072）。**注意：内容是 base64 编码的 Yjs 二进制更新（非 JSON）**——前端 `toBase64(Y.encodeStateAsUpdate(yDoc))`（src/frontend/apps/impress/src/features/docs/doc-editor/hook/useSaveDoc.tsx:97），后端 `DocumentContentSerializer` 校验 base64（core/api/serializers.py:318-325）。
- 版本：**无 DocumentVersion 模型**；版本=S3 对象版本（bucket versioning），`get_versions_slice` 走 `list_object_versions`（models.py:1092-1148），`delete_version`/`versions_detail` 对应 GET/DELETE。
- 软删/恢复：`soft_delete`/`restore`（models.py:1484-1558），`TRASHBIN_CUTOFF_DAYS` 之后不可恢复；DB 约束 `check_deleted_at_matches_ancestors_deleted_at_when_set`（models.py:990-998）。
- 收藏：`DocumentFavorite`(document,user 唯一) models.py:1593；列表用 annotate is_favorite。
- 链接分享：`link_reach/link_role` 与祖先合成 `computed_link_reach/role`（`compute_ancestors_links_paths_mapping`、`get_equivalent_link_definition`，models.py:1231-1306, core/choices.py:95）；子文档不可比父更宽。
- `get_abilities(user)`（models.py:1308-1418）返回能力字典：accesses_manage/view、ai_proxy/transform/translate、attachment_upload、can_edit、children_list/create、collaboration_auth、comment、content_patch/retrieve、cors_proxy、destroy、duplicate、favorite、link_configuration、invite_owner、leave、move、partial_update、restore、retrieve、media_auth/check、tree、update、versions_destroy/list/retrieve、search、formatted_content 等。
- excerpt：**未找到生成逻辑**——仅模型字段+序列化透传（serializers.py:104 等），`core/management/commands/clean_document.py:104` 会置空，代码中无任何回填/计算点。

### A2. 权限与协作模型
- `DocumentAccess`（models.py:1624，BaseAccess:829）：user 或 team(字符串) + role，unique(user,document)/unique(team,document)；**按 path 前缀继承**：`get_role`/roles 例 `document__path=Left(path, Length(...))`（models.py:1205-1218）。角色 `RoleChoices`：reader/commenter/editor/administrator/owner（choices.py:46），特权=admin+owner。`DocumentAccess.get_abilities` 另算 set_role_to（models.py:1736-1787）。
- `Invitation`：email+document 唯一、role、issuer、`is_expired`（INVITATION_VALIDITY_DURATION，models.py:2037-2090）；email 已注册则拒绝。**invitation→access 转换**：`User.save()` 新建用户时 `_convert_valid_invitations()` 转 DocumentAccess（models.py:344-357），即受邀人首次登录时生效；`create-for-owner` 对不存在用户建 Invitation（owner）。
- `Thread`（document, creator, resolved/resolved_at/resolved_by, metadata JSON）models.py:1888；`Comment`（thread, user null, body JSON, metadata）models.py:1953；`Reaction`（comment, emoji, users M2M）models.py:2002。
- **锚定方式：不在 DB**。锚定是 Yjs 文档内的 BlockNote "comment" mark（threadId），前端 `setMark('comment', {threadId})`（DocsThreadStore.tsx:232）；DB 只存正文 body 与 thread.metadata；客户端另传 prosemirror/yjs 位置（DocsThreadStore.tsx:210-222）。协同刷新走 awareness ping（`commentsPing`，DocsThreadStore.tsx:63-130）+ REST 补拉。
- `UserReconciliation`（models.py:418）：双邮箱确认后把 access/favorite/linktrace/评论迁给 active 用户。
- `LinkTrace`（models.py:1561）：链接访问留痕，用于把"非受限链接读过的文档"出现在列表。

### A3. REST API（core/urls.py + core/api/viewsets.py + serializers.py）
前缀 `/api/v1.0/`（`API_VERSION`；核心视图 `core/api/viewsets.py`）。

| 端点 | 方法 | 要点 |
|---|---|---|
| documents/ | GET/POST | list 过滤 `is_creator_me/title/q/is_favorite`，ordering=created_at/updated_at/title，只返回"最高可达祖先"；POST 建根文档（creator=OWNER access，可带 file 导入） |
| documents/{id}/ | GET/PUT/PATCH/DELETE | retrieve 写 LinkTrace；update 带 `websocket` 布尔且受 `COLLABORATION_WS_NOT_CONNECTED_READ_ONLY` 限制；DELETE=软删 |
| documents/{id}/children/ | GET/POST | 分页子列表 / 建子文档（add_child） |
| documents/{id}/tree/ | GET | 返回**嵌套树**（祖先到最高可读层 + 一层子级，`utils.nest_tree`，viewsets.py:1470-1563） |
| documents/all/ | GET | 全树（含后代）默认关闭（DOCUMENT_ALL_ENDPOINT_ENABLED） |
| documents/{id}/move/ | POST | `{target_document_id, position: first-child/last-child/left/right}`；跨根/成根时清 accesses+invitations（viewsets.py:1206-1319） |
| documents/{id}/duplicate/ | POST | `with_accesses`/`with_descendants` |
| documents/{id}/restore/ | POST | 软删恢复 |
| documents/trashbin/ | GET | 自己 owner 化、保质期内 |
| documents/{id}/favorite/ | POST/DELETE | 收藏/取消；documents/favorite_list/ GET |
| documents/{id}/link-configuration/ | PUT | link_reach/link_role（权限限制继承） |
| documents/{id}/versions/、versions/{version_id}/ | GET/GET/DELETE | S3 version；仅能看到获得 access 之后版本 |
| documents/{id}/content/ | GET（流式+ETag/304）/PATCH | GET 返回 base64 文本流；PATCH 入参 `{content(base64), websocket}` |
| documents/{id}/attachment-upload/ | POST multipart(file) | 返回 media-check URL；S3 metadata status=processing→ready（malware 检测回调） |
| documents/media-auth/ | GET | nginx subrequest 鉴权（MEDIA_AUTH_ORIGINAL_URL_HEADER）并回传 S3 签名头 |
| documents/{id}/media-check/ | GET | `?key=` 返回 processing/ready+file |
| documents/{id}/can-edit/ | GET | `{"can_edit":bool}`（无连接时只读锁） |
| documents/{id}/formatted-content/ | GET | `content_format=json/markdown/html`，经 y-provider 转换 |
| documents/{id}/cors-proxy/ | GET | `?url=` 代理图片（防 SSRF） |
| documents/{id}/ai-proxy/、ai-transform/、ai-translate/ | POST | 前者 SSE（Vercel AI SDK stream）；后两者 `{text, action}/{text, language}` |
| documents/search/ | GET | `q`、`document`(可选)；Find 索引器或 DB 标题回退 |
| documents/create-for-owner/、create-table-for-owner/ | POST s2s | markdown/表格文档代建（Converter 转 Yjs） |
| documents/search-for-user/、list-for-user/、grant-access-for-users/ | GET/GET/POST s2s | **we-meet 新增**：按 sub 定位用户的标题搜索/最近列表/批量只读授权 |
| documents/{id}/accesses/ | GET/POST/PUT/PATCH/DELETE | POST `{user_id|team, role}`，owner 赋权校验+邮件；非特权用户只见特权 access（DocumentAccessLightSerializer） |
| documents/{id}/invitations/ | CRUD | email(小写)+role，invite owner 校验 |
| documents/{id}/threads/、{thread_id}/ | GET/POST/PATCH/DELETE | 平铺 ThreadSerializer：`body`(写)→首条 Comment；resolve/unresolve POST |
| …/threads/{tid}/comments/、{cid}/、reactions/ | CRUD/POST/DELETE(emoji) | CommentSerializer(含 reactions、abilities) |
| documents/{id}/ask-for-access/ | GET/POST/create…/accept | POST 建请求（可指定 role≤editor），accept 转 access 发邮件 |
| documents/{id}/leave/ | POST | 删除自身 access+linktrace |
| users/ | GET | `?q=`（min_length 配置）+`document_id=` 排除；邮箱用 levenshtein≤3，其他用 pg_trgm 相似度>0.2 且限"共享过/同域"（防枚举），上限 `API_USERS_LIST_LIMIT`；节流 burst 30/min、sustained 180/h |
| users/{id}/、users/me/、users/onboarding-done/ | PATCH/GET/POST | me=当前用户（IsSelf）；PATCH 可改 language |
| users/session-ticket/ | POST s2s | **we-meet 新增**：`{sub,email?,full_name?,short_name?,language?}`→`{ticket, expires_in:60}`（core/api/viewsets.py:367-402) |
| session-from-ticket/ | GET | **we-meet 新增**：`?ticket=&next=` 单次票据换 Docs 会话并 302（core/api/session_bootstrap.py，缓存存 sha256，同域下写 docs_sessionid；无 KC 会话的 WebView 用） |
| config/ | GET | AllowAny：COLLABORATION_WS_URL、LANGUAGES、TRASHBIN_CUTOFF_DAYS、AI_*、FRONTEND_*、RELEASE_VERSION、theme_customization 等 |
| authenticate/、callback/、logout/ | GET | lasuite.oidc_login（Keycloak 浏览器会话引导，`core/urls.py:6`，DrfSpectacular schema） |

- 序列化字段（ListDocumentSerializer，serializers.py:81）：id、abilities、ancestors_link_reach/role、computed_link_reach/role、created_at、creator、deleted_at(=ancestors_deleted_at)、depth、excerpt、is_favorite、link_role/reach、nb_accesses_ancestors/direct、numchild、path、title、updated_at、user_role；SearchDocumentSerializer 追加 `parent`。Search 结果 `SearchDocumentSerializer`；版本响应 `{versions:[{etag,is_latest,last_modified,version_id}], next_version_id_marker, is_truncated, count}`。
- 分页/过滤：PageNumberPagination（settings PAGE_SIZE=20；自定义 Pagination max 200，page_size 参数）；django_filters：`title`/`q`→`title__unaccent__icontains`（Postgres unaccent，filters.py:42-56）；`is_creator_me`/`is_favorite` 布尔；**搜索后端：无 meilisearch、无 tsvector**——全文走 La Suite "Find" 索引器（core/services/search_indexers.py，waffle flag `flag_find_hybrid_search`/`flag_find_full_text_search` 决定 hybrid/full-text/title，settings SEARCH_INDEXER_CLASS/SEARCH_URL），未配置或不可达时回退 DB 标题搜索。

### A4. 认证
- settings.py:413-416：`DEFAULT_AUTHENTICATION_CLASSES = ("core.authentication.backends.SessionAuthentication",)`——全部主 API 走 **session cookie**（docs_sessionid）。前端 fetchAPI `credentials:'include'` + 从 `csrftoken` cookie 读值放在 `X-CSRFToken`（sites/impress/src/api/fetchApi.ts:14-29, api/utils.ts:40）。DRF SessionAuthentication 对非安全方法并行 CSRF 校验。匿名可访问 public link_reach 文档（DocumentPermission per abilities）。
- `ServerToServerAuthentication`（core/authentication/__init__.py）：Authorization: Bearer <settings.SERVER_TO_SERVER_API_TOKENS 之一>，不产生用户；用于：users/session-ticket、documents/create-for-owner、create-table-for-owner、search-for-user、list-for-user、grant-access-for-users（viewsets.py 各 action 的 `authentication_classes=[...]`）。
- external_api：`lasuite.oidc_resource_server` 的 `ResourceServerAuthentication`（加密 JWE JWT：A256GCM/RSA-OAEP/ES256，settings.py:775-802）。端点 `/external_api/v1.0/documents/`(list/retrieve/create/children，按 `EXTERNAL_API.documents.actions` 配置)、`/external_api/v1.0/users/`(仅 get_me、users.me 的 s2s 版)；document_access/invitation 默认 disabled（settings.py:810-836, core/external_api/viewsets.py）。`/api/v1.0/` 下另挂 lasuite.oidc_resource_server.urls（OIDC_RS_PRIVATE_KEY_STR 时，含 JWT 校验/兑换端点）。
- y-provider 调后端时转发浏览器 Cookie + `X-Y-Provider-Key` 头（src/frontend/servers/y-provider/src/api/collaborationBackend.ts:59-68），鉴权仍是 cookie。

### A5. 其它后端能力
- 转换：`Converter`（core/services/converter_services.py）→ HTTP 调 y-provider `/api/convert/`（markdown/blocknote ↔ Yjs、Yjs→markdown/html/json），DOCX→BlockNote 走 `DOCSPEC_API_URL`；无 pandoc。
- 全文搜索：见 A3（Find + waffle；无 meilisearch/tsvector）。
- 缩略图：easy_thumbnails 在 INSTALLED_APPS + THUMBNAIL_* 设置（settings.py:390,583-587），**生产代码未找到使用**。
- Celery（impress/celery_app.py + core/tasks/）：access.reset_service_connections_in_cascade（权限变更后踢 WS 重连）、mail.send_ask_for_access_mail、search.document_indexer_task/batch_document_indexer_task/trigger_batch_document_indexer、user_reconciliation.csv 导入（core/tasks/*.py）。
- AI：settings AI_FEATURE_ENABLED/BLOCKNOTE(AGPL, @blocknote/xl-ai)/LEGACY(MIT, core/services/ai_services/legacy.py)；`MISTRAL_SDK_*` 与 `OPENAI_SDK_*` 互斥（settings.py:1219）；用于 ai-proxy(SSE)/ai-transform/ai-translate + 前端 BlockNote AI 菜单。

## B. 协同服务（src/frontend/servers/y-provider）

### B1. 构成
- 单进程 Express + express-ws + Hocuspocus（`start-server.ts` 监听 `PORT` 默认 4444）。`appServer.ts`：WS 路由 `GET /collaboration/ws/`（app.ws，wsSecurity 先校验 Origin∈COLLABORATION_SERVER_ORIGIN 且带 cookie）；HTTP：`POST /collaboration/api/reset-connections/`、`GET /collaboration/api/get-connections/`、`POST /api/convert/`、`GET /ping`（routes.ts）。HTTP 用 raw token 于 `Authorization` 头（httpSecurity，secret 或 Y_PROVIDER_API_KEY）。
- `hocuspocusServer.ts`：`onConnect` 校验 `documentName === requestParameters.room` 且为 UUID v4；回调后端 `GET /api/v1.0/documents/{id}/`（转发 cookie/origin + X-Y-Provider-Key），要求 `abilities.retrieve`，`connectionConfig.readOnly = !abilities.update`；从 `docs_sessionid` cookie 取 context.sessionKey；`GET /api/v1.0/users/me/` 取 userId（失败忽略，允许匿名连 public 文档）。
- WS URL（前端 `useCollaborationUrl.tsx`）：`${COLLABORATION_WS_URL||wss://{host}/collaboration/ws/}?room=${docId}`；room=文档 UUID；HocuspocusProvider name=storeId，Y.Doc guid=room，初始内容来自 `GET /documents/{id}/content/`（base64）本地 apply。
- **updates 存储：未找到持久化**——hocuspocusServer 无 onStoreDocument/onLoadDocument/history 扩展，房间数据仅内存；持久化由前端 60s/离开页 `PATCH /documents/{id}/content/`（useSaveDoc.tsx:13,127-132）落 S3。
- awareness：Hocuspocus 默认（光标）+ 评论 `commentsPing` 事件。

### B2. getDocumentConnectionInfoHandler
`GET /collaboration/api/get-connections/?room=&sessionKey=` → `{count(非只读连接数), exists(sessionKey 是否在)}`（handlers/getDocumentConnectionInfoHandler.ts）；**它不返回 WS URL**——供后端 `CollaborationService.get_document_connection_info` 判断"别人在编辑且我没有 WS 连接"（core/services/collaboration_services.py:73-103），支撑 can-edit/COLLABORATION_WS_NOT_CONNECTED_READ_ONLY 锁。

### B3. convertHandler
`POST /api/convert/`（handlers/convertHandler.ts）：读 markdown(text/markdown,text/x-markdown,application/x-www-form-urlencoded 当 markdown)、Yjs(application/vnd.yjs.doc,octet-stream)、BlockNote(application/vnd.blocknote+json)；写 BlockNote/JSON、Yjs、markdown(lossy)、HTML(lossy)。用 BlockNote `ServerBlockNoteEditor` + CommentsExtension（保住 comment mark，否则转换丢注释文本）；**无 pandoc**。上游前端导出是 @blocknote/xl-* 客户端导出 + formatted-content 服务端导出，均依赖它。

### B4. env.ts
`COLLABORATION_LOGGING`、`COLLABORATION_SERVER_ORIGIN`（默认 http://localhost:3000，逗号分隔 CORS/WS 白名单）、`COLLABORATION_SERVER_SECRET`(_FILE)、`CONVERSION_FILE_MAX_SIZE`(20MB)、`Y_PROVIDER_API_KEY`(_FILE, 默认 yprovider-api-key)、`PORT`(4444)、`SENTRY_DSN`、`COLLABORATION_BACKEND_BASE_URL`(默认 http://app-dev:8000)。**无 DB/Redis 配置**（纯内存 + 转发后端）。

## C. 前端（src/frontend/apps/impress，包名 app-impress 5.4.1）

### C1. 栈与构建
- **Next.js 16.2.9（Pages Router）**，`next.config.js`: `output:'export'`、trailingSlash、SW=workbox InjectManifest（service-worker 特性）；制品 `out/` 由 nginx 服务（src/frontend/Dockerfile：nginxinc/nginx-unprivileged，`conf/default.conf` 有 `frame-ancestors 'self' https://meet.we-meet.online https://meet.jusiai.com` 及 /docs/[uuid]、/user-reconciliations/... SSG fallback）。
- 依赖：@blocknote/* 0.51.4（core/react/mantine/xl-ai/xl-docx/xl-odt/xl-pdf/xl-multi-column/code-block）、@hocuspocus/provider 3.4.4、yjs、y-protocols 1.0.7、@tanstack/react-query 5、zustand 5、i18next 26 + react-i18next 17、styled-components、@gouvfr-lasuite/cunningham-react、react-resizable-panels、cmdk、react-dropzone、@dnd-kit、posthog-js、@sentry/nextjs、idb。**未找到 react-virtuoso**。
- 路由（src/pages/）：`/`(→docs)、`/home`、`/login`、`/offline`、`/docs`(列表网格)、`/docs/new`、`/docs/[id]`、`/user-reconciliations/{active|inactive}/[id]`、401/404/500。**没有独立 /search、/trash、/templates、/settings 路由**——搜索是头部 QuickSearch 弹窗（doc-search），回收站是网格筛选（docs-grid target=TRASHBIN→`/documents/trashbin/`），模板仅首页外链；文档所有操作在 [id] 页头部 DocFloatingBar/DocHeader。

### C2. 编辑器
- **BlockNote 0.51.4（底层 TipTap）**：`doc-editor/components/BlockNoteEditor.tsx` schema = defaultBlockSpecs + `callout`/`codeBlock`/`pdf`/`uploadLoader`、withPageBreak、inline `interlinkingLinkInline`、xl-multi-column；CommentsExtension；AI 扩展（xl-ai，需 flag+abilities.ai_proxy）；tables splitCells/headers/cell 颜色；自定义光标渲染；工具栏 BlockNoteToolbar、BlockNoteSuggestionMenu、AIMenu；FloatingComposer/FloatingThread/ThreadsSidebar 评论浮层。
- Yjs 绑定：`useCollaboration.tsx` + `useProviderStore.tsx` 建 HocuspocusProvider；`useCreateBlockNote({collaboration:{provider, fragment: provider.document.getXmlFragment('document-store'), user}})`；只读模式 BlockNoteReader 用同一 fragment（provider 未传）。保存：useSaveDoc（60s + beforeunload + routeChangeStart，`websocket: isSynced`，离线乐观）。
- 离线策略：**有**——service-worker（workbox，SW_DEACTIVATED 可关）+ idb 缓存 + useIsOffline 乐观保存（features/service-worker）。
- 附件：`useUploadFile`→`attachment-upload`(multipart)，media-check 轮询，图片 URL `MEDIA_BASE_URL/media/{pk}/attachments/{uuid}.{ext}` 过 nginx media-auth。

### C3. 功能面（对应 API）
- 树：左栏 `TreeProvider onLoadChildren`→`GET /documents/{id}/children/?page=`（**懒加载分页，非整棵**）；`useDocTree`→tree/（当前文档祖先链+相邻层，`utils.nest_tree` 嵌套返回）；move/restore/duplicate 走对应端点。
- 列表：docs-grid 用 `GET /documents/?...`（filters/is_favorite/is_creator_me + ordering），收藏列表 favorite_list，回收站 trashbin。
- 评论：DocsThreadStore（REST threads/comments/reactions + awareness ping + comment mark 定位）；thread 弹窗内 Restore/Resolve。
- 版本：useDocVersions→versions/，选中→versions/{id}/ GET，恢复→PATCH content（前端把版本内容写回）。
- 分享：DocShareModal → accesses CRUD、invitations CRUD、`link-configuration`、ask-for-access、leave、users 搜索 `GET /users/?q=`；内嵌时"分享到聊天"经 `sendToHost`（postMessage/WeMeetHost 桥）交宿主。
- 导入导出：导入 useImportDoc POST /documents/ (multipart file)；导出 doc-export（@blocknote/xl-* 客户端 + formatted-content 服务端 + @react-pdf/renderer）。
- AI：doc-editor/components/AI（blocknote AI 走 ai-proxy SSE；legacy ai-transform/ai-translate）。
- 搜索页：doc-search QuickSearch 弹窗 → `GET /documents/search/?q=`（Find/title）。

### C4. 响应式与内嵌
- 响应式：`useResponsiveStore`（断点 560/768/1024，stores/useResponsiveStore.tsx）；移动端（≤768）自动收左栏（`useLeftPanelStore.autoClose`，docs 页用 ResizableLeftPanel）；**未找到底部导航/抽屉**；移动端编辑器 padding 变小、留 DocFloatingBar（注释/目录右侧栏 same）。
- 内嵌收敛：
  - `hooks/useIsEmbedded.tsx`：判据=iframe(`self!==top`) ∨ UA 含 `WeMeetApp`（`EMBED_UA_MARKER`）∨ sessionStorage `docs-embed`(?embed=1 模块级捕获)；`embedderLanguage()`＝`?lang=` > App `navigator.language`；`embedderTheme()`＝`?theme=` > UA `theme=dark|light`；`sendToHost()`＝`window.WeMeetHost.postEvent(json)`（App 桥）或 `parent.postMessage(payload,'*')`。
  - `core/config/ConfigProvider.tsx`：内嵌时框架语言/主题优先（PATCH 回 profile；`wemeet-theme` postMessage 同步、发 `wemeet-theme-ready`）；内嵌不套 `FRONTEND_THEME`。
  - `hooks/useEmbedShell.tsx`：协议 PROTOCOL=1；消息名 `wemeet-embed-hello`/`wemeet-host-hello`/`wemeet-route-changed`/`wemeet-navigate`/`wemeet-open-search`/`wemeet-panel-state`/`wemeet-ui-command`（与 we-meet DocsFrame 的 HOST_PROTOCOL 对应）；docs 声明 route-sync/panel-state/open-search/ui-command；消费宿主能力 global-search/shell-nav/route-sync（握手未落定默认按"已收敛"）；写 `<html data-wemeet-embed="web|app">`，收敛样式在 `src/styles/we-meet.css`（隐藏 docs 用户区=头像+退出+语言+Waffle、隐藏自带搜索入口、`?chrome=none` 时隐藏左栏开合按钮/App 单文档查看器只留三件套不动）。web 端只放开 `frame-ancestors` 白名单（default.conf）。
- i18n：单文件 `src/i18n/translations.json`（packages/i18n 负责抽取/部署），语言：br/de/el/en/es/fr/it/nl/pt/ru/sl/sv/tr/uk/zh（15 种）；后端 locale 另含 zh_CN 等（src/backend/locale）。

## D. 原生客户端可行性事实清单
- **纯 REST、无浏览器会话即用**：① external_api（`/external_api/v1.0/`，加密 JWT 服务账号）：documents list/retrieve/create/children、users get_me（动作受 EXTERNAL_API 配置，access/invitation 默认关）；② s2s 端点（Bearer 静态 token，身份靠请求体 sub/email）：create-for-owner / create-table-for-owner / search-for-user / list-for-user / grant-access-for-users / users/session-ticket(+GET session-from-ticket/ 免 Keycloak 换会话，用于 WebView 内嵌)；③ `GET config/` 匿名；④ public link 文档 retrieve/content 可匿名（无登录）。
- **依赖 session cookie+CSRF**：其余全部主 API（documents CRUD/children/tree/versions/favorite/link-configuration/trashbin、accesses、invitations、threads/comments、ask-for-access、users/me、search、attachment-upload、media-check 等），需先拿 docs_sessionid（OIDC authenticate/callback 或 session-from-ticket）+ `csrftoken` cookie→`X-CSRFToken`；否则 401/403（DRF SessionAuthentication）。
- **仅 WS 通道**：实时协同（房间 `?room={uuid}` `/collaboration/ws/`，只读由服务端 abilities 决定）+ awareness 光标/评论 ping；内容本身可由 PATCH content 落库、由 GET content 取全量——无 WS 也能收发文档（单机可用）。
- **被 we-meet 改过的点**：session-ticket + session-from-ticket（免 Keycloak 引导，注释见 core/api/session_bootstrap.py）；s2s search-for-user/list-for-user/grant-access-for-users 三个端点 + `_clamped_int` 分页夹取；create-table-for-owner（日历表导出）；FRONTEND_SILENT_LOGIN_ENABLED；内嵌外壳（useIsEmbedded/useEmbedShell/we-meet.css、CSP frame-ancestors、UA 标记 `WeMeetApp`、`?embed=1`、`?chrome=none`、主题/语言跟随、搜索代理）；OSS/S3 兼容修复（AWS_S3_ADDRESSING_STYLE、boto3 校验和）；深色主题与移动端对齐；zh 简体 i18n 校正；部署镜像/deploy 套件（deploy/aliyun-docs、国内镜像源）。最近提交主题详见 git log：`dff621dd 优化(文档)：适配深色文档图标`、`3795ed2d fix(backend): stabilize configurable language choices`、`a288e7d4 fix(ui): complete mobile interaction alignment`、`9edd6ad7 fix(ui): improve responsive docs design alignment`、`6ffc682a 修复(部署)：修正 Docs 转换服务地址`、`0e0d78d3 feat(docs): add calendar table export endpoint`、多条 embed/theme/docs-grid 修复、`1ce86686/953db8d7/013e3960`(share/search s2s)、`ccfa5118/6e9b933f`(silent login) 等。
