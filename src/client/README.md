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

Alova 实例导出于 `src/api/index.ts`，使用 Vue 状态适配器和 Fetch 请求适配器，默认超时 10 秒、禁用 GET 缓存。HTTP 错误会抛出异常，成功响应按 Content-Type 解析为 JSON 或文本，空响应返回 `undefined`。

默认请求前缀为 `/api`，开发环境沿用 Vite 到 `http://localhost:8080` 的代理。需要更改时，将 `.env.example` 复制为 `.env.local` 并设置 `VITE_API_BASE_URL`；生产环境需由部署服务提供 `/api` 路由，或在构建前配置完整 API 地址。

下面是后续新增 HTTP 接口时的用法示例（当前后端只提供 STOMP 消息接口，尚无 `/api/example`）：

```vue
<script setup lang="ts">
import { api } from '@/api'

const message = useMessage()
const { loading, data, send, onError } = useRequest(() => api.Get<string>('/example'), {
  immediate: false,
})

onError(({ error }) => message.error(error.message))
</script>

<template>
  <n-button :loading="loading" @click="send()">加载</n-button>
  <p>{{ data }}</p>
</template>
```

官方文档：[Naive UI](https://www.naiveui.com/zh-CN/os-theme/docs/introduction)、[Alova](https://alova.js.org/zh-CN/tutorial/getting-started/quick-start/)、[unplugin-auto-import](https://github.com/unplugin/unplugin-auto-import)、[unplugin-vue-components](https://github.com/unplugin/unplugin-vue-components)。

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

已安装 `sass-embedded`，Vite 可直接编译 `.scss` 文件和 Vue 的 `<style lang="scss" scoped>` 样式块，无需额外的 Vite 插件。当前未添加自定义 SCSS 样式。

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
