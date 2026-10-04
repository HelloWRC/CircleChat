# CiRCLE Chat 项目约定

## 构建与验证

- 后端构建：`./gradlew bootJar`；回归测试：`./gradlew test`。JAR 包含前端产物，部署时前后端必须一起更新。
- 前端在 `src/client` 执行 `pnpm test`、`pnpm build`。
- WebSocket 专项：`./gradlew test --tests dev.hellowrc.circlechat.WebSocketProxyTests`。
- 默认测试使用 H2；H2 的 PostgreSQL 兼容模式不能替代真实 PostgreSQL 验证。
- PostgreSQL 迁移测试：设置 `CIRCLECHAT_TEST_POSTGRES_URL`、`CIRCLECHAT_TEST_POSTGRES_USERNAME`、`CIRCLECHAT_TEST_POSTGRES_PASSWORD`，运行 `./gradlew test --tests dev.hellowrc.circlechat.MessagePostgresMigrationTests`；测试创建并清理独立 schema。
- 更新 HTTP 生成代码：先运行 `./gradlew test --tests dev.hellowrc.circlechat.MessageApiTests`，再在 `src/client` 设置 `OPENAPI_INPUT=../../build/openapi.json` 并运行 `pnpm api:generate -f`。

## 部署与代理

- 链路：浏览器 HTTPS → CDN → Nginx HTTP（80）→ Spring Boot HTTP（8080）。后端 8080 仅允许受信任代理访问。
- 使用 `deploy/nginx-proxy.conf` 替换站点已有的 `location ^~ /` 代理规则，避免重复；执行 `nginx -t` 后重载 Nginx。CDN 开启 WebSocket、将 HTTP 重定向到 HTTPS；更新前端后刷新 CDN 的 HTML 缓存。
- 保留 `Upgrade` / `Connection` 头，`Host` / `X-Forwarded-Host` 使用站点域名。HTTPS 在 CDN 终止，转发协议和端口固定为 `https` / `443`，不能使用 Nginx 的 `$scheme`。
- Spring Boot 保持 `server.forward-headers-strategy: framework`。WebSocket 使用登录 Session 和同域 `/ws`；允许本地 Vite 开发来源，拒绝其他跨域来源。
- 双向 STOMP 心跳为 10 秒；Nginx 关闭代理缓冲，读写超时均为 300 秒。登录后 `/ws` 应返回 HTTP 101，STOMP `CONNECTED` 的 `heart-beat` 为 `10000,10000`。
- 若面板未定义 `$connection_upgrade`，在 Nginx `http` 块添加（不能放入 `server` / `location`）：

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}
```

## 会话与接口

仅支持主会话 `0`，尚无会话创建、成员管理或切换功能。其他会话的发送和就绪请求失败，历史查询返回 HTTP 404。只允许订阅主会话和当前连接的确认队列；直接向 broker 发布或订阅其他目标会收到 STOMP ERROR 并断开。

| 用途 | 入口 / 正文 |
| --- | --- |
| 发送 | `/app/conversations/0/messages/send`；`{ message, clientMessageId }` |
| 广播 | `/topic/conversations/0/messages` |
| 发送确认 | `/user/queue/chat/acks` |
| 订阅就绪 | `/app/conversations/0/ready`；`{ requestId }` |
| 就绪确认 | `/user/queue/chat/ready`；`requestId, conversationId, success, error` |
| 历史查询 | `GET /api/v1/conversations/0/messages`；需要登录 Session |

消息对外使用 UUID 字符串 `id` 和 `conversationId`，数据库自增主键仅内部使用。`sendTime` 在广播前生成，保留微秒精度；入库和历史沿用同值，审计时间独立记录。

## 连接与发送

- 服务端按同一连接的接收顺序处理 `SUBSCRIBE` / `SEND`。每次连接后先订阅消息、发送确认和就绪队列，再发送 `ready`；收到就绪确认后拉取历史，同步完成才允许发送。断线立即禁用发送；退出登录关闭连接、停止重连并清除订阅。
- 发送确认只返回发起请求的连接；成功表示已广播并进入待写队列，不表示数据库已提交。收到成功确认才清空输入；拒绝、断线或 15 秒确认超时保留草稿并显示错误。超时可能是确认丢失，重试前先检查聊天记录。
- 首次加载最近 50 条，支持加载更早消息并保持可见消息位置。重连从上次完整同步的游标分页补齐，同时接收实时广播；全部补齐成功才推进游标。历史与实时消息按 UUID 去重，失败保留消息、草稿并提供重试。

## 历史分页

- `limit` 默认 50、范围 1–100；`before` / `after` 互斥，可用 `until` 限定上界。游标由服务器生成，客户端原样传递，不解析或使用数据库主键代替。
- 响应：`{ content: { messages, nextCursor, hasMore, snapshotCursor }, statusCode, message }`。
- 无方向返回最近一页；`before` 查更早消息，`after` 向后补齐。每页按发送时间、UUID 升序排列；该方向还有消息才返回 `nextCursor`。后续页以第一页 `snapshotCursor` 作为 `until` 固定同步范围。
- 无效游标或分页参数返回 HTTP 400，数据库查询失败返回 HTTP 503。

## 持久化

- 先登记待写消息、广播，再由单消费者 FIFO 队列写库。历史查询先复制待写快照，再以 READ_COMMITTED 查询数据库并合并去重；提交成功后才移除待写记录，避免广播后查询或提交清理交错时漏消息。写库重试按 UUID 幂等。
- 容量包含排队、写入和重试中的消息；满载在广播前拒绝。数据库失败时持续重试队头，后续消息等待；通过日志中的 UUID 定位错误。
- 队列仅在单个进程内，不支持多实例；异常退出可能丢失未提交消息。正常停机停止接收新消息并等待排空，超时记录剩余数量。
- `.env` 配置如下，除容量外均为毫秒：

| 配置 | 默认值 |
| --- | --- |
| `CHAT_PERSISTENCE_CAPACITY` | 10000 |
| `CHAT_PERSISTENCE_SHUTDOWN_MILLIS` | 30000 |
| `CHAT_PERSISTENCE_RETRY_MILLIS` | 1000 |
| `CHAT_PERSISTENCE_MAX_RETRY_MILLIS` | 30000 |

## 数据库升级

已有数据库：停止旧应用、备份数据库，在 PostgreSQL 执行 `deploy/sql/001-message-persistence.sql` 后启动新 JAR。脚本可重复执行，回填会话 `0`、稳定 UUID 和发送时间，调整正文为 TEXT，并建立约束与索引；不能只依赖 Hibernate `ddl-auto: update` 回填。全新数据库由 Hibernate 建表，无需升级脚本。
