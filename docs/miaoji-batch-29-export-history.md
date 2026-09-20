# 第二十九批：个人纪要导出历史分页

- 信息页使用服务端 next_cursor 翻阅本人更早的导出副本；每页 10 条，前后导航，切换账号/记录重置游标。
- 游标只作为 Retrofit query 参数，不作为外部 URL；请求/响应均限制长度并拒绝重复游标。保留账号边界、状态/文档 ID 校验和读失败后的内容清除。
- 旧后端无 next_cursor 时仍显示原有单页。翻页不创建文档、不重试导出、不扩展 Docs 权限。
- MeetingDeliveryRepository 11 项单元、RecordExportsTest 10 项 Pixel 8 仪器测试、assembleDebug、checkDesignTokens 通过；新版 debug APK 已安装。配套生产部署和正式 App 分发待完成。
