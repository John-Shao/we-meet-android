# 双语互译固定方向显示修复

用户反馈设置为固定“英语 → 中文”，返回主页面后空白状态仍显示“英语 ⇄ 中文”。原因是主页面始终引用双向语言对文案；设置页、偏好保存和会话请求使用的固定方向未被该文案改变。

Android `main` 产品提交 `1c6d3df78384b4c7a2f74d0c9f7ff13e80a649b6` 修复空白状态：自动模式保留双向箭头，固定模式显示单向箭头，并以实际固定源语言排列源、目标。默认、简体中文、德语、法语、荷兰语资源同步补齐。没有修改设置持久化、请求参数、方向识别、音频链路或服务端。

## 验证与交付

- 全仓多语言检查 0 错误；提交钩子的设计规范检查通过。
- 内部 Release 主 APK 与匹配测试 APK 构建成功，耗时 6 分 49 秒；签名校验通过，沿用原内部证书，非 debuggable。
- 本轮专用 API 34 模拟器运行两项既有测试通过：`BilingualTranslationUiTest#fixedDirectionCanBeSelectedAndIsLockedDuringTranslation`、`BilingualTranslationTest#fixedDirectionIsPassedToGatewayAndLockedUntilTheSessionStops`。它们验证设置切换/锁定及固定方向请求保持；没有新增测试，也不将这些结果表述为荣耀主页面的人工复测。
- [设计规范 CI](https://github.com/John-Shao/we-meet-android/actions/runs/39190535638)、[多语言 CI](https://github.com/John-Shao/we-meet-android/actions/runs/39190535633) 均通过，绑定上述产品提交。

[下载 0.3.0-work.8 内部 APK](https://github.com/John-Shao/we-meet-android/releases/download/v0.3.0-work.8-internal/we-meet-0.3.0-work.8-internal.apk)；[发布页与校验和](https://github.com/John-Shao/we-meet-android/releases/tag/v0.3.0-work.8-internal)。versionCode 10，Android 10 及以上，可覆盖安装现有内部包。APK 大小 146187176 字节，SHA-256：`b5d0a446d46ecb0f7b98bdcbb8bc6d6a5947f7465e0eaecc8b4ba55425a8f14f`。

普通手机覆盖安装后，可在停止状态设置“英语 → 中文”并返回主页面，确认显示单向箭头；切回自动后应显示双向箭头。荣耀 300 的本次显示人工复测尚未提供，不扩展为新的语音或声学验收。BT04、后端可用性及此前其他遗留项保持原状态，见[阶段收尾记录](bilingual-closeout-2026-10-18.md)。

原开发目录和其他会话工作区保持不动；只使用本轮新建的隔离工作区、干净 IM SDK 提交和专用模拟器。构建与测试记录另行保留，发布包、旧版本及原证据归档不覆盖。
