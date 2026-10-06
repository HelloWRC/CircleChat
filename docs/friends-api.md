# 好友接口

所有接口需要登录 Session，前缀为 `/api/v1/friends`，响应沿用 `{ content, statusCode, message }`。

| 操作 | 方法与路径 | 参数 |
| --- | --- | --- |
| 好友列表 | `GET /my` | 返回 `content.friends`，按显示名称、用户名排序 |
| 用户信息与好友关系 | `GET /user/{username}` | 返回公开信息和 `isFriend`，不返回邮箱或密码 |
| 好友请求列表 | `GET /requests` | `sent=false` 为收到的请求，`sent=true` 为发送的请求；可按 `state` 过滤 |
| 发送请求 | `POST /requests` | `{ "targetUsername": "bob", "note": "你好" }`；`note` 可省略 |
| 接受请求 | `POST /requests/{id}/accept` | 仅接收方可处理，建立双方好友关系与双人会话 |
| 拒绝请求 | `POST /requests/{id}/reject` | 仅接收方可处理 |
| 忽略请求 | `POST /requests/{id}/ignore` | 仅接收方可处理 |
| 删除好友 | `DELETE /{username}` | 删除双方好友关系并撤销该好友会话中双方的成员权限 |

请求列表默认查询所有状态，`state` 可为 `Open`、`Accepted`、`Rejected`、`Ignored`。`page` 默认 0，必须非负；`size` 默认 20，范围 1–100。按请求 ID 倒序排列，`content` 为 `{ content: [...], page, size, totalElements, totalPages, hasMore }`，列表项含请求 ID、双方用户名、显示名称、头像、备注、状态和审计时间。

发送请求返回新请求的信息。目标用户名不能为空或超过 32 个字符，备注不能超过 255 个字符；不能添加自己。双方任一方向已有待处理请求或已是好友时返回 HTTP 409。已拒绝或忽略的请求不阻止重新发送。

只允许处理 `Open` 请求，重复处理返回 HTTP 409；不存在的用户、好友关系、请求，以及其他用户的请求返回 HTTP 404；非法参数返回 HTTP 400。业务错误的 HTTP 状态与响应 `statusCode` 一致。

接受请求在同一事务中更新请求状态、创建好友关系、会话和双方成员记录。重复操作受行锁保护。删除好友保留会话及消息数据，但双方不再能发送、建立新订阅或查询该会话历史；重新添加会创建新会话。
