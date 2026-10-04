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

  connect() {
    this.connected = true
    this.options.onConnect({})
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
    server: { middlewareMode: true, hmr: false, watch: null },
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

function context() {
  const transport = new StompTransport()
  const client = new ChatClient(transport)
  return { client, transport }
}

test('断线时禁止发送；每次重连恢复所有订阅并继续收发消息', async () => {
  const { client, transport } = context()
  const messages = []
  const states = []
  client.subscribe('main', (payload) => messages.push(payload))
  client.subscribe('other', (payload) => messages.push(payload))
  client.onConnectionChange((connected) => states.push(connected))
  await assert.rejects(client.send('before connect'), /尚未连接/)
  for (let attempt = 0; attempt < 3; attempt++) {
    transport.connect()
    assert.deepEqual([...transport.subscriptions.keys()], [
      '/user/queue/chat/acks', '/topic/chat/main', '/topic/chat/other',
    ])
    transport.receive('/topic/chat/main', { message: { body: `received ${attempt}` } })
    const sent = client.send(`sent ${attempt}`)
    transport.acknowledge()
    await sent
    transport.close()
    await assert.rejects(client.send('offline'), /尚未连接/)
  }
  assert.equal(messages.length, 3)
  assert.equal(transport.publishes.length, 3)
  assert.deepEqual(states, [false, true, false, true, false, true, false])
  await client.dispose()
})

test('发送等待匹配的服务端确认，publish 成功不能被当作发送成功', async () => {
  const { client, transport } = context()
  client.subscribe('main', () => {})
  transport.connect()
  let resolved = false
  const sent = client.send('hello').then(() => { resolved = true })
  await Promise.resolve()
  assert.equal(resolved, false)
  assert.equal(transport.publishes[0].headers['content-type'], 'application/json')
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
  client.subscribe('main', () => {})
  transport.connect()
  const rejected = client.send('rejected')
  transport.acknowledge(false)
  await assert.rejects(rejected, /服务器未能发送/)
  const interrupted = client.send('interrupted')
  transport.close()
  await assert.rejects(interrupted, /连接已断开/)
  transport.connect()
  const timedOut = client.send('no acknowledgement')
  t.mock.timers.tick(15000)
  await assert.rejects(timedOut, /未收到服务器确认/)
  await client.dispose()
})

test('离线取消订阅不访问失效句柄，也不会在重连时复活订阅', async () => {
  const { client, transport } = context()
  const subscription = client.subscribe('main', () => {})
  transport.connect()
  transport.close()
  subscription.dispose()
  transport.connect()
  assert.equal(transport.subscriptions.has('/topic/chat/main'), false)
  const newer = client.subscribe('main', () => {})
  subscription.dispose()
  assert.equal(transport.subscriptions.has('/topic/chat/main'), true)
  newer.dispose()
  assert.deepEqual(transport.unsubscribes, ['/topic/chat/main'])
  await client.dispose()
})

test('共享订阅只在最后一个监听者退出时取消；登出关闭连接并拒绝未确认发送', async () => {
  const { client, transport } = context()
  const first = client.subscribe('main', () => {})
  const second = client.subscribe('main', () => {})
  transport.connect()
  first.dispose()
  assert.equal(transport.subscriptions.has('/topic/chat/main'), true)
  const pending = client.send('pending')
  await client.dispose()
  await assert.rejects(pending, /聊天连接已关闭/)
  second.dispose()
  assert.equal(transport.active, false)
  assert.equal(transport.deactivations, 1)
})
