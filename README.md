# CiRCLE Chat

## CDN / Nginx 部署

当前部署链路为浏览器 HTTPS → CDN → Nginx HTTP（80）→ Spring Boot HTTP（8080）。
前端会根据页面协议连接同域的 `wss://<域名>/ws`，并使用登录 Session。

Spring Boot 使用 `server.forward-headers-strategy: framework` 识别代理转发的外部
协议、域名和端口，让 WebSocket 的同源检查使用浏览器实际访问的 HTTPS 地址。
本地 Vite 开发地址仍然允许连接，其他跨域来源会被拒绝。

部署步骤：

1. 重新构建并部署后端 JAR（`./gradlew bootJar`），重启应用以加载新的配置。
2. 将站点已有的 `/www/server/panel/vhost/nginx/proxy/circle-chat-demo-fed90df.classisland.tech/*.conf`
   中包含 `location ^~ /` 的代理文件内容替换为 `deploy/nginx-proxy.conf`。
   替换原有规则即可，无需增加重复的 `location`。
3. 使用 `nginx -t` 检查配置，再执行 `nginx -s reload`，或通过面板重载。
4. 在 CDN 中开启 WebSocket 支持，并将访客 HTTP 请求重定向到 HTTPS。
5. 登录后检查浏览器网络面板：`/ws` 应返回 `101 Switching Protocols`，随后收到
   STOMP `CONNECTED` 帧，其中 `heart-beat` 为 `10000,10000`。
6. 后端 JAR 包含构建后的前端，必须一起更新。部署后刷新页面；如果 CDN 缓存了
   HTML，请刷新该缓存，确保浏览器加载新版本前端。

代理配置保留 WebSocket 的 `Upgrade` / `Connection` 头，并将 `Host` 和
`X-Forwarded-Host` 设置为站点域名。因为 HTTPS 在 CDN 终止，转发协议固定为
`https`、端口固定为 `443`；这里不能使用 Nginx 的 `$scheme`，它的值为 `http`。
此配置适用于上述 CDN 部署链路。仅通过受信任代理访问后端 8080 端口。

`$connection_upgrade` 沿用原面板配置中的变量。如果面板没有定义它，请在 Nginx
的 `http` 块中添加（不要放在 `server` 或 `location` 块中）：

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}
```

## 连接保活与消息确认

后端简单消息代理通过调度器启用双向 STOMP 心跳，前端每 10 秒发送心跳。
空闲时服务器也会发送心跳，避免 CDN / Nginx 将连接判定为空闲而断开。
Nginx 配置关闭代理缓冲，并为读写分别设置 300 秒超时。

每次 STOMP 连接成功后，前端都会重新订阅聊天室和当前连接的发送确认队列。
断线时立即禁用发送；重新连接、收到订阅就绪确认并同步历史后再允许发送。
服务端按每个连接的接收顺序处理 `SUBSCRIBE` 和 `SEND`，保证立即发送的消息
不会抢在订阅之前处理。

发送请求包含 `clientMessageId`，服务器完成聊天室广播后向
`/user/queue/chat/acks` 返回对应的处理结果。成功代表广播完成且已进入待写队列，
不代表数据库已经提交。确认只发送到发起请求的连接。
前端收到成功确认后才清空输入框；服务器拒绝、断线或 15 秒内未收到确认时，
保留草稿并显示错误。确认超时可能表示响应丢失，需先检查聊天记录再重试。
退出登录会关闭旧连接，停止重连并清除旧订阅。

回归测试：`./gradlew test --tests dev.hellowrc.circlechat.WebSocketProxyTests`。
测试通过真实 WebSocket 验证 HTTPS 转发头、STOMP 心跳、广播、发送确认、
重连后收发、失败处理、本地开发来源，以及未登录和不受信任来源的拒绝行为，
使用内存数据库。前端回归测试：在 `src/client` 中执行 `pnpm test`。

线上验收：打开两个已登录窗口，相互发送消息；空闲五分钟后再发送；
断网后确认发送按钮禁用，恢复网络后确认两个窗口仍能互相收发；
退出登录后检查旧 WebSocket 不再重连。

配置依据：[Spring Boot 转发头处理](https://docs.spring.io/spring-boot/how-to/webserver.html#howto.webserver.use-behind-a-proxy)
、[STOMP 心跳配置](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-simple-broker.html)
和 [Nginx WebSocket 代理](https://nginx.org/en/docs/http/websocket.html)。

## 消息持久化与主会话

当前主会话 ID 固定为 `0`。发送、订阅与历史查询都预留会话 ID；其他 ID 的
发送和就绪请求返回失败，历史查询返回 HTTP 404。目前没有会话创建、成员管理、
列表或切换功能。旧 `/app/message/send` 和 `/topic/chat/main` 已移除，前后端需一起更新。
服务端只允许订阅主会话及当前连接的确认队列；直接向 broker 发布或订阅其他目标
会返回 STOMP ERROR 并关闭该连接，避免绕过消息校验和持久化。

| 用途 | 入口 |
| --- | --- |
| 发送 | `/app/conversations/0/messages/send`，正文 `{ message, clientMessageId }` |
| 广播 | `/topic/conversations/0/messages` |
| 发送确认 | `/user/queue/chat/acks` |
| 订阅就绪 | `/app/conversations/0/ready`，正文 `{ requestId }` |
| 就绪确认 | `/user/queue/chat/ready`，包含 `requestId, conversationId, success, error` |
| 历史查询 | `GET /api/v1/conversations/0/messages`，需要登录 Session |

消息对外使用 UUID 字符串 `id`，并包含 `conversationId`；数据库自增主键仅用于内部。
`sendTime` 在广播前生成，保留微秒精度，入库和历史返回沿用相同值；审计时间独立记录。

历史查询支持 `limit`（默认 50，范围 1–100）、互斥的 `before` / `after`，以及
可选上界 `until`。游标由服务器返回，客户端应原样传递，不使用数据库主键或自行解析。
响应为 `{ content: { messages, nextCursor, hasMore, snapshotCursor }, statusCode, message }`。
不传方向时返回最近一页；`before` 返回更早一页，`after` 从指定边界向后补齐。
每页消息均按发送时间、UUID 升序排列；`nextCursor` 仅在该方向还有消息时返回。
使用第一页的 `snapshotCursor` 作为后续页的 `until`，可固定本次同步范围。
无效游标或分页参数返回 HTTP 400；数据库查询失败返回 HTTP 503。

前端先订阅消息、发送确认和就绪队列，再发送 `ready`；服务端按同连接的接收顺序
返回就绪确认，之后才拉取历史。首次加载最近 50 条，可继续加载更早消息。
重连从上次完整同步的游标分页补齐，并继续接收实时广播；全部补偿成功后才推进游标。
实时消息与历史按 UUID 去重，失败时保留消息和草稿并提供重试入口。

服务器先登记待写消息、广播，再交给单消费者 FIFO 队列写库。历史查询先复制待写
快照，再以 READ_COMMITTED 查询数据库并合并去重；事务提交成功后才移除待写记录。
因此，广播后立即查询，或查询期间恰好完成提交和清理，都不会漏掉已接收的消息。
重试按 UUID 幂等，不会重复插入。

队列容量统计包含排队、写入和重试中的消息。容量不足时在广播前拒绝发送。
数据库失败时队头持续重试，不跳过后续消息；请根据日志中的消息 UUID 定位错误。
队列仅存在于单个应用进程内，异常退出可能丢失尚未提交的消息，不支持多实例部署。
正常停机停止接收新消息，等待队列排空；超过等待期限后会记录剩余待写数量。

可通过 `.env` 调整以下参数，单位均为毫秒（容量除外）：

| 配置 | 默认值 |
| --- | --- |
| `CHAT_PERSISTENCE_CAPACITY` | 10000 |
| `CHAT_PERSISTENCE_SHUTDOWN_MILLIS` | 30000 |
| `CHAT_PERSISTENCE_RETRY_MILLIS` | 1000 |
| `CHAT_PERSISTENCE_MAX_RETRY_MILLIS` | 30000 |

### 升级已有数据库

停止旧应用并备份数据库，然后使用 PostgreSQL 执行
[`deploy/sql/001-message-persistence.sql`](deploy/sql/001-message-persistence.sql)，再启动新 JAR。
脚本在事务中增加字段、将旧消息归入会话 `0`、生成稳定 UUID、从 `created_at` 回填
发送时间（缺失时用升级时间）、将正文改为 TEXT，并建立非空约束和索引；可以重复执行。
已有数据库必须先执行此脚本，不能只依赖 Hibernate `ddl-auto: update` 回填数据。
全新数据库由 Hibernate 创建表，无需运行升级脚本。

消息回归测试：`./gradlew test`；前端在 `src/client` 中执行 `pnpm test` 和 `pnpm build`。
测试覆盖广播先于写库、未提交事务的可见性、快照与清理交错、重试、背压、分页、
重启恢复和真实 WebSocket / HTTP 链路。升级脚本测试使用 H2 的 PostgreSQL 兼容模式，
不替代实际 PostgreSQL 的部署验证。
可设置 `CIRCLECHAT_TEST_POSTGRES_URL`、`CIRCLECHAT_TEST_POSTGRES_USERNAME`、
`CIRCLECHAT_TEST_POSTGRES_PASSWORD` 后运行
`./gradlew test --tests dev.hellowrc.circlechat.MessagePostgresMigrationTests`；此测试在
新建的独立 schema 中验证 PostgreSQL 升级及约束，并清理该 schema，不修改已有表。
默认启动测试使用 H2，避免自动更新开发数据库结构。

更新 HTTP 生成代码可以先运行
`./gradlew test --tests dev.hellowrc.circlechat.MessageApiTests`，再在 `src/client` 中设置
`OPENAPI_INPUT=../../build/openapi.json` 并运行 `pnpm api:generate -f`；该 JSON 来自真实
Springdoc 接口。也可以沿用已有的在线 OpenAPI 生成流程。

上线验收：两个登录窗口相互发送，立即刷新并核对消息只出现一次；发送超过 50 条后
加载更早消息；让一个窗口断线期间发送超过 50 条，恢复后确认记录完整；重启应用后
确认已入库消息仍可读取。加载更早消息时，应保持原来的可见消息位置。

