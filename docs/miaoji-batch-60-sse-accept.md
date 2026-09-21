# 第60批：生产问答SSE的406修复

生产backend `8452f82de` / Helm387，Pixel8 Android16。旧App点击Ask AI实际返回HTTP406；`globalAskStream`已设置`Accept: text/event-stream`，但AuthInterceptor添加Bearer时无条件改为application/json。生产请求头对照确认JSON Accept返回406，SSE Accept返回200及meta/delta/done。

修复仅在调用方未指定Accept时设置application/json，保留显式SSE和其他媒体类型。鉴权快照、Bearer、No-Auth及刷新流程保持。此修改也覆盖共用鉴权客户端的房间流式请求，但本批未生产重测房间问答。

`assembleDebug`、`assembleDebugAndroidTest`成功；Pixel8上AuthAcceptHeaderTest四项、MeetingAiUxTest两项，共6项通过。前者使用独立前缀的加密测试偏好，未替换目标App登录凭据；模拟终点经过真实AuthInterceptor，覆盖JSON默认、显式媒体类型、No-Auth、GlobalAsk SSE全链路解析。现有无鉴权模拟测试单独通过不足以发现本次问题。

修复debug APK以覆盖安装保留原登录，生产App选择Meetings and recordings、输入既有视频标题、点击Ask AI后显示回答与来源卡片；点击使用中的引用[6]打开对应记录和指定历史纪要版本，正文加载完成。该UI样本只用于通信/导航闭环，不算中文自然问题质量评测；中文API对照及剩余噪声/无依据推断见we-meet第60批报告。

仅Android代码变更，需新版App正式构建分发；当前只安装模拟器debug APK，物理设备/正式升级未验收。无需backend/frontend/summary/agents重新部署。
