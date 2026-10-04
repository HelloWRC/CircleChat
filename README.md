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
断线时立即禁用发送；重新连接并恢复订阅后再允许发送。
服务端按每个连接的接收顺序处理 `SUBSCRIBE` 和 `SEND`，保证立即发送的消息
不会抢在订阅之前处理。

发送请求包含 `clientMessageId`，服务器完成聊天室广播后向
`/user/queue/chat/acks` 返回对应的处理结果。确认只发送到发起请求的连接。
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

