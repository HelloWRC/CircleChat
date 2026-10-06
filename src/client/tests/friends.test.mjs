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
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { NAvatar, NLayout, NLayoutContent, NLayoutSider, NMessageProvider } from 'naive-ui'

// Naive UI's scrollable tabs inject CSS through its SSR adapter.
const require = createRequire(import.meta.url)
const { setup: setupStyles } = require(
  require.resolve('@css-render/vue3-ssr', {
    paths: [require.resolve('naive-ui')],
  }),
)

const originalFetch = globalThis.fetch
const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
let server,
  useUserStore,
  useFriendsStore,
  useConversationsStore,
  FriendsList,
  FriendDetails,
  NewFriends,
  routes,
  requests,
  respond
const bob = {
  userId: 2,
  username: 'bob',
  displayName: 'Bob',
  avatarUrl: 'https://example.com/bob.png',
  isFriend: true,
}
const json = (content, status = 200) =>
  new Response(JSON.stringify(content), {
    status,
    headers: { 'content-type': 'application/json' },
  })
const infoResponse = (friend = bob) => json({ statusCode: 200, content: friend })

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
  ;({ useUserStore } = await server.ssrLoadModule('/src/stores/user.ts'))
  ;({ useFriendsStore } = await server.ssrLoadModule('/src/stores/friends.ts'))
  ;({ useConversationsStore } = await server.ssrLoadModule('/src/stores/conversations.ts'))
  ;({ default: FriendsList } = await server.ssrLoadModule('/src/views/friends/base.vue'))
  ;({ default: FriendDetails } = await server.ssrLoadModule('/src/views/friends/Details.vue'))
  ;({ default: NewFriends } = await server.ssrLoadModule('/src/views/friends/New.vue'))
  ;({ routes } = await server.ssrLoadModule('/src/router/routes.ts'))
})

beforeEach(() => {
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: { getItem: () => null, setItem() {}, removeItem() {} },
  })
  requests = []
  respond = (url) =>
    url.endsWith('/friends/my')
      ? json({
          statusCode: 200,
          content: { friends: [bob, { ...bob, userId: 3, username: 'carol' }] },
        })
      : infoResponse()
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/auth/login')) {
      return json({ statusCode: 200, content: { user: { id: 1, username: 'alice' } } })
    }
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
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/friends', name: 'friends', component: { render: () => null } },
      { path: '/friends/new', name: 'friends.new', component: { render: () => null } },
      {
        path: '/friends/details/:username',
        name: 'friends.details',
        component: { render: () => null },
      },
      { path: '/chat/:id', name: 'chat.conversation', component: { render: () => null } },
    ],
  })
  return {
    pinia,
    user,
    router,
    store: useFriendsStore(pinia),
    conversations: useConversationsStore(pinia),
  }
}

// Load the real component's data before SSR without changing its browser lifecycle.
function prepared(component, prepare) {
  return {
    ...component,
    setup(props, ctx) {
      const bindings = component.setup(props, ctx)
      onServerPrefetch(() => prepare(bindings))
      return bindings
    },
  }
}

async function renderDetails(session, username = 'bob', prepare = (view) => view.loadDetails()) {
  await session.router.push({ name: 'friends.details', params: { username } })
  const app = createSSRApp({
    render: () =>
      h(NMessageProvider, null, {
        default: () => h(prepared(FriendDetails, prepare), { username }),
      }),
  })
  app.use(session.pinia)
  app.use(session.router)
  return renderToString(app)
}

test('好友详情子路由传递用户名，列表链接和侧栏高亮跟随当前好友', async () => {
  const session = await context()
  const testRoutes = routes.map((root) => ({
    ...root,
    children: root.children?.map((route) =>
      route.name === 'friends'
        ? {
            ...route,
            component: prepared(FriendsList, (view) => view.loadFriends()),
            children: route.children.map((child) => ({
              ...child,
              component:
                child.name === 'friends.details'
                  ? prepared(FriendDetails, (view) => view.loadDetails())
                  : prepared(NewFriends, (view) => view.loadRequests()),
            })),
          }
        : route,
    ),
  }))
  const router = createRouter({ history: createMemoryHistory(), routes: testRoutes })
  const app = createSSRApp({
    render: () => h(NMessageProvider, null, { default: () => h(RouterView) }),
  })
  app.use(session.pinia)
  app.use(router)
  for (const [name, component] of Object.entries({
    NAvatar,
    NLayout,
    NLayoutContent,
    NLayoutSider,
  })) {
    app.component(name, component)
  }
  app.component('NDropdown', {
    inheritAttrs: false,
    emits: ['select'],
    setup:
      (_props, { slots }) =>
      () =>
        slots.default?.(),
  })
  app.component('NMenu', {
    inheritAttrs: false,
    props: ['value'],
    setup: (props) => () => h('nav', { 'data-active-menu': props.value }),
  })
  await router.push('/friends/details/bob')
  assert.equal(router.currentRoute.value.name, 'friends.details')
  let html = await renderToString(app)
  const link = html.match(/<a[^>]*>/g).find((tag) => tag.includes('href="/friends/details/bob"'))
  assert.match(link, /aria-current="page"/)
  assert.match(link, /bg-\(--color-primary\)\/16/)
  assert.match(html, /data-active-menu="friends"/)
  assert.match(html, /已添加为好友/)
  assert.match(html, /data-image-src="https:\/\/example.com\/bob.png"/)
  assert.ok(requests.some(({ url }) => url.endsWith('/api/v1/friends/user/bob')))

  respond = (url) =>
    url.endsWith('/friends/my')
      ? json({ statusCode: 200, content: { friends: [{ ...bob, userId: 3, username: 'carol' }] } })
      : infoResponse({ ...bob, userId: 3, username: 'carol', displayName: 'Carol' })
  await router.push('/friends/details/carol')
  html = await renderToString(app)
  const carolLink = html
    .match(/<a[^>]*>/g)
    .find((tag) => tag.includes('href="/friends/details/carol"'))
  assert.match(carolLink, /aria-current="page"/)
  assert.match(html, /Carol/)
  assert.ok(requests.some(({ url }) => url.endsWith('/api/v1/friends/user/carol')))
})

test('直接获取好友资料携带 Session 并编码用户名，空白名称和头像使用回退', async () => {
  const session = await context()
  const username = '小明+qa'
  respond = () =>
    infoResponse({ ...bob, username, displayName: '  ', avatarUrl: '  ', isFriend: false })
  const html = await renderDetails(session, username)
  assert.ok(requests[0].url.endsWith(`/friends/user/${encodeURIComponent(username)}`))
  assert.equal(requests[0].options.credentials, 'include')
  assert.match(html, /小明\+qa/)
  assert.match(html, /尚未添加为好友/)
  assert.match(html.replace(/<!--.*?-->/g, ''), /n-avatar__text[^>]*>小<\/span>/)
  assert.ok(!html.includes('data-image-src='))
})

test('404 和错误用户响应显示错误，刷新失败保留资料，重试后更新', async () => {
  const session = await context()
  respond = () => json({}, 404)
  let html = await renderDetails(session)
  assert.match(html, /用户不存在或已被删除/)
  assert.match(html, /role="alert"/)
  respond = () => infoResponse({ ...bob, username: 'carol' })
  html = await renderDetails(session)
  assert.match(html, /好友资料加载失败，请重试/)
  assert.ok(!html.includes('已添加为好友'))

  respond = () => infoResponse()
  html = await renderDetails(session, 'bob', async (view) => {
    await view.loadDetails()
    respond = () => json({}, 503)
    await view.loadDetails()
    assert.equal(view.friend.value.displayName, 'Bob')
    assert.ok(view.error.value)
    respond = () => infoResponse({ ...bob, displayName: 'Bob 新名称' })
    await view.loadDetails()
  })
  assert.match(html, /Bob 新名称/)
  assert.ok(!html.includes('好友资料加载失败'))
})

test('请求中退出登录会清除资料，迟到响应不能恢复好友信息', async () => {
  const session = await context()
  const html = await renderDetails(session, 'bob', async (view) => {
    await view.loadDetails()
    let finish, notifyStarted
    const started = new Promise((resolve) => (notifyStarted = resolve))
    respond = () =>
      new Promise((resolve) => {
        finish = resolve
        notifyStarted()
      })
    const loading = view.loadDetails()
    await started
    const duplicate = view.loadDetails()
    assert.equal(requests.length, 2)
    session.user.clearSession()
    assert.equal(view.friend.value, null)
    finish(infoResponse())
    await Promise.all([loading, duplicate])
    assert.equal(view.friend.value, null)
    assert.equal(view.isLoading.value, false)
  })
  assert.ok(!html.includes('已添加为好友'))
})

test('重新进入详情先展示列表或详情缓存，后台刷新完成后更新列表和详情', async () => {
  const session = await context()
  await session.store.loadFriends()
  const count = requests.length
  let html = await renderDetails(session, 'bob', () => {})
  assert.match(html, /Bob/)
  assert.match(html, /已添加为好友/)
  assert.equal(requests.length, count)

  let finish, notifyStarted
  const started = new Promise((resolve) => (notifyStarted = resolve))
  respond = () =>
    new Promise((resolve) => {
      finish = resolve
      notifyStarted()
    })
  const refreshing = session.store.loadDetails('bob')
  await started
  html = await renderDetails(session, 'bob', () => {})
  assert.match(html, /Bob/)
  assert.match(html, /aria-busy="true"/)
  assert.ok(!html.includes('正在加载好友资料…'))
  finish(infoResponse({ ...bob, displayName: 'Bob 最新资料' }))
  await refreshing
  assert.equal(session.store.friends[0].displayName, 'Bob 最新资料')
  html = await renderDetails(session, 'bob', () => {})
  assert.match(html, /Bob 最新资料/)

  respond = () => json({}, 503)
  await session.store.loadDetails('bob')
  html = await renderDetails(session, 'bob', () => {})
  assert.match(html, /Bob 最新资料/)
  assert.match(html, /好友资料加载失败，请重试/)
})

test('好友列表在刷新中保留缓存，同一刷新请求合并，失败可重试', async () => {
  const { store } = await context()
  await store.loadFriends()
  let finish, notifyStarted
  const started = new Promise((resolve) => (notifyStarted = resolve))
  respond = () =>
    new Promise((resolve) => {
      finish = resolve
      notifyStarted()
    })
  const refreshing = store.loadFriends()
  await started
  const duplicate = store.loadFriends()
  assert.equal(requests.length, 2)
  assert.equal(store.friends[0].displayName, 'Bob')
  assert.equal(store.hasLoaded, true)
  assert.equal(store.isLoading, true)
  finish(json({}, 503))
  await Promise.all([refreshing, duplicate])
  assert.equal(store.friends.length, 2)
  assert.ok(store.error)
  respond = () => json({ statusCode: 200, content: { friends: [] } })
  await store.loadFriends()
  assert.deepEqual(store.friends, [])
  assert.equal(store.error, null)
  assert.equal(store.hasLoaded, true)
})

test('不同好友分别缓存和加载，较早发出的列表请求不能覆盖更新后的详情', async () => {
  const { store } = await context()
  const finishes = new Map()
  respond = (url) => new Promise((resolve) => finishes.set(url, resolve))
  const listing = store.loadFriends()
  const details = store.loadDetails('bob')
  const carolDetails = store.loadDetails('carol')
  // Fetch dispatches asynchronously; allow all three mocked requests to start.
  await new Promise((resolve) => setImmediate(resolve))
  const complete = (suffix, response) => {
    const [, finish] = [...finishes].find(([url]) => url.endsWith(suffix))
    finish(response)
  }
  complete('/friends/user/bob', infoResponse({ ...bob, displayName: 'Bob 最新资料' }))
  await details
  complete(
    '/friends/user/carol',
    infoResponse({ ...bob, userId: 3, username: 'carol', displayName: 'Carol' }),
  )
  await carolDetails
  complete('/friends/my', json({ statusCode: 200, content: { friends: [bob] } }))
  await listing
  assert.equal(store.friends[0].displayName, 'Bob 最新资料')
  assert.equal(store.profiles.get('bob').displayName, 'Bob 最新资料')
  assert.equal(store.profiles.get('carol').displayName, 'Carol')
  assert.equal(store.loadingDetails.size, 0)
})

test('清除会话后缓存和请求状态立即清空，旧会话的列表和详情不能影响新会话', async () => {
  const { store, user } = await context()
  await store.loadFriends()
  await store.loadDetails('bob')
  const finishes = new Map()
  respond = (url) => new Promise((resolve) => finishes.set(url, resolve))
  const listing = store.loadFriends()
  const details = store.loadDetails('bob')
  await new Promise((resolve) => setImmediate(resolve))
  user.clearSession()
  assert.equal(store.friends.length, 0)
  assert.equal(store.profiles.size, 0)
  assert.equal(store.loadingDetails.size, 0)
  assert.equal(store.detailErrors.size, 0)
  assert.equal(store.hasLoaded, false)
  assert.equal(store.isLoading, false)

  await user.login({ username: 'alice', password: 'secret' })
  respond = (url) =>
    url.endsWith('/friends/my')
      ? json({ statusCode: 200, content: { friends: [{ ...bob, displayName: '新会话资料' }] } })
      : infoResponse({ ...bob, displayName: '新会话资料' })
  await store.loadFriends()
  await store.loadDetails('bob')
  for (const [url, finish] of finishes) {
    finish(
      url.endsWith('/friends/my')
        ? json({ statusCode: 200, content: { friends: [bob] } })
        : infoResponse(),
    )
  }
  await Promise.all([listing, details])
  assert.equal(store.profiles.get('bob').displayName, '新会话资料')
  assert.equal(store.friends[0].displayName, '新会话资料')

  user.user = { id: 9, username: 'other' }
  assert.equal(store.profiles.size, 0)
  assert.equal(store.friends.length, 0)
})

test('用户返回 404 后移除失效缓存，普通网络失败保留缓存', async () => {
  const { store } = await context()
  await store.loadFriends()
  respond = () => json({}, 503)
  await store.loadDetails('bob')
  assert.ok(store.profiles.has('bob'))
  assert.equal(store.friends.length, 2)
  respond = () => json({}, 404)
  await store.loadDetails('bob')
  assert.equal(store.profiles.has('bob'), false)
  assert.equal(
    store.friends.some((friend) => friend.username === 'bob'),
    false,
  )
  assert.equal(store.detailErrors.get('bob'), '用户不存在或已被删除')
})

test('较早发出的详情请求返回 404，不能删除较新列表已刷新的资料', async () => {
  const { store } = await context()
  let finishDetails
  respond = (url) =>
    url.endsWith('/friends/my')
      ? json({ statusCode: 200, content: { friends: [{ ...bob, displayName: '最新列表资料' }] } })
      : new Promise((resolve) => (finishDetails = resolve))
  const details = store.loadDetails('bob')
  await new Promise((resolve) => setImmediate(resolve))
  await store.loadFriends()
  finishDetails(json({}, 404))
  await details
  assert.equal(store.profiles.get('bob').displayName, '最新列表资料')
  assert.equal(store.friends[0].displayName, '最新列表资料')
  assert.equal(store.detailErrors.has('bob'), false)
})

test('发消息获取好友会话后跳转，用户名编码并携带 Session', async () => {
  const session = await context()
  const username = '小明+qa'
  respond = (url) =>
    url.endsWith('/conversation')
      ? json({ statusCode: 200, content: { id: 42 } })
      : infoResponse({ ...bob, username })
  const html = await renderDetails(session, username, async (view) => {
    await view.loadDetails()
    await view.openChat()
  })
  assert.match(html, /发消息/)
  assert.match(html, /删除好友/)
  assert.equal(session.router.currentRoute.value.fullPath, '/chat/42')
  const request = requests.find(({ url }) => url.endsWith('/conversation'))
  assert.ok(request.url.endsWith(`/friends/user/${encodeURIComponent(username)}/conversation`))
  assert.equal(request.options.credentials, 'include')
  assert.equal(request.options.method, 'GET')
})

test('非好友显示添加入口，不能直接触发已有好友操作', async () => {
  const session = await context()
  respond = () => infoResponse({ ...bob, isFriend: false })
  const html = await renderDetails(session, 'bob', async (view) => {
    await view.loadDetails()
    await view.openChat()
    view.requestDelete()
    await view.confirmDelete()
    assert.equal(view.showDeleteConfirm.value, false)
  })
  assert.ok(!html.includes('发消息'))
  assert.match(html, /添加好友/)
  assert.equal(requests.length, 1)
})

const friendRequest = (overrides = {}) => ({
  id: 20,
  senderUsername: 'bob',
  senderDisplayName: 'Bob',
  targetUsername: 'alice',
  targetDisplayName: 'Alice',
  note: '你好，交个朋友吧',
  state: 'Open',
  createdAt: '2026-10-06T12:00:00',
  ...overrides,
})
const requestPageResponse = (content = [], page = 0, hasMore = false) =>
  json({ statusCode: 200, content: { content, page, size: 20, hasMore } })
const settle = () => new Promise((resolve) => setImmediate(resolve))

async function renderNew(session, prepare = (view) => view.loadRequests()) {
  await session.router.push({ name: 'friends.new' })
  const app = createSSRApp({
    render: () =>
      h(NMessageProvider, null, {
        default: () => h(prepared(NewFriends, prepare)),
      }),
  })
  app.use(session.pinia)
  app.use(session.router)
  setupStyles(app)
  return renderToString(app)
}

test('新好友路由嵌入原布局，入口置于好友链接之前且正确高亮', async () => {
  const session = await context()
  const friendsRoute = routes
    .find((route) => route.name === 'home')
    .children.find((route) => route.name === 'friends')
  assert.equal(friendsRoute.children.find((route) => route.name === 'friends.new').path, 'new')
  await session.router.push('/friends/new')
  const app = createSSRApp(prepared(FriendsList, (view) => view.loadFriends()))
  app.use(session.pinia)
  app.use(session.router)
  const html = await renderToString(app)
  const link = html.match(/<a[^>]*>/g).find((tag) => tag.includes('href="/friends/new"'))
  assert.match(link, /aria-current="page"/)
  assert.match(link, /bg-\(--color-primary\)\/16/)
  assert.ok(html.indexOf('href="/friends/new"') < html.indexOf('href="/friends/details/bob"'))
})

test('申请页默认收到，展示公开资料、留言和接受／忽略操作；处理后只显示真实状态', async () => {
  const session = await context()
  respond = () =>
    requestPageResponse([
      friendRequest(),
      friendRequest({ id: 19, state: 'Accepted' }),
      friendRequest({ id: 18, state: 'Rejected' }),
      friendRequest({ id: 17, state: 'Ignored' }),
    ])
  const html = await renderNew(session, async (view) => {
    assert.equal(view.activeTab.value, 'received')
    assert.deepEqual(view.rejectOptions, [{ label: '拒绝', key: 'reject' }])
    await view.loadRequests()
  })
  assert.match(html, /收到的请求/)
  assert.match(html, /发出的请求/)
  assert.match(html, /你好，交个朋友吧/)
  assert.match(html, /href="\/friends\/details\/bob"/)
  assert.match(html, /接受/)
  assert.match(html, /忽略/)
  assert.match(html, /已接受/)
  assert.match(html, /已拒绝/)
  assert.match(html, /已忽略/)
  assert.match(html, /aria-haspopup="menu"/)
  assert.equal(requests[0].options.credentials, 'include')
  assert.ok(requests[0].url.includes('sent=false'))
  assert.ok(requests[0].url.includes('size=20'))
})

test('发出列表只将 Open 和 Ignored 展示为待接收，状态原值保留', async () => {
  const session = await context()
  const entries = ['Open', 'Ignored', 'Accepted', 'Rejected'].map((state, index) =>
    friendRequest({
      id: 20 - index,
      senderUsername: 'alice',
      targetUsername: 'bob',
      state,
    }),
  )
  respond = () => requestPageResponse(entries)
  const html = await renderNew(session, async (view) => {
    view.activeTab.value = 'sent'
    await settle()
    await session.store.loadRequests('sent')
    assert.deepEqual(entries.map(view.requestStatus), ['待接收', '待接收', '已接受', '已拒绝'])
  })
  assert.match(html, /待接收/)
  assert.ok(!html.includes('已忽略'))
  assert.ok(!html.includes('aria-haspopup="menu"'))
  assert.equal(session.store.requestPages.sent.requests[1].state, 'Ignored')
  assert.equal(session.store.requestPages.received.hasLoaded, false)
})

test('申请分页独立保存、按 ID 去重排序，加载失败保留列表且重试同一页', async () => {
  const { store } = await context()
  const received = friendRequest()
  respond = (url) => {
    const params = new URL(url, 'http://localhost').searchParams
    return params.get('sent') === 'true'
      ? requestPageResponse([
          friendRequest({ id: 30, senderUsername: 'alice', targetUsername: 'carol' }),
        ])
      : requestPageResponse([received], 0, true)
  }
  await Promise.all([store.loadRequests('received'), store.loadRequests('sent')])
  assert.equal(store.requestPages.received.nextPage, 1)
  assert.equal(store.requestPages.sent.nextPage, 1)
  respond = () => json({}, 503)
  await store.loadRequests('received')
  assert.equal(store.requestPages.received.requests.length, 1)
  assert.equal(store.requestPages.received.nextPage, 1)
  assert.equal(store.requestPages.received.retryRefresh, false)
  respond = () => requestPageResponse([received, friendRequest({ id: 10 })], 1)
  await store.loadRequests('received')
  assert.deepEqual(
    store.requestPages.received.requests.map((entry) => entry.id),
    [20, 10],
  )
  assert.equal(store.requestPages.received.hasMore, false)
  assert.equal(store.requestPages.sent.requests[0].id, 30)
  const count = requests.length
  await store.loadRequests('received')
  assert.equal(requests.length, count)
  respond = () => json({}, 503)
  await store.loadRequests('received', true)
  assert.equal(store.requestPages.received.retryRefresh, true)
  assert.equal(store.requestPages.received.requests.length, 2)
  respond = () => requestPageResponse([])
  await store.loadRequests('received', store.requestPages.received.retryRefresh)
  assert.deepEqual(store.requestPages.received.requests, [])
})

test('拒绝异常列表，空列表显示空态，刷新失败保留已有申请', async () => {
  const session = await context()
  for (const content of [
    [friendRequest({ targetUsername: 'other' })],
    [friendRequest({ id: '20' })],
    [friendRequest({ state: 'invalid' })],
  ]) {
    respond = () => requestPageResponse(content)
    await session.store.loadRequests('received', true)
    assert.ok(session.store.requestPages.received.error)
    assert.equal(session.store.requestPages.received.requests.length, 0)
  }
  respond = () => requestPageResponse([])
  assert.match(await renderNew(session), /暂无收到的好友申请/)
  respond = () => requestPageResponse([friendRequest()])
  await session.store.loadRequests('received', true)
  respond = () => json({}, 503)
  const html = await renderNew(session)
  assert.match(html, /好友申请加载失败/)
  assert.match(html, /你好，交个朋友吧/)
})

test('搜索校验不发请求，精确查找编码用户名并跳转非好友详情', async () => {
  const session = await context()
  const username = '小明+qa'
  respond = () => infoResponse({ ...bob, username, isFriend: false })
  await renderNew(session, async (view) => {
    for (const value of ['', '  ', 'x'.repeat(33)]) {
      view.search.value = value
      await view.findUser()
      assert.ok(view.searchError.value)
    }
    assert.equal(requests.length, 0)
    view.search.value = `  ${username}  `
    await view.findUser()
    assert.equal(view.searchError.value, null)
  })
  assert.equal(session.router.currentRoute.value.params.username, username)
  assert.ok(requests[0].url.endsWith(encodeURIComponent(username)))
  assert.equal(requests[0].options.credentials, 'include')
  assert.equal(session.store.profiles.get(username).isFriend, false)
})

test('搜索失败不使用旧缓存跳转，迟到响应和重复搜索不能改变已离开的页面', async () => {
  const session = await context()
  session.store.profiles.set('bob', bob)
  await renderNew(session, async (view) => {
    view.search.value = 'bob'
    for (const status of [404, 503]) {
      respond = () => json({}, status)
      await view.findUser()
      assert.equal(session.router.currentRoute.value.name, 'friends.new')
      assert.match(view.searchError.value, status === 404 ? /不存在/ : /失败/)
    }
    let finish
    respond = () => new Promise((resolve) => (finish = resolve))
    const searching = view.findUser()
    await settle()
    await view.findUser()
    assert.equal(requests.length, 3)
    await session.router.push('/friends')
    finish(infoResponse())
    await searching
    assert.equal(session.router.currentRoute.value.name, 'friends')
  })
})

test('非好友申请需打开 dialog，自身无入口，取消不发送，可选留言省略且发送后关闭', async () => {
  const session = await context()
  respond = () => infoResponse({ ...bob, username: 'alice', userId: 1, isFriend: false })
  const selfHtml = await renderDetails(session, 'alice', async (view) => {
    await view.loadDetails()
    view.requestAdd()
    assert.equal(view.showAddDialog.value, false)
  })
  assert.ok(!selfHtml.includes('添加好友'))
  respond = () => infoResponse({ ...bob, isFriend: false })
  await renderDetails(session, 'bob', async (view) => {
    await view.loadDetails()
    await view.confirmAdd()
    view.requestAdd()
    view.cancelAdd()
    assert.equal(view.showAddDialog.value, false)
    assert.equal(requests.filter(({ options }) => options.method === 'POST').length, 0)
    const sent = friendRequest({ senderUsername: 'alice', targetUsername: 'bob' })
    respond = (_url, options) =>
      options.method === 'POST'
        ? json({ statusCode: 200, content: sent })
        : requestPageResponse([sent])
    view.requestAdd()
    view.requestNote.value = '  '
    await view.confirmAdd()
    assert.equal(view.showAddDialog.value, false)
    assert.equal(view.requestNote.value, '')
    assert.equal(view.actionError.value, null)
  })
  await settle()
  const submission = requests.find(({ options }) => options.method === 'POST')
  assert.deepEqual(JSON.parse(submission.options.body), { targetUsername: 'bob' })
  assert.equal(submission.options.credentials, 'include')
  assert.equal(session.store.requestPages.sent.requests[0].state, 'Open')
})

test('申请失败保留留言并允许重试，255 字符可提交，重复提交和关闭被阻止', async () => {
  const session = await context()
  respond = () => infoResponse({ ...bob, isFriend: false })
  await renderDetails(session, 'bob', async (view) => {
    await view.loadDetails()
    view.requestAdd()
    view.requestNote.value = 'x'.repeat(256)
    await view.confirmAdd()
    assert.match(view.actionError.value, /255/)
    assert.equal(requests.length, 1)
    view.requestNote.value = 'x'.repeat(255)
    respond = () => json({}, 503)
    await view.confirmAdd()
    assert.equal(view.showAddDialog.value, true)
    assert.equal(view.requestNote.value.length, 255)
    assert.match(view.actionError.value, /失败/)
    let finish
    const sent = friendRequest({ senderUsername: 'alice', targetUsername: 'bob' })
    respond = (_url, options) =>
      options.method === 'POST'
        ? new Promise((resolve) => (finish = resolve))
        : requestPageResponse([sent])
    const sending = view.confirmAdd()
    await settle()
    await view.confirmAdd()
    view.cancelAdd()
    assert.equal(view.showAddDialog.value, true)
    assert.equal(view.isSending.value, true)
    assert.equal(requests.filter(({ options }) => options.method === 'POST').length, 2)
    finish(json({ statusCode: 200, content: sent }))
    await sending
    assert.equal(view.showAddDialog.value, false)
  })
  const submission = requests.filter(({ options }) => options.method === 'POST').at(-1)
  assert.equal(JSON.parse(submission.options.body).note.length, 255)
})

test('申请 409 和 404 提示原因并刷新关联资料及双向申请列表', async () => {
  for (const status of [409, 404]) {
    const session = await context()
    respond = () => infoResponse({ ...bob, isFriend: false })
    await renderDetails(session, 'bob', async (view) => {
      await view.loadDetails()
      view.requestAdd()
      view.requestNote.value = '保留留言'
      respond = (url, options) =>
        options.method === 'POST'
          ? json({}, status)
          : url.includes('/requests?')
            ? requestPageResponse([])
            : infoResponse({ ...bob, isFriend: false })
      await view.confirmAdd()
      await settle()
      assert.match(view.actionError.value, status === 409 ? /待处理申请/ : /不存在/)
      assert.equal(view.requestNote.value, '保留留言')
      assert.equal(view.showAddDialog.value, true)
    })
    assert.ok(requests.some(({ url }) => url.includes('sent=true')))
    assert.ok(requests.some(({ url }) => url.includes('sent=false')))
  }
})

test('接受、忽略、拒绝使用各自接口，仅接受刷新好友和会话，保持当前页面', async () => {
  for (const [action, state] of [
    ['accept', 'Accepted'],
    ['ignore', 'Ignored'],
    ['reject', 'Rejected'],
  ]) {
    requests = []
    const session = await context()
    respond = () => requestPageResponse([friendRequest()])
    await session.store.loadRequests('received')
    session.store.profiles.set('bob', { ...bob, isFriend: false })
    session.conversations.drafts['0'] = '草稿'
    respond = (url, options) =>
      options.method === 'POST'
        ? json({ statusCode: 200 })
        : url.endsWith('/friends/my')
          ? json({ statusCode: 200, content: { friends: [bob] } })
          : url.includes('/conversations')
            ? json({ statusCode: 200, content: { conversations: [], page: 0, hasMore: false } })
            : infoResponse()
    await renderNew(session, (view) => view.handleRequest(20, action))
    await settle()
    assert.equal(session.store.requestPages.received.requests[0].state, state)
    assert.equal(session.store.processingRequests.size, 0)
    assert.equal(session.router.currentRoute.value.name, 'friends.new')
    const operation = requests.filter(({ options }) => options.method === 'POST').at(-1)
    assert.ok(operation.url.endsWith(`/requests/20/${action}`))
    assert.equal(operation.options.credentials, 'include')
    assert.equal(
      requests.some(({ url }) => url.includes('/conversations')),
      action === 'accept',
    )
    if (action === 'accept') {
      assert.equal(session.store.profiles.get('bob').isFriend, true)
      assert.equal(session.store.friends[0].username, 'bob')
    }
    assert.equal(session.conversations.drafts['0'], '草稿')
  }
})

test('同一申请操作互斥，失败保留 Open，成功后禁止再次处理；不同申请可以同时操作', async () => {
  const { store } = await context()
  respond = () =>
    requestPageResponse([friendRequest(), friendRequest({ id: 21, senderUsername: 'carol' })])
  await store.loadRequests('received')
  respond = () => json({}, 503)
  assert.equal(await store.processRequest(20, 'ignore'), false)
  assert.equal(store.requestPages.received.requests.find((entry) => entry.id === 20).state, 'Open')
  assert.ok(store.requestActionErrors.get(20))
  const finishes = new Map()
  respond = (url) => new Promise((resolve) => finishes.set(url, resolve))
  const ignoring = store.processRequest(20, 'ignore')
  const rejecting = store.processRequest(21, 'reject')
  await settle()
  const count = requests.length
  assert.equal(await store.processRequest(20, 'accept'), false)
  assert.equal(requests.length, count)
  assert.equal(store.processingRequests.size, 2)
  for (const finish of finishes.values()) finish(json({ statusCode: 200 }))
  await Promise.all([ignoring, rejecting])
  assert.equal(store.requestActionErrors.size, 0)
  assert.equal(await store.processRequest(20, 'reject'), false)
})

test('处理 409／404 刷新状态，接受后刷新失败仍保持成功状态并可重试', async () => {
  for (const status of [409, 404]) {
    const { store } = await context()
    respond = () => requestPageResponse([friendRequest()])
    await store.loadRequests('received')
    respond = (url, options) =>
      options.method === 'POST'
        ? json({}, status)
        : url.includes('/requests?')
          ? requestPageResponse(status === 404 ? [] : [friendRequest({ state: 'Accepted' })])
          : url.endsWith('/my')
            ? json({ statusCode: 200, content: { friends: [bob] } })
            : infoResponse()
    assert.equal(await store.processRequest(20, 'accept'), false)
    await settle()
    assert.match(store.requestActionErrors.get(20), status === 409 ? /已处理/ : /不存在/)
    assert.equal(store.requestPages.received.requests.length, status === 404 ? 0 : 1)
  }
  const session = await context()
  respond = () => requestPageResponse([friendRequest()])
  await session.store.loadRequests('received')
  respond = (_url, options) =>
    options.method === 'POST' ? json({ statusCode: 200 }) : json({}, 503)
  assert.equal(await session.store.processRequest(20, 'accept'), true)
  await settle()
  assert.equal(session.store.requestPages.received.requests[0].state, 'Accepted')
  assert.ok(session.store.error)
  const html = await renderNew(session, () => {})
  assert.match(html, /申请已接受，好友资料刷新失败/)
  respond = (url) =>
    url.endsWith('/my') ? json({ statusCode: 200, content: { friends: [bob] } }) : infoResponse()
  await Promise.all([session.store.loadFriends(), session.store.loadDetails('bob')])
  assert.equal(session.store.error, null)
  assert.equal(session.store.detailErrors.size, 0)
})

test('接受时旧好友资料、列表及申请列表响应不能覆盖已确认的新状态', async () => {
  const { store } = await context()
  respond = () => requestPageResponse([friendRequest()])
  await store.loadRequests('received')
  const finishes = new Map()
  respond = (url) => new Promise((resolve) => finishes.set(url, resolve))
  const oldListing = store.loadFriends()
  const oldDetails = store.loadDetails('bob')
  const oldRequests = store.loadRequests('received', true)
  await settle()
  respond = (url, options) =>
    options.method === 'POST'
      ? json({ statusCode: 200 })
      : url.endsWith('/friends/my')
        ? json({ statusCode: 200, content: { friends: [bob] } })
        : url.includes('/conversations')
          ? json({ statusCode: 200, content: { conversations: [], page: 0, hasMore: false } })
          : infoResponse()
  assert.equal(await store.processRequest(20, 'accept'), true)
  await settle()
  for (const [url, finish] of finishes) {
    finish(
      url.includes('/requests?')
        ? requestPageResponse([friendRequest()])
        : url.endsWith('/my')
          ? json({ statusCode: 200, content: { friends: [] } })
          : infoResponse({ ...bob, isFriend: false }),
    )
  }
  await Promise.all([oldListing, oldDetails, oldRequests])
  assert.equal(store.profiles.get('bob').isFriend, true)
  assert.equal(store.friends[0].username, 'bob')
  assert.equal(store.requestPages.received.requests[0].state, 'Accepted')
})

test('刷新取代旧分页响应，登出清空申请及操作状态，迟到操作不能污染新账号', async () => {
  const { store, user } = await context()
  let finishOld
  respond = () => new Promise((resolve) => (finishOld = resolve))
  const old = store.loadRequests('received')
  await settle()
  const duplicate = store.loadRequests('received')
  assert.equal(requests.length, 1)
  respond = () => requestPageResponse([friendRequest({ id: 30 })])
  await store.loadRequests('received', true)
  finishOld(requestPageResponse([friendRequest()]))
  await Promise.all([old, duplicate])
  assert.equal(store.requestPages.received.requests[0].id, 30)
  let finishAction
  respond = () => new Promise((resolve) => (finishAction = resolve))
  const action = store.processRequest(30, 'accept')
  await settle()
  user.clearSession()
  assert.equal(store.requestPages.received.requests.length, 0)
  assert.equal(store.processingRequests.size, 0)
  assert.equal(store.requestActionErrors.size, 0)
  await user.login({ username: 'alice', password: 'secret' })
  store.requestPages.received.requests = [friendRequest({ id: 99 })]
  finishAction(json({ statusCode: 200 }))
  assert.equal(await action, false)
  assert.equal(store.requestPages.received.requests[0].id, 99)
  assert.equal(store.friends.length, 0)
  assert.equal(requests.filter(({ url }) => url.includes('/conversations')).length, 0)
})

test('搜索、申请列表、发送中的退出登录会清空数据，旧响应不恢复缓存或 dialog', async () => {
  for (const operation of ['search', 'list', 'send']) {
    const session = await context()
    let finish
    if (operation === 'send') {
      respond = () => infoResponse({ ...bob, isFriend: false })
      await renderDetails(session, 'bob', async (view) => {
        await view.loadDetails()
        view.requestAdd()
        view.requestNote.value = '留言'
        respond = () => new Promise((resolve) => (finish = resolve))
        const sending = view.confirmAdd()
        await settle()
        session.user.clearSession()
        assert.equal(view.showAddDialog.value, false)
        assert.equal(view.requestNote.value, '')
        finish(
          json({
            statusCode: 200,
            content: friendRequest({ senderUsername: 'alice', targetUsername: 'bob' }),
          }),
        )
        await sending
        assert.equal(session.store.requestPages.sent.requests.length, 0)
      })
    } else {
      respond = () => new Promise((resolve) => (finish = resolve))
      await renderNew(session, async (view) => {
        view.search.value = 'bob'
        const pending = operation === 'search' ? view.findUser() : view.loadRequests()
        await settle()
        session.user.clearSession()
        finish(operation === 'search' ? infoResponse() : requestPageResponse([friendRequest()]))
        await pending
        assert.equal(session.router.currentRoute.value.name, 'friends.new')
        assert.equal(session.store.profiles.size, 0)
        assert.equal(session.store.requestPages.received.requests.length, 0)
      })
    }
  }
})

test('打开会话失败显示错误，拒绝无效会话 ID，重试成功', async () => {
  const session = await context()
  const html = await renderDetails(session, 'bob', async (view) => {
    await view.loadDetails()
    respond = () => json({}, 404)
    await view.openChat()
    assert.equal(view.actionError.value, '好友会话不存在或已无权访问，请刷新资料')
    for (const id of [0, -1, '42', 1.5, null, Number.MAX_SAFE_INTEGER + 1]) {
      respond = () => json({ statusCode: 200, content: { id } })
      await view.openChat()
      assert.equal(view.actionError.value, '打开会话失败，请重试')
      assert.equal(view.isOpeningChat.value, false)
      assert.equal(session.router.currentRoute.value.name, 'friends.details')
    }
    respond = () => json({ statusCode: 200, content: { id: 42 } })
    await view.openChat()
    assert.equal(view.actionError.value, null)
  })
  assert.ok(!html.includes('打开会话失败'))
  assert.equal(session.router.currentRoute.value.fullPath, '/chat/42')
})

test('删除需确认，取消不请求；成功更新好友资料和会话列表并保留其他草稿', async () => {
  const session = await context()
  await session.store.loadFriends()
  session.conversations.conversations = [{ id: 42, title: 'Bob', type: 'Friend' }]
  session.conversations.drafts['0'] = '主会话草稿'
  const html = await renderDetails(session, 'bob', async (view) => {
    const count = requests.length
    await view.confirmDelete()
    view.requestDelete()
    assert.equal(view.showDeleteConfirm.value, true)
    view.cancelDelete()
    assert.equal(view.showDeleteConfirm.value, false)
    assert.equal(requests.length, count)
    respond = (url, options) =>
      options.method === 'DELETE'
        ? json({ statusCode: 200, content: null })
        : json({ statusCode: 200, content: { conversations: [], page: 0, hasMore: false } })
    view.requestDelete()
    await view.confirmDelete()
    assert.equal(view.showDeleteConfirm.value, false)
    assert.equal(view.isDeleting.value, false)
  })
  assert.equal(session.router.currentRoute.value.fullPath, '/friends')
  assert.equal(
    session.store.friends.some((entry) => entry.username === 'bob'),
    false,
  )
  assert.equal(session.store.profiles.get('bob').isFriend, false)
  assert.ok(!html.includes('发消息'))
  assert.deepEqual(session.conversations.conversations, [])
  assert.equal(session.conversations.drafts['0'], '主会话草稿')
  const deletion = requests.find(({ options }) => options.method === 'DELETE')
  assert.ok(deletion.url.endsWith('/friends/user/bob'))
  assert.equal(deletion.options.credentials, 'include')
  assert.ok(requests.some(({ url }) => url.includes('/v1/conversations?page=0')))
})

test('删除失败保留好友和确认窗口，可重试，重复点击只发送一次删除请求', async () => {
  const session = await context()
  await renderDetails(session, 'bob', async (view) => {
    await view.loadDetails()
    view.requestDelete()
    respond = () => json({ statusCode: 500 })
    await view.confirmDelete()
    assert.equal(view.actionError.value, '删除好友失败，请重试')
    assert.equal(view.showDeleteConfirm.value, true)
    assert.equal(view.friend.value.isFriend, true)
    let finish
    respond = (_url, options) =>
      options.method === 'DELETE'
        ? new Promise((resolve) => {
            finish = resolve
          })
        : json({ statusCode: 200, content: { conversations: [], page: 0, hasMore: false } })
    const deleting = view.confirmDelete()
    await new Promise((resolve) => setImmediate(resolve))
    const count = requests.length
    await view.confirmDelete()
    await view.openChat()
    view.cancelDelete()
    assert.equal(view.isDeleting.value, true)
    assert.equal(view.showDeleteConfirm.value, true)
    assert.equal(requests.length, count)
    finish(json({ statusCode: 200 }))
    await deleting
    assert.equal(view.actionError.value, null)
  })
  assert.equal(session.store.profiles.get('bob').isFriend, false)
})

test('删除成功后迟到的列表和详情响应不能恢复好友关系', async () => {
  const session = await context()
  await session.store.loadFriends()
  const finishes = new Map()
  respond = (url, options) => {
    if (options.method === 'DELETE') return json({ statusCode: 200 })
    if (url.includes('/conversations')) {
      return json({ statusCode: 200, content: { conversations: [], page: 0, hasMore: false } })
    }
    return new Promise((resolve) => finishes.set(url, resolve))
  }
  const listing = session.store.loadFriends()
  const details = session.store.loadDetails('bob')
  await new Promise((resolve) => setImmediate(resolve))
  await session.store.removeFriend('bob')
  for (const [url, finish] of finishes) {
    finish(
      url.endsWith('/my') ? json({ statusCode: 200, content: { friends: [bob] } }) : infoResponse(),
    )
  }
  await Promise.all([listing, details])
  assert.equal(session.store.profiles.get('bob').isFriend, false)
  assert.equal(session.store.friends.length, 0)
})

test('打开会话期间切换好友，迟到响应不能跳转且重复点击不重复请求', async () => {
  const session = await context()
  await renderDetails(session, 'bob', async (view) => {
    await view.loadDetails()
    let finish
    respond = () =>
      new Promise((resolve) => {
        finish = resolve
      })
    const opening = view.openChat()
    await new Promise((resolve) => setImmediate(resolve))
    await view.openChat()
    assert.equal(requests.filter(({ url }) => url.endsWith('/conversation')).length, 1)
    await session.router.push('/friends/details/carol')
    finish(json({ statusCode: 200, content: { id: 42 } }))
    await opening
    assert.equal(session.router.currentRoute.value.fullPath, '/friends/details/carol')
  })
})

test('好友操作期间退出登录，迟到响应不能跳转或更新新会话缓存', async () => {
  for (const operation of ['openChat', 'confirmDelete']) {
    const session = await context()
    await renderDetails(session, 'bob', async (view) => {
      await view.loadDetails()
      if (operation === 'confirmDelete') view.requestDelete()
      let finish
      respond = () =>
        new Promise((resolve) => {
          finish = resolve
        })
      const pending = view[operation]()
      await new Promise((resolve) => setImmediate(resolve))
      const finishAction = finish
      respond = () => infoResponse()
      session.user.clearSession()
      await session.user.login({ username: 'alice', password: 'secret' })
      session.store.profiles.set('bob', bob)
      finishAction(json({ statusCode: 200, content: { id: 42 } }))
      await pending
      assert.equal(session.router.currentRoute.value.name, 'friends.details')
      assert.equal(session.store.profiles.get('bob').isFriend, true)
      assert.equal(session.conversations.isLoading, false)
    })
  }
})
