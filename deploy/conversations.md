# 用户会话分页

`GET /api/v1/conversations?page=0&size=20` 使用当前登录 Session，只返回该用户参与的会话。`page` 从 0 开始，`size` 默认 20，范围 1–100；按会话 ID 倒序稳定排列。无会话或超出末页时返回空列表，非法参数返回 HTTP 400，数据库查询失败返回 HTTP 503，未登录请求被拒绝。

```json
{
  "content": {
    "conversations": [
      {
        "id": 0,
        "title": "主聊天室",
        "hasNewMessage": false,
        "isMuted": false,
        "type": "Chatroom"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "hasMore": false
  },
  "statusCode": 200,
  "message": "ok"
}
```

聊天室标题取聊天室名称，好友会话标题取对方显示名；无关联元数据的会话使用 `Unknown` 类型和 `会话 <id>` 标题。`isMuted` 来自当前用户的参与记录，`hasNewMessage` 根据已入库消息和该用户的最后已读消息判断，按发送时间及 UUID 排序，不把 UUID 单独当作时间顺序。空会话无未读；已读 UUID 失效时，存在消息的会话视为有未读。

前端可通过 `src/api` 导出的 `getConversations({ params: { page: 0, size: 20 } })` 调用；返回的 `hasMore` 表示是否能继续请求下一页。翻页期间会话集合发生变化时，应刷新第一页以更新列表和总数。

登录会将用户加入主聊天室，重复登录不会重复添加参与记录。前端左侧使用 `n-layout-sider` 展示会话列表，点击进入 `/chat/{id}`；`/chat` 和首页默认进入 `/chat/0`。列表显示未读与静音状态，支持加载更多、空列表与失败重试，窄屏可展开或收起列表。

每次打开会话都会请求 `GET /api/v1/conversations/{id}/meta`，响应中的 `content.info` 为当前用户对应的会话信息。标题优先使用该响应，即使直接打开的会话尚未出现在已加载的分页列表中，也可显示正确标题。详情成功后才建立会话订阅；失败时可重试，草稿保留。详情会同步更新已加载列表中的对应条目；切换和登出后迟到的响应不会覆盖当前会话。

切换会话后先清理旧订阅，再订阅所选会话、等待就绪确认并同步历史，完成后才允许发送。各会话草稿分别保留，登出清除全部会话和草稿；旧历史响应与旧确认不能影响新会话。消息发送、历史、WebSocket 就绪及订阅均校验成员权限，非成员无法加入或读取会话。

已有数据库升级前先停应用并备份。若尚未升级消息持久化，先执行 `deploy/sql/001-message-persistence.sql`，再执行 `deploy/sql/002-conversations.sql`，然后启动新 JAR。第二个脚本可重复执行，预建主会话 `0`，并移除旧的“每个用户只能加入一个聊天室”约束；其余表、索引和聊天室/用户组合约束由 Hibernate 建立。全新数据库由应用初始化，无需执行脚本。
