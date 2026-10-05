import assert from 'node:assert/strict'
import { after, before, beforeEach, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

let server
let ChatClient
const originalWindow = Object.getOwnPropertyDescriptor(globalThis, 'window')

class StompTransport {
  connected = false
  active = false
  subscriptions = new Map()
  publishes = []
  unsubscribes = []
  deactivations = 0

  configure(options) {
    this.options = options
  }

  activate() {
    this.active = true
  }

  connect(autoReady = true) {
    this.connected = true
    this.options.onConnect({})
    if (autoReady) this.acknowledgeReady()
  }

  close() {
    this.connected = false
    this.subscriptions.clear()
    this.options.onWebSocketClose({ code: 1006, reason: 'network interrupted' })
  }

  subscribe(destination, callback) {
    assert.ok(this.connected)
    assert.equal(this.subscriptions.has(destination), false, 'Only one proxy per channel')
    this.subscriptions.set(destination, callback)
    return {
      unsubscribe: () => {
        assert.ok(this.connected, 'Do not unsubscribe using a closed connection')
        this.unsubscribes.push(destination)
        this.subscriptions.delete(destination)
      },
    }
  }

  publish(frame) {
    assert.ok(this.connected)
    this.publishes.push(frame)
  }

  receive(destination, payload) {
    this.subscriptions.get(destination)?.({ body: JSON.stringify(payload) })
  }

  acknowledgeReady(
    request = this.publishes.findLast((frame) => frame.destination.endsWith('/ready')),
    success = true,
  ) {
    if (!request) return
    const { requestId } = JSON.parse(request.body)
    const conversationId = Number(request.destination.split('/')[3])
    this.receive('/user/queue/chat/ready', {
      requestId,
      conversationId,
      success,
      error: success ? null : '会话不存在',
    })
  }

  forceDisconnect() {
    this.close()
  }

  acknowledge(success = true) {
    const request = JSON.parse(this.publishes.at(-1).body)
    this.receive('/user/queue/chat/acks', {
      clientMessageId: request.clientMessageId,
      success,
      error: success ? null : '服务器未能发送消息，请稍后重试',
    })
  }

  async deactivate() {
    this.active = false
    this.connected = false
    this.deactivations++
    this.subscriptions.clear()
  }
}

before(async () => {
  server = await createServer({
    configFile: false,
    root: fileURLToPath(new URL('../', import.meta.url)),
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: 'custom',
  })
  ;({ default: ChatClient } = await server.ssrLoadModule('/src/api/chat.ts'))
})

beforeEach((context) => {
  Object.defineProperty(globalThis, 'window', {
    configurable: true,
    value: { location: { protocol: 'https:', host: 'chat.example.com' } },
  })
  context.mock.method(console, 'warn', () => {})
})

after(async () => {
  await server?.close()
  if (originalWindow) Object.defineProperty(globalThis, 'window', originalWindow)
  else delete globalThis.window
})

test('切换会话清理旧订阅与未确认发送，新会话就绪前禁止发送', async () => {
  const { client, transport } = context()
  const first = client.subscribe(0, () => {})
  transport.connect()
  const pending = client.send(0, 'old draft')
  first.dispose()
  await assert.rejects(pending, /已离开会话/)
  const messages = []
  const second = client.subscribe(42, (payload) => messages.push(payload))
  assert.equal(transport.subscriptions.has('/topic/conversations/0/messages'), false)
  assert.equal(transport.subscriptions.has('/topic/conversations/42/messages'), true)
  assert.equal(transport.publishes.at(-1).destination, '/app/conversations/42/ready')
  await assert.rejects(client.send(42, 'too early'), /尚未连接/)
  transport.acknowledgeReady()
  transport.receive('/topic/conversations/0/messages', { message: { body: 'stale' } })
  transport.receive('/topic/conversations/42/messages', { message: { body: 'new' } })
  assert.equal(messages.length, 1)
  const sent = client.send(42, 'new draft')
  assert.equal(transport.publishes.at(-1).destination, '/app/conversations/42/messages/send')
  transport.acknowledge()
  await sent
  second.dispose()
  await client.dispose()
})

test('就绪确认到达前切换会话，旧确认不会允许新会话发送', async () => {
  const { client, transport } = context()
  const old = client.subscribe(0, () => {})
  transport.connect(false)
  const oldReady = transport.publishes.at(-1)
  old.dispose()
  client.subscribe(7, () => {})
  transport.acknowledgeReady(oldReady)
  await assert.rejects(client.send(7, 'not ready'), /尚未连接/)
  transport.acknowledgeReady()
  const sent = client.send(7, 'ready')
  transport.acknowledge()
  await sent
  await client.dispose()
})

function context() {
  const transport = new StompTransport()
  const client = new ChatClient(transport)
  return { client, transport }
}

test('断线时禁止发送；每次重连恢复所有订阅并继续收发消息', async () => {
  const { client, transport } = context()
  const messages = []
  const states = []
  client.subscribe(0, (payload) => messages.push(payload))
  client.onConnectionChange((connected) => states.push(connected))
  await assert.rejects(client.send(0, 'before connect'), /尚未连接/)
  for (let attempt = 0; attempt < 3; attempt++) {
    transport.connect()
    assert.deepEqual(
      [...transport.subscriptions.keys()],
      ['/user/queue/chat/acks', '/user/queue/chat/ready', '/topic/conversations/0/messages'],
    )
    transport.receive('/topic/conversations/0/messages', {
      message: { body: `received ${attempt}` },
    })
    const sent = client.send(0, `sent ${attempt}`)
    transport.acknowledge()
    await sent
    transport.close()
    await assert.rejects(client.send(0, 'offline'), /尚未连接/)
  }
  assert.equal(messages.length, 3)
  assert.equal(
    transport.publishes.filter((frame) => frame.destination.endsWith('/messages/send')).length,
    3,
  )
  assert.deepEqual(states, [false, true, false, true, false, true, false])
  await client.dispose()
})

test('发送等待匹配的服务端确认，publish 成功不能被当作发送成功', async () => {
  const { client, transport } = context()
  client.subscribe(0, () => {})
  transport.connect()
  let resolved = false
  const sent = client.send(0, 'hello').then(() => {
    resolved = true
  })
  await Promise.resolve()
  assert.equal(resolved, false)
  assert.equal(transport.publishes.at(-1).headers['content-type'], 'application/json')
  transport.receive('/user/queue/chat/acks', { clientMessageId: 'another-request', success: true })
  await Promise.resolve()
  assert.equal(resolved, false)
  transport.acknowledge()
  await sent
  assert.equal(resolved, true)
  await client.dispose()
})

test('服务端拒绝、连接中断、确认超时均返回失败', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const { client, transport } = context()
  client.subscribe(0, () => {})
  transport.connect()
  const rejected = client.send(0, 'rejected')
  transport.acknowledge(false)
  await assert.rejects(rejected, /服务器未能发送/)
  const interrupted = client.send(0, 'interrupted')
  transport.close()
  await assert.rejects(interrupted, /连接已断开/)
  transport.connect()
  const timedOut = client.send(0, 'no acknowledgement')
  t.mock.timers.tick(15000)
  await assert.rejects(timedOut, /未收到服务器确认/)
  await client.dispose()
})

test('离线取消订阅不访问失效句柄，也不会在重连时复活订阅', async () => {
  const { client, transport } = context()
  const subscription = client.subscribe(0, () => {})
  transport.connect()
  transport.close()
  subscription.dispose()
  transport.connect()
  assert.equal(transport.subscriptions.has('/topic/conversations/0/messages'), false)
  const newer = client.subscribe(0, () => {})
  subscription.dispose()
  assert.equal(transport.subscriptions.has('/topic/conversations/0/messages'), true)
  newer.dispose()
  assert.deepEqual(transport.unsubscribes, ['/topic/conversations/0/messages'])
  await client.dispose()
})

test('共享订阅只在最后一个监听者退出时取消；登出关闭连接并拒绝未确认发送', async () => {
  const { client, transport } = context()
  const first = client.subscribe(0, () => {})
  const second = client.subscribe(0, () => {})
  transport.connect()
  first.dispose()
  assert.equal(transport.subscriptions.has('/topic/conversations/0/messages'), true)
  const pending = client.send(0, 'pending')
  await client.dispose()
  await assert.rejects(pending, /聊天连接已关闭/)
  second.dispose()
  assert.equal(transport.active, false)
  assert.equal(transport.deactivations, 1)
})

test('订阅完成确认前不能发送；确认仅接受当前连接的匹配请求', async () => {
  const { client, transport } = context()
  const states = []
  client.subscribe(0, () => {})
  client.onConnectionChange((connected) => states.push(connected))
  transport.connect(false)
  const oldRequest = transport.publishes.at(-1)
  assert.equal(oldRequest.destination, '/app/conversations/0/ready')
  await assert.rejects(client.send(0, 'too early'), /尚未连接/)
  assert.deepEqual(states, [false])
  transport.close()
  transport.connect(false)
  transport.acknowledgeReady(oldRequest)
  await assert.rejects(client.send(0, 'stale confirmation'), /尚未连接/)
  transport.acknowledgeReady()
  const sent = client.send(0, 'ready now')
  assert.equal(transport.publishes.at(-1).destination, '/app/conversations/0/messages/send')
  transport.acknowledge()
  await sent
  assert.deepEqual(states, [false, true])
  assert.throws(() => client.subscribe(-1, () => {}), /会话不存在/)
  await assert.rejects(client.send(-1, 'invalid room'), /会话不存在/)
  await client.dispose()
})

test('订阅确认超时或失败会断开当前连接，等待重新订阅', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const { client, transport } = context()
  client.subscribe(0, () => {})
  transport.connect(false)
  t.mock.timers.tick(10000)
  assert.equal(transport.connected, false)
  await assert.rejects(client.send(0, 'offline'), /尚未连接/)
  transport.connect(false)
  transport.acknowledgeReady(undefined, false)
  assert.equal(transport.connected, false)
  await client.dispose()
})
