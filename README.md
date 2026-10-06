# CiRCLE Chat

## Docker Compose 启动

安装并启动 Docker Engine（或 Docker Desktop，使用 Linux 容器）和 Docker Compose。在项目根目录运行：

```sh
docker compose up -d --build
```

构建在容器内完成，无需宿主机安装 Java、Node.js、pnpm，也无需先创建 `.env`。镜像构建使用 Node.js 24、pnpm 11.7.0 和 Java 25，将前端生产产物打入后端 JAR；首次构建需要联网下载镜像和依赖。

启动后访问 <http://127.0.0.1:8080>。Compose 同时启动应用和 PostgreSQL 18，等待数据库健康后启动应用。应用启动需要一些时间，可以检查状态和日志：

```sh
docker compose ps
docker compose logs -f app db
```

等待两个服务显示 `healthy` 后即可使用，也可运行 `docker compose up -d --build --wait` 等待健康检查通过。新数据库由应用建表并初始化主聊天室和管理员；管理员用户名为 `root`，初始密码为 `believe_the_rainbow`，登录后可在用户设置中修改密码。

## 配置

默认配置即可启动。需要调整时，可将 `.env.example` 复制为根目录 `.env` 后编辑，或设置同名环境变量。修改后再次执行 `docker compose up -d --build`。

| 配置 | 默认值 | 用途 |
| --- | --- | --- |
| `POSTGRES_DB` | `circlechat` | Compose 内置数据库名称 |
| `POSTGRES_USER` | `circlechat` | Compose 内置数据库用户 |
| `POSTGRES_PASSWORD` | `circlechat` | Compose 内置数据库密码，上线时请设置自定义密码 |
| `CIRCLECHAT_PORT` | `8080` | 宿主机应用端口，仅绑定 `127.0.0.1` |
| `CIRCLECHAT_STOP_GRACE_PERIOD` | `60s` | 应用容器停止宽限期 |
| `CHAT_PERSISTENCE_CAPACITY` | `10000` | 待写消息容量，包含排队、写入和重试中的消息 |
| `CHAT_PERSISTENCE_SHUTDOWN_MILLIS` | `30000` | 停机时等待消息排空的时间，毫秒 |
| `CHAT_PERSISTENCE_RETRY_MILLIS` | `1000` | 写库首次重试间隔，毫秒 |
| `CHAT_PERSISTENCE_MAX_RETRY_MILLIS` | `30000` | 写库最大重试间隔，毫秒 |

Compose 显式将应用连接到 `db:5432`。根目录 `.env` 中的 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 仅用于直接运行 JAR 或 Gradle，不影响 Compose 数据库连接。实际 `.env` 文件不会进入构建上下文或镜像，前端使用同域 `/api` 和 `/ws`。

数据库不开放宿主机端口，数据保存在命名卷 `postgres_data` 中。数据库首次初始化后，修改 `POSTGRES_*` 不会自动重命名现有数据库或修改数据库账号、密码；已有数据需要在 PostgreSQL 中完成相应修改并同步配置。

应用使用优雅停机并等待消息队列排空。`CIRCLECHAT_STOP_GRACE_PERIOD` 应至少覆盖 HTTP 停机等待时间（30 秒）与 `CHAT_PERSISTENCE_SHUTDOWN_MILLIS`，增加消息排空时间时需同步增加停止宽限期。异常退出仍可能丢失尚未提交的消息；消息队列只支持单实例，不要扩容应用副本。

## 更新和停止

更新源码后，在项目根目录重新构建并启动，前后端会一起更新，数据库卷保持不变：

```sh
docker compose up -d --build
```

停止并移除容器及网络，保留数据库：

```sh
docker compose down
```

**以下命令会同时删除数据库卷及全部数据，仅在确认需要清空时使用：**

```sh
docker compose down -v
```

## 线上代理与已有数据库

应用默认只监听宿主机 `127.0.0.1:8080`，沿用浏览器 HTTPS → CDN → 宿主机 Nginx HTTP → 应用的部署方式。使用 `deploy/nginx-proxy.conf` 替换站点已有代理规则，执行 `nginx -t` 后重载。若更改 `CIRCLECHAT_PORT`，同步修改 Nginx 的上游端口。

保留 WebSocket 的 `Upgrade` / `Connection` 头和 300 秒代理超时；HTTPS 在 CDN 终止时，转发协议和端口保持 `https` / `443`。应用继续使用 `server.forward-headers-strategy: framework`。源码更新后需刷新 CDN 的 HTML 缓存。

Compose 默认创建独立的新数据库，不自动接入或迁移宿主机已有数据库，也不自动执行升级 SQL。迁移旧数据时，停止旧应用并备份数据库，在 PostgreSQL 依次执行 `deploy/sql/001-message-persistence.sql` 和 `deploy/sql/002-conversations.sql`；导入内置数据库后再启动新应用。不能只依赖 Hibernate `ddl-auto: update` 回填旧数据。全新数据库无需执行升级脚本。
