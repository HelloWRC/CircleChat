# client

This template should help get you started developing with Vue 3 in Vite.

## Recommended IDE Setup

[VS Code](https://code.visualstudio.com/) + [Vue (Official)](https://marketplace.visualstudio.com/items?itemName=Vue.volar) (and disable Vetur).

## Recommended Browser Setup

- Chromium-based browsers (Chrome, Edge, Brave, etc.):
  - [Vue.js devtools](https://chromewebstore.google.com/detail/vuejs-devtools/nhdogjmejiglipccpnnnanhbledajbpd)
  - [Turn on Custom Object Formatter in Chrome DevTools](http://bit.ly/object-formatters)
- Firefox:
  - [Vue.js devtools](https://addons.mozilla.org/en-US/firefox/addon/vue-js-devtools/)
  - [Turn on Custom Object Formatter in Firefox DevTools](https://fxdx.dev/firefox-devtools-custom-object-formatters/)

## Type Support for `.vue` Imports in TS

TypeScript cannot handle type information for `.vue` imports by default, so we replace the `tsc` CLI with `vue-tsc` for type checking. In editors, we need [Volar](https://marketplace.visualstudio.com/items?itemName=Vue.volar) to make the TypeScript language service aware of `.vue` types.

## Customize configuration

See [Vite Configuration Reference](https://vite.dev/config/).

## Project Setup

```sh
pnpm install
```

## UI、请求和自动导入

Naive UI 组件通过 `unplugin-vue-components` 和 `NaiveUiResolver` 按需导入，可以直接在模板中使用 `<n-button>` 等组件。`App.vue` 已配置中文语言和 Message、Dialog、Notification、LoadingBar Provider；在其子组件中可以直接调用 `useMessage()` 等 API。

`unplugin-auto-import` 自动导入 Vue、Vue Router、Pinia API，以及 Naive UI 的上述四个组合式 API 和 Alova 的 `useRequest`、`useWatcher`、`useFetcher`。例如，`ref()` 和 `computed()` 无需手动导入。普通业务模块（如下面的 `api`）仍需显式导入。

类型声明保存在 `src/auto-imports.d.ts` 和 `src/components.d.ts`，运行 `pnpm dev` 或 `pnpm build-only` 会自动更新；这两个文件应随源码提交，以支持首次类型检查。

Alova 实例配置位于 `src/api/instance.ts`，通过 `src/api/index.ts` 导出，使用 Vue 状态适配器和 Fetch 请求适配器，默认超时 10 秒、禁用 GET 缓存。HTTP 错误会抛出异常，成功响应按 Content-Type 解析为 JSON 或文本，空响应返回 `undefined`。

默认请求前缀为 `/api`，开发环境沿用 Vite 到 `http://localhost:8080` 的代理。需要更改时，将 `.env.example` 复制为 `.env.local` 并设置 `VITE_API_BASE_URL`；生产环境需由部署服务提供 `/api` 路由，或在构建前配置完整 API 地址。

下面使用 Swagger 生成的当前用户接口，调用函数返回 alova Method，可直接交给 `useRequest`：

```vue
<script setup lang="ts">
import { me } from '@/api'

const message = useMessage()
const { loading, data, send, onError } = useRequest(() => me(), {
  immediate: false,
})

onError(({ error }) => message.error(error.message))
</script>

<template>
  <n-button :loading="loading" @click="send()">加载</n-button>
  <p>{{ data?.content?.user?.displayName }}</p>
</template>
```

官方文档：[Naive UI](https://www.naiveui.com/zh-CN/os-theme/docs/introduction)、[Alova](https://alova.js.org/zh-CN/tutorial/getting-started/quick-start/)、[unplugin-auto-import](https://github.com/unplugin/unplugin-auto-import)、[unplugin-vue-components](https://github.com/unplugin/unplugin-vue-components)。

## xicons 图标

已引入 xicons 的 Vue 3 图标包 `@vicons/ionicons5`（Ionicons 5）。图标在使用处显式按需导入，通过 Naive UI 的 `<n-icon>` 控制大小和颜色，无需全局注册：

```vue
<script setup lang="ts">
import { ChatbubbleOutline } from '@vicons/ionicons5'
</script>

<template>
  <n-button>
    <template #icon>
      <n-icon :size="20" aria-hidden="true">
        <ChatbubbleOutline />
      </n-icon>
    </template>
    聊天
  </n-button>
</template>
```

`n-icon` 沿用现有的 Naive UI 自动导入配置。图标默认继承文字颜色，也可设置 `color`；仅包含图标的按钮需通过 `aria-label` 提供操作名称。

图标预览与搜索：[xicons](https://www.xicons.org)。使用说明：[xicons 官方文档](https://github.com/07akioni/xicons)。

## 登录与用户状态

登录页面位于 `/auth/login`，通过现有 `login` 接口提交用户名和密码，支持表单校验、回车提交、加载状态和错误提示。请求统一携带 Session Cookie（`credentials: 'include'`）。

注册页面位于 `/auth/register`，沿用登录页布局与样式，提供用户名、昵称、邮箱、密码和确认密码，校验必填项、邮箱格式与两次密码是否一致。注册成功后跳转登录页并预填用户名，保留原访问地址；登录页与注册页提供相互跳转入口。注册接口只创建账户，登录后才会建立会话；已登录访问注册页会返回目标页面或首页。

`src/stores/user.ts` 提供 Pinia 用户 Store `useUserStore()`，保存当前 `user` 和 `isAuthenticated`，并通过 `login()`、`logout()`、`restoreSession()`、`refreshUser()` 管理状态。用户信息（包含 `avatarUrl`、`avatarSmallUrl`、`avatarLargeUrl`）保存在 localStorage 的 `circlechat.user` 中；密码不持久化。刷新页面时会调用 `/v1/users/me` 校验服务端会话，本地缓存不能单独作为登录依据；本地存储不可用时仍支持内存中的登录状态。

Store 的 `displayName` 和 `avatarUrl` 提供用户菜单使用的昵称与头像 URL。侧栏优先显示小尺寸头像，其次使用默认、大尺寸头像；头像未设置或加载失败时显示昵称首字。通过 `storeToRefs()` 读取这些字段可保持响应性，登录、刷新用户信息及清理会话时侧栏同步更新。

侧栏登出调用 `logout()`，立即清除 Pinia 和本地用户缓存，并携带 Cookie 发送 `POST /api/v1/auth/logout`。服务端通过 Spring Security 登出过滤器使 Session 失效、清除认证并将 `JSESSIONID` Cookie 过期，返回 `204 No Content`；随后前端跳转登录页。重复登出合并为一次请求，尚未完成的用户信息请求不能重新恢复已清除的状态。服务端请求失败时仍清除本地状态、跳转登录页，并明确提示服务端登出未完成。

登出接口由过滤器提供，不在 MVC 的 Swagger 生成结果中；请求封装位于 `src/api/auth.ts`，从 `@/api` 导出，不需要修改自动生成文件。

路由默认要求登录，公开页面通过 `meta: { public: true }` 标记。未登录会跳转 `/auth/login?redirect=原访问地址`，登录后返回原页面；已登录访问登录页会自动返回目标页或首页。业务接口返回 401 时清理用户状态并跳转登录页；返回 403 时先校验会话，区分登录过期与权限不足。

在组件中读取用户信息：

```ts
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const { user, isAuthenticated } = storeToRefs(userStore)
```

认证与路由测试使用 Node 内置测试运行器及模拟接口，不依赖在线后端：

```sh
pnpm test
```

## 从 Swagger 生成 API

已接入 alova 官方推荐的 [worma](https://alova.js.org/zh-CN/tutorial/getting-started/openapi-integration/)（`wormajs`），支持 Swagger 2 / OpenAPI 3 的 JSON、YAML 文档。配置位于 `worma.config.ts`，默认读取本项目 Springdoc 提供的 `http://localhost:8080/v3/api-docs`，无需额外配置后端。

在后端启动后，从 `src/client` 目录运行：

```sh
pnpm api:generate
```

生成的调用函数按 Swagger tag 分组保存在 `src/api/generated/services/`，模型类型保存在 `src/api/generated/components.d.ts`。函数名由 `operationId` 转为 camelCase，目前生成以下接口，并从 `@/api` 提供便捷导出：

| 函数       | HTTP 接口                     |
| ---------- | ----------------------------- |
| `login`    | `POST /api/v1/auth/login`     |
| `register` | `POST /api/v1/users/register` |
| `me`       | `GET /api/v1/users/me`        |

```ts
import { login } from '@/api'
import type { AuthLoginReq } from '@/api'

const credentials: AuthLoginReq = { username: 'alice', password: 'password' }
const response = await login({ data: credentials })
console.log(response.content?.user?.displayName)
```

也可以直接从生成模块导入，例如 `import { login } from '@/api/generated/services/authenticateController'`。后续新增接口会出现在对应的生成模块中；需要从 `@/api` 使用时，在 `src/api/index.ts` 中补充导出。

生成入口 `src/api/generated/index.ts` 复用 `src/api/instance.ts` 中的实例。Swagger 路径中的 `/api` 前缀在生成时移除，由实例的 `VITE_API_BASE_URL` 提供，避免请求变成 `/api/api/v1/...`；自定义地址也应包含 API 前缀，例如 `https://example.com/api`。生成配置兼容 Springdoc 的 `*/*` 响应媒体类型，响应保留后端的完整 `{ content, statusCode, message }` 结构。

要改用其他文档地址或离线文件，将 `.env.example` 复制为 `.env.local`，设置 `OPENAPI_INPUT` 为 Swagger/OpenAPI 文档的 URL 或相对于 `src/client` 的文件路径，例如：

```dotenv
OPENAPI_INPUT=./openapi/circlechat.json
```

`OPENAPI_INPUT` 仅用于生成，不进入前端包，也不改变运行时请求地址。生成配置读取 `.env`、`.env.local`、`.env.development` 和 `.env.development.local`；同名的进程环境变量优先。

后端接口更新后再次运行 `pnpm api:generate`，并将生成源码与业务代码一起提交。`pnpm dev` 和 `pnpm build` 使用已提交的源码，不依赖后端在线。缓存与变更记录 `.worma-cache/` 已忽略；需要强制更新时运行 `pnpm api:generate -f`。

`src/api/generated/index.ts` 和 `src/api/generated/services/index.ts` 是 worma 只生成一次、后续保留的文件，可分别用于实例接入和接口默认配置；其他生成文件应通过 Swagger 文档和生成配置更新。生成目录不参与 lint，但仍参与 TypeScript 检查。`pnpm-workspace.yaml` 允许生成器依赖 `esbuild` 和现有前端依赖 `@parcel/watcher` 的安装构建脚本。

官方文档：[worma 安装与配置](https://worma.js.org/docs/guide/installation-config)、[alova 生成模板](https://worma.js.org/docs/template-system/predefined-templates)。

## Tailwind CSS

已通过官方 `@tailwindcss/vite` 插件接入 Tailwind CSS 4，样式入口为 `src/assets/main.css`，可直接在 Vue 模板中使用工具类：

```vue
<template>
  <div class="flex items-center gap-3 p-4">
    <n-button type="primary">发送</n-button>
    <span class="text-sm text-gray-500">消息内容</span>
  </div>
</template>
```

`main.css` 通过 `@import 'tailwindcss'` 引入默认主题、Preflight 和工具类。Vue 模板自带的 `base.css`、全局布局/配色规则及 `App.vue` 的示例样式已移除；页面基础样式由 Tailwind Preflight 和 Naive UI 默认样式提供。

Tailwind 自动扫描源码中的完整类名。条件样式使用完整字符串（如 `active ? 'text-green-500' : 'text-gray-500'`），避免拼接 `text-${color}-500`。当前采用 CSS 配置方式，扩展主题可在样式入口中使用 `@theme`，无需额外创建 `tailwind.config.js` 或 PostCSS 配置。

官方文档：[Vite 安装](https://tailwindcss.com/docs/installation/using-vite)、[Preflight 配置](https://tailwindcss.com/docs/preflight)。

## SCSS

已安装 `sass-embedded`，Vite 可直接编译 `.scss` 文件和 Vue 的 `<style lang="scss" scoped>` 样式块，无需额外的 Vite 插件。

主题色为 `#00efe8`，统一定义在 `src/assets/_variables.scss` 的 `$primary-color` 中，并提供 `$primary-color-hover`、`$primary-color-pressed` 状态变量。`theme.module.scss` 将这些颜色导出给 `App.vue` 的 Naive UI 主题配置，明暗模式共用同一主色。

全局样式 `src/assets/main.scss` 提供 CSS 变量 `--color-primary` 和文字颜色类 `text-primary`，可直接使用 `<span class="text-primary">主题色文字</span>`。在组件 SCSS 中可通过 `@use '@/assets/variables' as theme;` 引入变量，例如 `color: theme.$primary-color;`。

`$font-family-sans` 定义全局字体串，包含苹方（PingFang SC）、冬青黑体（Hiragino Sans GB）、微软雅黑（Microsoft YaHei）、思源黑体（Noto Sans CJK SC / Source Han Sans SC）和文泉驿微米黑（WenQuanYi Micro Hei），并保留系统西文字体和 Emoji 回退。该变量同步用于页面默认字体、Tailwind 的 `--font-sans` 和 Naive UI 的 `fontFamily`；浏览器按顺序使用本机已安装的字体。

后续业务样式可以使用 SCSS；Tailwind 的入口仍保持为 `main.css`，在普通 CSS 中处理 Tailwind 的导入和指令，SCSS 中编写独立的业务样式。

官方文档：[Vite CSS 预处理器](https://vite.dev/guide/features.html#css-pre-processors)、[Tailwind 与预处理器](https://tailwindcss.com/docs/compatibility#sass-less-and-stylus)。

### Compile and Hot-Reload for Development

```sh
pnpm dev
```

### Type-Check, Compile and Minify for Production

```sh
pnpm build
```

### Lint with [ESLint](https://eslint.org/)

```sh
pnpm lint
```
