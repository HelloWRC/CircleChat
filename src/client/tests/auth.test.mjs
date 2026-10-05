import assert from 'node:assert/strict'
import { after, before, beforeEach, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import { createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { NAvatar, NLayout, NLayoutContent, NLayoutSider, NMessageProvider } from 'naive-ui'

const key = 'circlechat.user'
const account = {
  id: 1,
  username: 'alice',
  displayName: 'Alice',
  avatarUrl: 'https://example.com/alice.png',
  avatarSmallUrl: 'https://example.com/alice-small.png',
  avatarLargeUrl: 'https://example.com/alice-large.png',
}
const originalFetch = globalThis.fetch
const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
let server
let useUserStore
let setupAuthGuard
let getLoginRedirect
let api
let HttpError
let register
let routes
let storage
let requests
let respond

function json(content, status = 200) {
  return new Response(JSON.stringify(content), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

function userResponse(user = account) {
  return json({ statusCode: 200, content: { user }, message: 'ok' })
}

function context() {
  const pinia = createPinia()
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [...routes, { path: '/room/:id', name: 'room', component: {} }],
  })
  setupAuthGuard(router, pinia)
  return { router, pinia, user: useUserStore(pinia) }
}

async function renderSidebar(session) {
  const app = createSSRApp({
    render: () => h(NMessageProvider, null, { default: () => h(RouterView) }),
  })
  app.use(session.pinia)
  app.use(session.router)
  for (const [name, component] of Object.entries({
    NAvatar,
    NLayout,
    NLayoutContent,
    NLayoutSider,
  })) {
    app.component(name, component)
  }
  // Menu popovers depend on the browser DOM; keep the real avatar for this rendering regression.
  app.component('NDropdown', {
    inheritAttrs: false,
    emits: ['select'],
    setup: (_props, { slots, emit }) => {
      session.selectUserMenu = (key) => emit('select', key)
      return () => slots.default?.()
    },
  })
  app.component('NMenu', {
    inheritAttrs: false,
    props: ['value', 'options'],
    emits: ['update:value'],
    setup: (props, { emit }) => {
      session.selectMenu = (key) => emit('update:value', key)
      return () =>
        h(
          'div',
          { 'data-active-menu': props.value ?? 'none' },
          props.options.map((option) => option.label),
        )
    },
  })
  return renderToString(app)
}

function nextLoginNavigation(router) {
  return new Promise((resolve) => {
    const remove = router.afterEach((to) => {
      if (to.name === 'auth.login') {
        remove()
        resolve()
      }
    })
  })
}

before(async () => {
  server = await createServer({
    configFile: false,
    root: fileURLToPath(new URL('../', import.meta.url)),
    plugins: [
      vue(),
      AutoImport({ imports: ['vue', 'vue-router', { 'naive-ui': ['useMessage'] }], dts: false }),
    ],
    resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, watch: null },
    appType: 'custom',
  })
  ;({ useUserStore } = await server.ssrLoadModule('/src/stores/user.ts'))
  ;({ setupAuthGuard, getLoginRedirect } = await server.ssrLoadModule('/src/router/guards.ts'))
  ;({ api, HttpError } = await server.ssrLoadModule('/src/api/instance.ts'))
  ;({ register } = await server.ssrLoadModule('/src/api/index.ts'))
  ;({ routes } = await server.ssrLoadModule('/src/router/routes.ts'))
})

beforeEach(() => {
  storage = new Map()
  requests = []
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (name) => storage.get(name) ?? null,
      setItem: (name, value) => storage.set(name, value),
      removeItem: (name) => storage.delete(name),
    },
  })
  respond = () => json({}, 403)
  globalThis.fetch = async (url, options) => {
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

test('未登录时守卫保留完整目标地址并跳转登录页', async () => {
  const { router, user } = context()
  await router.push('/room/42?tab=chat#latest')
  assert.equal(router.currentRoute.value.name, 'auth.login')
  assert.equal(router.currentRoute.value.query.redirect, '/room/42?tab=chat#latest')
  assert.equal(user.isAuthenticated, false)
  assert.equal(requests[0].options.credentials, 'include')
})

test('登录成功保存用户信息并携带 Cookie，密码不写入本地存储', async () => {
  respond = () => userResponse()
  const { user } = context()
  await user.login({ username: 'alice', password: 'secret' })
  assert.equal(user.isAuthenticated, true)
  assert.deepEqual(JSON.parse(storage.get(key)), account)
  assert.equal(storage.get(key).includes('secret'), false)
  assert.equal(requests[0].options.credentials, 'include')
  assert.deepEqual(JSON.parse(requests[0].options.body), { username: 'alice', password: 'secret' })
})

test('Pinia 恢复缓存中的全部头像 URL，服务端校验前不建立登录状态', () => {
  storage.set(key, JSON.stringify(account))
  const { user } = context()
  assert.deepEqual(user.user, account)
  assert.equal(user.avatarUrl, account.avatarSmallUrl)
  assert.equal(user.displayName, account.displayName)
  assert.equal(user.isAuthenticated, false)
})

test('头像按小图、默认图、大图顺序选择，空白 URL 不作为图片地址', async () => {
  const { user } = context()
  for (const [profile, expected] of [
    [account, account.avatarSmallUrl],
    [{ ...account, avatarSmallUrl: '  ' }, account.avatarUrl],
    [{ ...account, avatarSmallUrl: '', avatarUrl: '' }, account.avatarLargeUrl],
    [{ id: 1, username: 'alice' }, undefined],
  ]) {
    respond = () => userResponse(profile)
    await user.login({ username: 'alice', password: 'secret' })
    assert.equal(user.avatarUrl, expected)
  }
})

test('刷新用户头像同步更新 Store 和缓存，清理会话移除头像', async () => {
  respond = () => userResponse()
  const { user } = context()
  await user.login({ username: 'alice', password: 'secret' })
  const latest = { ...account, avatarSmallUrl: 'https://example.com/new-avatar.png' }
  respond = () => userResponse(latest)
  await user.refreshUser()
  assert.equal(user.avatarUrl, latest.avatarSmallUrl)
  assert.deepEqual(JSON.parse(storage.get(key)), latest)
  user.clearSession()
  assert.equal(user.user, null)
  assert.equal(user.avatarUrl, undefined)
  assert.equal(storage.has(key), false)
})

test('登出发送携带 Cookie 的 POST 请求并清除 Pinia 和本地缓存', async () => {
  respond = () => userResponse()
  const { user } = context()
  await user.login({ username: 'alice', password: 'secret' })
  requests = []
  respond = () => new Response(null, { status: 204 })
  await user.logout()
  assert.equal(requests.length, 1)
  assert.equal(requests[0].url, '/api/v1/auth/logout')
  assert.equal(requests[0].options.method, 'POST')
  assert.equal(requests[0].options.credentials, 'include')
  assert.equal(user.user, null)
  assert.equal(user.avatarUrl, undefined)
  assert.equal(user.isAuthenticated, false)
  assert.equal(user.isLoggingOut, false)
  assert.equal(storage.has(key), false)
  await user.restoreSession()
  assert.equal(requests.length, 1)
})

test('重复点击登出只发送一次请求，等待期间清除本地状态并禁止会话恢复', async () => {
  respond = () => userResponse()
  const { user } = context()
  await user.login({ username: 'alice', password: 'secret' })
  requests = []
  let resolveLogout
  respond = () => new Promise((resolve) => (resolveLogout = resolve))
  const first = user.logout()
  const second = user.logout()
  assert.equal(user.isLoggingOut, true)
  assert.equal(user.user, null)
  assert.equal(storage.has(key), false)
  await user.refreshUser()
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(requests.length, 1)
  resolveLogout(new Response(null, { status: 204 }))
  await Promise.all([first, second])
  assert.equal(user.isLoggingOut, false)
})

test('服务端登出失败仍清除本地状态，错误交由调用方处理并可重试', async () => {
  respond = () => userResponse()
  const { user } = context()
  await user.login({ username: 'alice', password: 'secret' })
  requests = []
  respond = () => json({}, 403)
  await assert.rejects(user.logout(), (error) => error instanceof HttpError && error.status === 403)
  assert.equal(user.isAuthenticated, false)
  assert.equal(user.initialized, true)
  assert.equal(user.isLoggingOut, false)
  assert.equal(storage.has(key), false)
  assert.equal(requests.length, 1)
  respond = () => new Response(null, { status: 204 })
  await user.logout()
  assert.equal(requests.length, 2)
})

test('登出后迟到的当前用户响应不会恢复用户信息或头像缓存', async () => {
  respond = () => userResponse()
  const { user } = context()
  await user.login({ username: 'alice', password: 'secret' })
  let resolveRefresh
  respond = (url) =>
    url.endsWith('/users/me')
      ? new Promise((resolve) => (resolveRefresh = resolve))
      : new Response(null, { status: 204 })
  const refreshing = user.refreshUser()
  await new Promise((resolve) => setImmediate(resolve))
  await user.logout()
  resolveRefresh(userResponse())
  await refreshing
  assert.equal(user.user, null)
  assert.equal(user.isAuthenticated, false)
  assert.equal(user.initialized, true)
  assert.equal(storage.has(key), false)
})

test('点击侧栏登出会调用服务端并跳转登录页', { timeout: 10000 }, async () => {
  respond = () => userResponse()
  const session = context()
  await session.router.push('/chat')
  await renderSidebar(session)
  requests = []
  respond = () => new Response(null, { status: 204 })
  const navigation = nextLoginNavigation(session.router)
  session.selectUserMenu('logout')
  await navigation
  assert.equal(session.router.currentRoute.value.name, 'auth.login')
  assert.equal(session.user.isAuthenticated, false)
  assert.equal(storage.has(key), false)
  assert.equal(requests.length, 1)
  assert.equal(requests[0].url, '/api/v1/auth/logout')
})

test('侧栏登出网络失败后仍退出本地登录并跳转登录页', { timeout: 10000 }, async () => {
  respond = () => userResponse()
  const session = context()
  await session.router.push('/chat')
  await renderSidebar(session)
  respond = () => {
    throw new Error('Network unavailable')
  }
  const navigation = nextLoginNavigation(session.router)
  session.selectUserMenu('logout')
  await navigation
  assert.equal(session.router.currentRoute.value.name, 'auth.login')
  assert.equal(session.user.isAuthenticated, false)
  assert.equal(storage.has(key), false)
})

test('侧栏渲染真实头像图片，昵称默认插槽不覆盖图片', async () => {
  respond = () => userResponse()
  const session = context()
  await session.router.push('/')
  const html = await renderSidebar(session)
  assert.ok(html.includes(`src="${account.avatarSmallUrl}"`))
  assert.ok(html.includes('打开 Alice 的用户菜单'))
})

test('首页自动跳转聊天路由并保留查询参数和锚点', async () => {
  respond = () => userResponse()
  const { router } = context()
  await router.push('/?tab=latest#messages')
  assert.equal(router.currentRoute.value.name, 'chat.conversation')
  assert.equal(router.currentRoute.value.fullPath, '/chat/0?tab=latest#messages')
})

test('未登录访问首页时登录回跳地址为聊天页', async () => {
  const { router, user } = context()
  await router.push('/')
  assert.equal(router.currentRoute.value.name, 'auth.login')
  assert.equal(router.currentRoute.value.query.redirect, '/chat/0')
  respond = () => userResponse()
  await user.login({ username: 'alice', password: 'secret' })
  await router.replace(getLoginRedirect(router, router.currentRoute.value.query.redirect))
  assert.equal(router.currentRoute.value.fullPath, '/chat/0')
})

test('侧栏菜单选择跳转真实聊天路由，选中状态跟随当前页面', { timeout: 3000 }, async () => {
  respond = () => userResponse()
  const session = context()
  await session.router.push('/chat')
  let html = await renderSidebar(session)
  assert.match(html, /data-active-menu="chat"/)
  assert.match(html, /href="\/chat"/)
  await session.router.push('/settings')
  html = await renderSidebar(session)
  assert.equal(session.router.currentRoute.value.name, 'settings')
  assert.match(html, /data-active-menu="none"/)
  const navigation = new Promise((resolve) => {
    const remove = session.router.afterEach((to) => {
      if (to.name === 'chat.conversation') {
        remove()
        resolve()
      }
    })
  })
  session.selectMenu('chat')
  await navigation
  assert.equal(session.router.currentRoute.value.fullPath, '/chat/0')
  html = await renderSidebar(session)
  assert.match(html, /data-active-menu="chat"/)
})

test('未设置头像时侧栏显示昵称首字', async () => {
  respond = () => userResponse({ id: 1, username: 'alice', displayName: '小明' })
  const session = context()
  await session.router.push('/')
  const html = await renderSidebar(session)
  assert.match(html.replace(/<!--.*?-->/g, ''), /n-avatar__text[^>]*>小<\/span>/)
  assert.ok(!html.includes('data-image-src='))
})

test('密码错误不创建登录状态', async () => {
  respond = () => json({}, 401)
  const { user } = context()
  await assert.rejects(user.login({ username: 'alice', password: 'wrong' }), (error) => {
    assert.ok(error instanceof HttpError)
    assert.equal(error.status, 401)
    return true
  })
  assert.equal(user.isAuthenticated, false)
  assert.equal(storage.has(key), false)
})

test('无有效用户的登录响应不被当作成功', async () => {
  respond = () => json({ statusCode: 200, content: {} })
  const { user } = context()
  await assert.rejects(user.login({ username: 'alice', password: 'secret' }))
  assert.equal(user.isAuthenticated, false)
})

test('刷新后校验 Session 并使用服务端最新用户信息', async () => {
  storage.set(key, JSON.stringify(account))
  const latest = { ...account, displayName: 'Updated Alice' }
  respond = () => userResponse(latest)
  const { router, user } = context()
  assert.equal(user.isAuthenticated, false)
  await router.push('/')
  assert.equal(router.currentRoute.value.name, 'chat.conversation')
  assert.equal(user.user.displayName, latest.displayName)
  assert.deepEqual(JSON.parse(storage.get(key)), latest)
})

test('本地缓存不能绕过已过期的 Session', async () => {
  storage.set(key, JSON.stringify(account))
  const { router, user } = context()
  await router.push('/')
  assert.equal(router.currentRoute.value.name, 'auth.login')
  assert.equal(user.user, null)
  assert.equal(storage.has(key), false)
})

test('本地数据损坏时仍可通过有效 Cookie 恢复登录', async () => {
  storage.set(key, '{broken json')
  respond = () => userResponse()
  const { router, user } = context()
  await router.push('/')
  assert.equal(user.isAuthenticated, true)
  assert.equal(router.currentRoute.value.name, 'chat.conversation')
})

test('浏览器禁止本地存储时登录仍可使用', async () => {
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    get() {
      throw new Error('Storage unavailable')
    },
  })
  respond = () => userResponse()
  const { user } = context()
  await user.login({ username: 'alice', password: 'secret' })
  assert.equal(user.isAuthenticated, true)
})

test('并发恢复只请求一次当前用户接口', async () => {
  let resolveResponse
  respond = () => new Promise((resolve) => (resolveResponse = resolve))
  const { user } = context()
  const first = user.restoreSession()
  const second = user.restoreSession()
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(requests.length, 1)
  resolveResponse(userResponse())
  await Promise.all([first, second])
  assert.equal(user.isAuthenticated, true)
})

test('登录后返回原目标，已登录访问登录页也自动返回', async () => {
  const { router, user } = context()
  await router.push('/room/42?tab=chat#latest')
  respond = () => userResponse()
  await user.login({ username: 'alice', password: 'secret' })
  await router.replace(getLoginRedirect(router, router.currentRoute.value.query.redirect))
  assert.equal(router.currentRoute.value.fullPath, '/room/42?tab=chat#latest')
  await router.push('/auth/login')
  assert.equal(router.currentRoute.value.name, 'chat.conversation')
})

test('登录回跳拒绝外部地址和登录页循环', () => {
  const { router } = context()
  for (const redirect of [
    'https://example.com',
    '//example.com',
    '/auth/login',
    '/auth/register',
    undefined,
    ['/'],
  ]) {
    assert.equal(getLoginRedirect(router, redirect), '/')
  }
})

test('业务接口 401 清除用户信息并跳转登录页', { timeout: 3000 }, async () => {
  respond = () => userResponse()
  const { router, user } = context()
  await router.push('/room/42')
  respond = () => json({}, 401)
  const navigation = nextLoginNavigation(router)
  await assert.rejects(api.Get('/v1/private').send())
  await navigation
  assert.equal(user.isAuthenticated, false)
  assert.equal(storage.has(key), false)
  assert.equal(router.currentRoute.value.query.redirect, '/room/42')
})

test('业务接口 403 且 Session 过期时跳转登录页', { timeout: 3000 }, async () => {
  respond = () => userResponse()
  const { router, user } = context()
  await router.push('/')
  respond = () => json({}, 403)
  const navigation = nextLoginNavigation(router)
  await assert.rejects(api.Get('/v1/private').send())
  await navigation
  assert.equal(user.isAuthenticated, false)
  assert.equal(storage.has(key), false)
})

test('业务接口 403 但 Session 有效时保留登录', async () => {
  respond = () => userResponse()
  const { router, user } = context()
  await router.push('/')
  respond = (url) => (url.endsWith('/users/me') ? userResponse() : json({}, 403))
  await assert.rejects(api.Get('/v1/admin/private').send())
  await user.refreshUser()
  assert.equal(user.isAuthenticated, true)
  assert.equal(router.currentRoute.value.name, 'chat.conversation')
})

test('会话校验网络失败不放行受保护页，后续可重试恢复', async () => {
  storage.set(key, JSON.stringify(account))
  respond = () => {
    throw new Error('Network unavailable')
  }
  const { router, user } = context()
  await router.push('/')
  assert.equal(router.currentRoute.value.name, 'auth.login')
  assert.equal(user.isAuthenticated, false)
  respond = () => userResponse()
  await router.push('/room/42')
  assert.equal(router.currentRoute.value.name, 'room')
  assert.equal(user.isAuthenticated, true)
})

test('未登录可以访问注册页并保留登录后的目标地址', async () => {
  const { router, user } = context()
  await router.push({ name: 'auth.register', query: { redirect: '/room/42?tab=chat#latest' } })
  assert.equal(router.currentRoute.value.name, 'auth.register')
  assert.equal(router.currentRoute.value.query.redirect, '/room/42?tab=chat#latest')
  assert.equal(user.isAuthenticated, false)
})

test('已登录访问注册页自动返回目标页', async () => {
  respond = () => userResponse()
  const { router } = context()
  await router.push({ name: 'auth.register', query: { redirect: '/room/42' } })
  assert.equal(router.currentRoute.value.fullPath, '/room/42')
  await router.push('/auth/register')
  assert.equal(router.currentRoute.value.name, 'chat.conversation')
})

test('注册接口提交账户信息，注册成功后仍需登录', async () => {
  const { router, user } = context()
  await router.push('/auth/register')
  respond = () => json({ statusCode: 200, content: null, message: 'ok' })
  const data = {
    username: 'alice',
    displayName: 'Alice',
    email: 'alice@example.com',
    password: 'secret',
  }
  const response = await register({ data })
  const request = requests.at(-1)
  assert.equal(request.url, '/api/v1/users/register')
  assert.equal(request.options.method, 'POST')
  assert.deepEqual(JSON.parse(request.options.body), data)
  assert.equal(response.statusCode, 200)
  assert.equal(user.isAuthenticated, false)
  assert.equal(storage.has(key), false)
})

test('注册接口返回 403 时保留注册页并交由表单处理错误', async () => {
  const { router } = context()
  await router.push('/auth/register')
  await assert.rejects(register({ data: { username: 'alice', password: 'secret' } }))
  assert.equal(router.currentRoute.value.name, 'auth.register')
})
