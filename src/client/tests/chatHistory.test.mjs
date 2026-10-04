import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { createServer } from 'vite'
import { fileURLToPath } from 'node:url'

let server
let ChatHistory
let compareMessages

before(async () => {
  server = await createServer({
    configFile: false,
    root: fileURLToPath(new URL('../', import.meta.url)),
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: 'custom',
  })
  ;({ ChatHistory, compareMessages } = await server.ssrLoadModule('/src/api/chatHistory.ts'))
})
after(async () => {
  await server?.close()
})

function message(id, micros = id) {
  return {
    id: String(id),
    conversationId: 0,
    body: `message ${id}`,
    senderId: 1,
    senderUsername: 'alice',
    senderDisplayName: 'Alice',
    senderAvatarUrl: '',
    sendTime: `2026-10-04T12:00:00.${String(micros).padStart(6, '0')}`,
  }
}

function page(messages, snapshotCursor, nextCursor = null) {
  return { messages, snapshotCursor, nextCursor, hasMore: nextCursor !== null }
}

function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

test('实时广播与初始历史重叠时按 UUID 去重，不覆盖期间的新消息', async () => {
  const response = deferred()
  const history = new ChatHistory(() => response.promise)
  const loading = history.setConnected(true)
  assert.equal(history.state.isReady, false)
  assert.equal(history.state.isLoading, true)
  history.addLive(message(2))
  history.addLive(message(3))
  response.resolve(page([message(1), message(2)], 'snapshot-2'))
  await loading
  assert.deepEqual(
    history.state.messages.map((m) => m.id),
    ['1', '2', '3'],
  )
  assert.equal(history.state.isReady, true)
  assert.equal(history.addLive(message(2)), false)
})

test('重连分页补齐超过一页的消息，并固定上界，避免实时消息推进补偿游标', async () => {
  const requests = []
  const firstCatchUp = deferred()
  const history = new ChatHistory(async (query) => {
    requests.push(query)
    if (requests.length === 1) return page([message(1)], 'sync-1')
    if (requests.length === 2) return firstCatchUp.promise
    if (requests.length === 3)
      return page(
        Array.from({ length: 20 }, (_, i) => message(i + 52)),
        'sync-71',
      )
    return page([], 'sync-80')
  })
  await history.setConnected(true)
  history.addLive(message(2))
  await history.setConnected(false)
  const catchUp = history.setConnected(true)
  history.addLive(message(80))
  firstCatchUp.resolve(
    page(
      Array.from({ length: 50 }, (_, i) => message(i + 2)),
      'sync-71',
      'page-51',
    ),
  )
  await catchUp
  assert.equal(history.state.messages.length, 72)
  assert.deepEqual(requests[1], { after: 'sync-1', until: undefined, limit: 50 })
  assert.deepEqual(requests[2], { after: 'page-51', until: 'sync-71', limit: 50 })
  assert.equal(history.state.messages.at(-1).id, '80')
  await history.setConnected(false)
  await history.setConnected(true)
  assert.equal(requests[3].after, 'sync-71')
})

test('中途补偿失败保留已加载消息；重试从上次完整同步边界开始', async () => {
  const requests = []
  const history = new ChatHistory(async (query) => {
    requests.push(query)
    switch (requests.length) {
      case 1:
        return page([message(1)], 'sync-1')
      case 2:
        return page([message(2)], 'sync-3', 'page-2')
      case 3:
        throw new Error('database unavailable')
      default:
        return page([message(2), message(3)], 'sync-3')
    }
  })
  await history.setConnected(true)
  await history.setConnected(false)
  await history.setConnected(true)
  assert.equal(history.state.isReady, false)
  assert.equal(history.state.error, 'database unavailable')
  assert.deepEqual(
    history.state.messages.map((m) => m.id),
    ['1', '2'],
  )
  await history.synchronize()
  assert.equal(requests[3].after, 'sync-1')
  assert.deepEqual(
    history.state.messages.map((m) => m.id),
    ['1', '2', '3'],
  )
  assert.equal(history.state.isReady, true)
  assert.equal(history.state.error, null)
})

test('断线重连与页面销毁后忽略旧请求的响应及错误', async () => {
  const old = deferred()
  let calls = 0
  const history = new ChatHistory(() =>
    ++calls === 1 ? old.promise : Promise.resolve(page([message(2)], 'sync-2')),
  )
  const initial = history.setConnected(true)
  await history.setConnected(false)
  await history.setConnected(true)
  old.resolve(page([message(1)], 'stale'))
  await initial
  assert.deepEqual(
    history.state.messages.map((m) => m.id),
    ['2'],
  )
  const pending = deferred()
  const disposed = new ChatHistory(() => pending.promise)
  const load = disposed.setConnected(true)
  disposed.dispose()
  pending.reject(new Error('stale failure'))
  await load
  assert.equal(disposed.state.error, null)
  assert.equal(disposed.state.messages.length, 0)
  assert.equal(disposed.state.isReady, false)
})

test('加载更早消息失败后可重试，成功后保留已有消息并正确更新分页', async () => {
  const requests = []
  const history = new ChatHistory(async (query) => {
    requests.push(query)
    if (requests.length === 1) return page([message(3)], 'sync-3', 'older-3')
    if (requests.length === 2) throw new Error('history temporarily unavailable')
    return page([message(1), message(2)], 'sync-3')
  })
  await history.setConnected(true)
  history.addLive(message(4))
  assert.equal(await history.loadOlder(), false)
  assert.equal(history.retryLoadsOlder, true)
  assert.equal(history.state.isReady, true)
  assert.equal(history.state.hasMore, true)
  assert.equal(await history.loadOlder(), true)
  assert.deepEqual(requests[2], { before: 'older-3', until: 'sync-3', limit: 50 })
  assert.deepEqual(
    history.state.messages.map((m) => m.id),
    ['1', '2', '3', '4'],
  )
  assert.equal(history.state.hasMore, false)
})

test('首次历史失败不会显示为空会话，重试仍请求最新一页', async () => {
  let calls = 0
  const history = new ChatHistory(async (query) => {
    assert.deepEqual(query, { limit: 50 })
    if (++calls === 1) throw new Error('offline')
    return page([], 'empty')
  })
  await history.setConnected(true)
  assert.equal(history.state.isReady, false)
  assert.equal(history.state.error, 'offline')
  await history.synchronize()
  assert.equal(history.state.isReady, true)
  assert.equal(history.state.error, null)
})

test('同一毫秒内的微秒发送时间仍保持正确顺序', () => {
  const messages = [message('a', 2), message('z', 1)]
  assert.equal(new Date(messages[0].sendTime).getTime(), new Date(messages[1].sendTime).getTime())
  assert.deepEqual(
    messages.sort(compareMessages).map((m) => m.id),
    ['z', 'a'],
  )
})

test('滚动锚点在插入旧消息之前捕获，包含请求期间收到的实时消息', async () => {
  const older = deferred()
  let calls = 0
  const history = new ChatHistory(() =>
    ++calls === 1 ? Promise.resolve(page([message(3)], 'sync-3', 'older-3')) : older.promise,
  )
  await history.setConnected(true)
  let captured
  const loading = history.loadOlder(() => {
    captured = history.state.messages.map((m) => m.id)
  })
  history.addLive(message(4))
  older.resolve(page([message(1), message(2)], 'sync-3'))
  assert.equal(await loading, true)
  assert.deepEqual(captured, ['3', '4'])
  assert.deepEqual(
    history.state.messages.map((m) => m.id),
    ['1', '2', '3', '4'],
  )
})
