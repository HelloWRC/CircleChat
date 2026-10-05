import assert from 'node:assert/strict'
import { after, before, beforeEach, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import { createPinia } from 'pinia'
import { createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { NMessageProvider, NLayout, NLayoutContent, NLayoutSider, NAvatar } from 'naive-ui'

let server, useConversationsStore, useUserStore, routes, requests, respond
const originalFetch = globalThis.fetch
const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
const json = (content, status = 200) =>
  new Response(JSON.stringify(content), {
    status,
    headers: { 'content-type': 'application/json' },
  })
const entry = (id, title = `会话 ${id}`) => ({
  id,
  title,
  type: 'Chatroom',
  isMuted: false,
  hasNewMessage: false,
})
const page = (number, entries, hasMore = false) =>
  json({
    statusCode: 200,
    content: { page: number, size: 20, conversations: entries, hasMore, totalElements: 3 },
  })

before(async () => {
  server = await createServer({
    configFile: false,
    root: fileURLToPath(new URL('../', import.meta.url)),
    plugins: [
      vue(),
      AutoImport({ imports: ['vue', 'vue-router', { 'naive-ui': ['useMessage'] }], dts: false }),
    ],
    resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: 'custom',
  })
  ;({ useConversationsStore } = await server.ssrLoadModule('/src/stores/conversations.ts'))
  ;({ useUserStore } = await server.ssrLoadModule('/src/stores/user.ts'))
  ;({ routes } = await server.ssrLoadModule('/src/router/routes.ts'))
})
beforeEach(() => {
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: { getItem: () => null, setItem() {}, removeItem() {} },
  })
  requests = []
  respond = () => page(0, [entry(42), entry(0, '主聊天室')])
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/auth/login'))
      return json({ statusCode: 200, content: { user: { id: 1, username: 'alice' } } })
    requests.push({ url, options })
    return respond(url, options)
  }
})
after(async () => {
  await server?.close()
  globalThis.fetch = originalFetch
  if (originalStorage) Object.defineProperty(globalThis, 'localStorage', originalStorage)
  else delete globalThis.localStorage
})
async function context() {
  const pinia = createPinia()
  const user = useUserStore(pinia)
  await user.login({ username: 'alice', password: 'secret' })
  return { pinia, user, store: useConversationsStore(pinia) }
}

test('会话按页获取、去重并携带 Session，失败后从同一页重试', async () => {
  const { store } = await context()
  respond = () => page(0, [entry(42), entry(7)], true)
  await store.loadMore()
  assert.match(requests[0].url, /page=0/)
  assert.match(requests[0].url, /size=20/)
  assert.equal(requests[0].options.credentials, 'include')
  respond = () => json({}, 503)
  await store.loadMore()
  assert.ok(store.error)
  assert.deepEqual(
    store.conversations.map((item) => item.id),
    [42, 7],
  )
  respond = () => page(1, [entry(7), entry(0)], false)
  await store.loadMore()
  assert.match(requests.at(-1).url, /page=1/)
  assert.deepEqual(
    store.conversations.map((item) => item.id),
    [42, 7, 0],
  )
  assert.equal(store.error, null)
  assert.equal(store.hasMore, false)
  const count = requests.length
  await store.loadMore()
  assert.equal(requests.length, count)
})

test('登出清除会话和各会话草稿，迟到的列表响应不能恢复数据', async () => {
  const { user, store } = await context()
  let finish
  let notifyStarted
  const started = new Promise((resolve) => {
    notifyStarted = resolve
  })
  respond = () =>
    new Promise((resolve) => {
      finish = resolve
      notifyStarted()
    })
  const loading = store.loadMore()
  await started
  await store.loadMore()
  assert.equal(requests.length, 1)
  store.drafts['42'] = '保留在会话 42 的草稿'
  store.drafts['0'] = '主会话草稿'
  user.clearSession()
  finish(page(0, [entry(42)]))
  await loading
  assert.deepEqual(store.conversations, [])
  assert.deepEqual(store.drafts, {})
  assert.equal(store.isLoading, false)
})

test('列表链接进入 /chat/{id}，选择状态、标题和草稿跟随路由', async () => {
  const { pinia, store } = await context()
  respond = () =>
    page(0, [
      entry(42, '开发讨论'),
      { ...entry(0, '主聊天室'), hasNewMessage: true, isMuted: true },
    ])
  await store.loadMore()
  store.drafts['42'] = '开发草稿'
  store.drafts['0'] = '主会话草稿'
  const router = createRouter({ history: createMemoryHistory(), routes })
  const app = createSSRApp({
    render: () => h(NMessageProvider, null, { default: () => h(RouterView) }),
  })
  app.use(pinia).use(router)
  for (const [name, component] of Object.entries({
    NLayout,
    NLayoutContent,
    NLayoutSider,
    NAvatar,
  }))
    app.component(name, component)
  // Popovers require the browser DOM; this test covers the real conversation links and layouts.
  app.component('NDropdown', {
    inheritAttrs: false,
    emits: ['select'],
    setup:
      (_props, { slots }) =>
      () =>
        slots.default?.(),
  })
  app.component('NMenu', { setup: () => () => h('nav') })
  await router.push('/chat/42')
  assert.equal(router.currentRoute.value.params.id, '42')
  let html = await renderToString(app)
  assert.match(
    html.match(/<a[^>]*>/g).find((tag) => tag.includes('href="/chat/42"')),
    /aria-current="page"/,
  )
  assert.ok(html.includes('开发讨论'))
  assert.ok(html.includes('开发草稿'))
  assert.ok(html.includes('有未读消息'))
  assert.ok(html.includes('已静音'))
  await router.push('/chat/0')
  html = await renderToString(app)
  assert.match(
    html.match(/<a[^>]*>/g).find((tag) => tag.includes('href="/chat/0"')),
    /aria-current="page"/,
  )
  assert.ok(html.includes('主会话草稿'))
  assert.equal(store.drafts['42'], '开发草稿')
})

test('每次打开会话都请求 meta，刷新标题及已加载列表中的状态', async () => {
  const { store } = await context()
  respond = () => page(0, [entry(42, '缓存标题')])
  await store.loadMore()
  respond = () =>
    json({ statusCode: 200, content: { info: { ...entry(42, '最新标题'), isMuted: true } } })
  assert.equal(await store.loadCurrent(42), true)
  assert.ok(requests.at(-1).url.endsWith('/api/v1/conversations/42/meta'))
  assert.equal(requests.at(-1).options.credentials, 'include')
  assert.equal(store.title(42), '最新标题')
  assert.equal(store.conversations[0].isMuted, true)
  respond = () => json({ statusCode: 200, content: { info: entry(42, '再次更新') } })
  assert.equal(await store.loadCurrent(42), true)
  assert.equal(store.title(42), '再次更新')
  assert.equal(requests.length, 3)
})

test('直接打开未加载到分页列表的会话，标题来自 meta 且不改变分页列表', async () => {
  const { store } = await context()
  await store.loadMore()
  respond = () => json({ statusCode: 200, content: { info: entry(900, '独立会话标题') } })
  assert.equal(await store.loadCurrent(900), true)
  assert.equal(store.title(900), '独立会话标题')
  assert.deepEqual(
    store.conversations.map((item) => item.id),
    [42, 0],
  )
  assert.equal(store.total, 3)
})

test('meta 失败可以重试，拒绝错误会话响应并保留草稿', async () => {
  const { store } = await context()
  store.drafts['42'] = '保留草稿'
  for (const status of [403, 404, 503]) {
    respond = () => json({}, status)
    assert.equal(await store.loadCurrent(42), false)
    assert.ok(store.currentError)
    assert.equal(store.currentConversation, null)
    assert.equal(store.isLoadingCurrent, false)
  }
  respond = () => json({ statusCode: 200, content: { info: entry(7) } })
  assert.equal(await store.loadCurrent(42), false)
  respond = () => json({ statusCode: 200, content: { info: entry(42, '重试成功') } })
  assert.equal(await store.loadCurrent(42), true)
  assert.equal(store.currentError, null)
  assert.equal(store.title(42), '重试成功')
  assert.equal(store.drafts['42'], '保留草稿')
})

test('快速切换会话后，旧 meta 响应不能覆盖当前会话信息', async () => {
  const { store } = await context()
  let finish, notifyStarted
  const started = new Promise((resolve) => {
    notifyStarted = resolve
  })
  respond = (url) =>
    url.endsWith('/42/meta')
      ? new Promise((resolve) => {
          finish = resolve
          notifyStarted()
        })
      : json({ statusCode: 200, content: { info: entry(7, '当前会话') } })
  const oldRequest = store.loadCurrent(42)
  await started
  assert.equal(await store.loadCurrent(7), true)
  finish(json({ statusCode: 200, content: { info: entry(42, '旧会话') } }))
  assert.equal(await oldRequest, false)
  assert.equal(store.currentConversation.id, 7)
  assert.equal(store.title(7), '当前会话')
  assert.equal(store.currentError, null)
})

test('登出后迟到的 meta 响应不能恢复会话信息', async () => {
  const { user, store } = await context()
  let finish, notifyStarted
  const started = new Promise((resolve) => {
    notifyStarted = resolve
  })
  respond = () =>
    new Promise((resolve) => {
      finish = resolve
      notifyStarted()
    })
  const loading = store.loadCurrent(42)
  await started
  user.clearSession()
  finish(json({ statusCode: 200, content: { info: entry(42) } }))
  assert.equal(await loading, false)
  assert.equal(store.currentConversation, null)
  assert.equal(store.currentError, null)
  assert.equal(store.isLoadingCurrent, false)
})

test('较晚到达的分页列表不能覆盖 meta 获取的新信息', async () => {
  const { store } = await context()
  let finish, notifyStarted
  const started = new Promise((resolve) => {
    notifyStarted = resolve
  })
  respond = (url) =>
    url.endsWith('/42/meta')
      ? json({ statusCode: 200, content: { info: entry(42, '最新标题') } })
      : new Promise((resolve) => {
          finish = resolve
          notifyStarted()
        })
  const loadingList = store.loadMore()
  await started
  assert.equal(await store.loadCurrent(42), true)
  finish(page(0, [entry(42, '旧列表标题')]))
  await loadingList
  assert.equal(store.conversations[0].title, '最新标题')
})
