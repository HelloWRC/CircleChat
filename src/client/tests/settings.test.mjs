import assert from 'node:assert/strict'
import { after, before, beforeEach, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import { createSSRApp, h, onServerPrefetch } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { createPinia } from 'pinia'
import { NMessageProvider } from 'naive-ui'

const require = createRequire(import.meta.url)
const { setup: setupStyles } = require(
  require.resolve('@css-render/vue3-ssr', {
    paths: [require.resolve('naive-ui')],
  }),
)
const originalFetch = globalThis.fetch
const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
const account = {
  id: 1,
  username: 'alice',
  displayName: 'Alice',
  email: 'alice@example.com',
  avatarLargeUrl: 'https://example.com/avatar.png',
}
const key = 'circlechat.user'
let server, useUserStore, Settings, api, HttpError, requests, respond, storage

const json = (body, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
const userResponse = (user = account) => json({ statusCode: 200, content: { user }, message: 'ok' })
const tick = () => new Promise((resolve) => setImmediate(resolve))

before(async () => {
  server = await createServer({
    configFile: false,
    root: fileURLToPath(new URL('../', import.meta.url)),
    plugins: [vue(), AutoImport({ imports: ['vue', { 'naive-ui': ['useMessage'] }], dts: false })],
    resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: 'custom',
  })
  ;({ useUserStore } = await server.ssrLoadModule('/src/stores/user.ts'))
  ;({ default: Settings } = await server.ssrLoadModule('/src/views/settings/base.vue'))
  ;({ api, HttpError } = await server.ssrLoadModule('/src/api/instance.ts'))
})

beforeEach(() => {
  requests = []
  storage = new Map()
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (name) => storage.get(name) ?? null,
      setItem: (name, value) => storage.set(name, value),
      removeItem: (name) => storage.delete(name),
    },
  })
  respond = () => json({ statusCode: 200, content: null, message: 'ok' })
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/auth/login')) return userResponse()
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

async function session() {
  const pinia = createPinia()
  const user = useUserStore(pinia)
  await user.login({ username: 'alice', password: 'old-password' })
  return { pinia, user }
}

// Exercise the component's submit handlers and rules through its existing SSR test setup.
function formRef(model, rules) {
  return {
    async validate() {
      for (const [field, rule] of Object.entries(rules)) {
        const value = model[field]
        if (rule.required && !value) throw new Error(rule.message || 'required')
        const result = rule.validator?.(rule, value)
        if (result instanceof Error) throw result
      }
    },
    restoreValidation() {},
  }
}

async function renderSettings(context, prepare = () => {}) {
  const component = {
    ...Settings,
    setup(props, ctx) {
      const view = Settings.setup(props, ctx)
      onServerPrefetch(async () => {
        view.profileFormRef.value = formRef(view.profile, view.profileRules)
        view.passwordFormRef.value = formRef(view.passwords, view.passwordRules)
        await prepare(view)
      })
      return view
    },
  }
  const app = createSSRApp({
    render: () => h(NMessageProvider, null, { default: () => h(component) }),
  })
  app.use(context.pinia)
  setupStyles(app)
  return renderToString(app)
}

test('设置页显示大头像、只读账号与邮箱、安全的新窗口链接及关联输入标签', async () => {
  const html = await renderSettings(await session())
  assert.match(html, /账户设置/)
  assert.match(html, /https:\/\/example.com\/avatar.png/)
  assert.match(html, /alice@example.com/)
  assert.match(html, /用户名：alice/)
  assert.match(html, /头像通过 Gravatar 获取，请使用本账户邮箱设置/)
  assert.match(
    html,
    /href="https:\/\/gravatar.com\/"[^>]*target="_blank"[^>]*rel="noopener noreferrer"/,
  )
  for (const id of ['display-name', 'current-password', 'new-password', 'confirm-password']) {
    assert.match(html, new RegExp(`for="settings-${id}"`))
    assert.match(html, new RegExp(`id="settings-${id}"`))
  }
  assert.match(html, /autocomplete="current-password"/)
  assert.match(html, /autocomplete="new-password"/)
  assert.equal(requests.length, 0)
})

test('保存名称使用 PATCH 和 Cookie，立即更新 Store 与缓存', async () => {
  const context = await session()
  const updated = { ...account, displayName: '新名称' }
  respond = () => userResponse(updated)
  await renderSettings(context, async (view) => {
    assert.equal(view.profile.displayName, account.displayName)
    view.profile.displayName = '  新名称  '
    await view.saveProfile()
    assert.equal(view.profile.displayName, '新名称')
  })
  assert.equal(context.user.displayName, '新名称')
  assert.deepEqual(JSON.parse(storage.get(key)), updated)
  assert.equal(requests[0].url, '/api/v1/users/me')
  assert.equal(requests[0].options.method, 'PATCH')
  assert.equal(requests[0].options.credentials, 'include')
  assert.deepEqual(JSON.parse(requests[0].options.body), { displayName: '新名称' })
})

test('空白及超长名称在提交前拒绝，服务端失败保留编辑内容与已有缓存', async () => {
  const context = await session()
  respond = () => json({ statusCode: 400, message: '显示名称不正确' }, 400)
  const html = await renderSettings(context, async (view) => {
    for (const name of ['', '   ', '名'.repeat(65)]) {
      view.profile.displayName = name
      await view.saveProfile()
      assert.equal(requests.length, 0)
    }
    view.profile.displayName = '修改中'
    await view.saveProfile()
    assert.equal(view.profile.displayName, '修改中')
    assert.equal(view.profileError.value, '显示名称不正确')
    assert.equal(view.savingProfile.value, false)
  })
  assert.match(html, /role="alert"[^>]*>显示名称不正确/)
  assert.deepEqual(JSON.parse(storage.get(key)), account)
  assert.equal(context.user.displayName, 'Alice')
})

test('迟到的资料刷新不会覆盖保存后的显示名称', async () => {
  const context = await session()
  let resolveRefresh
  respond = (_url, options) =>
    options.method === 'GET'
      ? new Promise((resolve) => {
          resolveRefresh = resolve
        })
      : userResponse({ ...account, displayName: 'Updated' })
  const refreshing = context.user.refreshUser()
  await tick()
  await context.user.updateProfile('Updated')
  resolveRefresh(userResponse())
  await refreshing
  assert.equal(context.user.displayName, 'Updated')
  assert.equal(JSON.parse(storage.get(key)).displayName, 'Updated')
})

test('退出登录后迟到的保存响应不会恢复账户缓存', async () => {
  const context = await session()
  let resolveUpdate
  respond = (_url, options) =>
    options.method === 'PATCH'
      ? new Promise((resolve) => {
          resolveUpdate = resolve
        })
      : new Response(null, { status: 204 })
  const saving = context.user.updateProfile('Updated')
  const rejected = assert.rejects(saving, /登录状态已改变/)
  await tick()
  await context.user.logout()
  resolveUpdate(userResponse({ ...account, displayName: 'Updated' }))
  await rejected
  assert.equal(context.user.isAuthenticated, false)
  assert.equal(context.user.user, null)
  assert.equal(storage.has(key), false)
})

test('名称与密码各自防止重复提交，两个表单可独立保存', async () => {
  const context = await session()
  let resolveProfile
  respond = (_url, options) =>
    options.method === 'PATCH'
      ? new Promise((resolve) => {
          resolveProfile = resolve
        })
      : json({ statusCode: 200, content: null })
  await renderSettings(context, async (view) => {
    view.profile.displayName = 'Updated'
    const saving = view.saveProfile()
    await tick()
    await view.saveProfile()
    view.passwords.currentPassword = 'old-password'
    view.passwords.newPassword = 'x'
    view.passwords.confirmPassword = 'x'
    await Promise.all([view.savePassword(), view.savePassword()])
    assert.equal(requests.length, 2)
    assert.equal(view.savingProfile.value, true)
    assert.equal(view.savingPassword.value, false)
    resolveProfile(userResponse({ ...account, displayName: 'Updated' }))
    await saving
    assert.equal(view.savingProfile.value, false)
  })
})

test('密码为空、确认不一致或超出 UTF-8 字节限制时不发请求', async () => {
  await renderSettings(await session(), async (view) => {
    view.passwords.currentPassword = 'old-password'
    for (const [password, confirmation] of [
      ['', ''],
      ['x', 'y'],
      ['密'.repeat(25), '密'.repeat(25)],
    ]) {
      view.passwords.newPassword = password
      view.passwords.confirmPassword = confirmation
      await view.savePassword()
      assert.equal(requests.length, 0)
      assert.equal(view.savingPassword.value, false)
    }
    view.passwords.currentPassword = ''
    view.passwords.newPassword = 'x'
    view.passwords.confirmPassword = 'x'
    await view.savePassword()
    assert.equal(requests.length, 0)
  })
})

test('成功改密保留空白、清空所有密码字段并保持登录，密码不进入缓存', async () => {
  const context = await session()
  await renderSettings(context, async (view) => {
    view.passwords.currentPassword = 'old-password'
    view.passwords.newPassword = ' x '
    view.passwords.confirmPassword = ' x '
    await view.savePassword()
    assert.deepEqual(view.passwords, { currentPassword: '', newPassword: '', confirmPassword: '' })
  })
  assert.equal(requests[0].url, '/api/v1/users/me/password')
  assert.equal(requests[0].options.method, 'PUT')
  assert.equal(requests[0].options.credentials, 'include')
  assert.deepEqual(JSON.parse(requests[0].options.body), {
    currentPassword: 'old-password',
    newPassword: ' x ',
  })
  assert.equal(context.user.isAuthenticated, true)
  assert.deepEqual(JSON.parse(storage.get(key)), account)
})

test('改密错误展示服务器原因并保留字段，网络失败可重试', async () => {
  const context = await session()
  respond = () => json({ statusCode: 400, message: '当前密码不正确' }, 400)
  await renderSettings(context, async (view) => {
    view.passwords.currentPassword = 'wrong-password'
    view.passwords.newPassword = 'x'
    view.passwords.confirmPassword = 'x'
    await view.savePassword()
    assert.equal(view.passwordError.value, '当前密码不正确')
    assert.equal(view.passwords.newPassword, 'x')
    respond = () => {
      throw new TypeError('Network unavailable')
    }
    await view.savePassword()
    assert.match(view.passwordError.value, /检查网络/)
    assert.equal(view.passwords.currentPassword, 'wrong-password')
    respond = () => json({ statusCode: 200, content: null })
    await view.savePassword()
    assert.equal(view.passwordError.value, '')
    assert.equal(view.passwords.newPassword, '')
  })
  assert.equal(requests.length, 3)
  assert.equal(context.user.isAuthenticated, true)
})

test('72 个 UTF-8 字节的新密码可提交，确认密码不发送到服务器', async () => {
  await renderSettings(await session(), async (view) => {
    view.passwords.currentPassword = 'old-password'
    view.passwords.newPassword = '密'.repeat(24)
    view.passwords.confirmPassword = view.passwords.newPassword
    await view.savePassword()
  })
  assert.equal(requests.length, 1)
  assert.equal(JSON.parse(requests[0].options.body).newPassword, '密'.repeat(24))
  assert.equal('confirmPassword' in JSON.parse(requests[0].options.body), false)
})

test('HTTP 错误使用服务端 message，非 JSON 和空 message 保留回退', async () => {
  for (const response of [
    json({ message: '当前密码不正确' }, 400),
    new Response('<html>Error</html>', { status: 503 }),
    json({ message: '' }, 400),
  ]) {
    respond = () => response
    const expected =
      response.status === 400 && response.headers.get('content-type') === 'application/json'
        ? (await response.clone().json()).message || 'HTTP 400:'
        : 'HTTP 503:'
    await assert.rejects(
      api.Put('/v1/users/me/password', {}).send(),
      (error) => error instanceof HttpError && error.message.startsWith(expected),
    )
  }
})
