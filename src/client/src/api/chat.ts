import { Client, TickerStrategy, type IMessage, type StompSubscription } from '@stomp/stompjs'
import type { IChatReadyRsp, IReceiveChatMessageRsp, ISendChatMessageRsp } from './chatDto.ts'

export const MAIN_CONVERSATION_ID = 0

function requireConversation(conversationId: number) {
  if (conversationId !== MAIN_CONVERSATION_ID) throw new Error('会话不存在')
}

export class ChatClientSubscription {
  private disposed = false

  constructor(private readonly disposeCallback: () => void) {}

  dispose() {
    if (this.disposed) return
    this.disposed = true
    this.disposeCallback()
  }
}

type MessageCallback = (message: IReceiveChatMessageRsp) => void
type PendingSend = {
  resolve: () => void
  reject: (error: Error) => void
  timeout: ReturnType<typeof setTimeout>
}

class ChatClient {
  private readonly client: Client
  private readonly subscriptions = new Map<number, Set<MessageCallback>>()
  private readonly proxySubscriptions = new Map<number, StompSubscription>()
  private readonly readyRequests = new Map<
    string,
    { conversationId: number; timeout: ReturnType<typeof setTimeout> }
  >()
  private readonly connectionListeners = new Set<(connected: boolean) => void>()
  private readonly pendingSends = new Map<string, PendingSend>()
  private connected = false
  private disposed = false

  constructor(client?: Client) {
    this.client = client ?? new Client()
    this.client.configure({
      brokerURL: `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/ws`,
      reconnectDelay: 5000,
      connectionTimeout: 10000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      heartbeatStrategy: TickerStrategy.Worker,
      heartbeatToleranceMultiplier: 3,
      discardWebsocketOnCommFailure: true,
      onConnect: () => {
        // STOMP subscriptions belong to a connection and must be recreated after every reconnect.
        this.proxySubscriptions.clear()
        this.clearReadyRequests()
        this.client.subscribe('/user/queue/chat/acks', (message) =>
          this.receiveAcknowledgement(message),
        )
        this.client.subscribe('/user/queue/chat/ready', (message) => this.receiveReady(message))
        for (const channel of this.subscriptions.keys()) this.registerProxy(channel)
        if (this.readyRequests.size === 0) this.setConnected(true)
      },
      onWebSocketClose: (event) => {
        this.connectionLost('连接已断开，未确认的消息可能已发送，请检查聊天记录')
        if (!this.disposed) console.warn('WebSocket closed:', event.code, event.reason)
      },
      onWebSocketError: (event) => {
        console.error('WebSocket error:', event)
        this.connectionLost('连接异常，请等待重新连接后重试')
      },
      onStompError: (frame) => {
        console.error('STOMP error:', frame.headers['message'], frame.body)
        this.connectionLost('服务器拒绝了消息，请检查连接后重试')
      },
    })
    this.client.activate()
  }

  onConnectionChange(callback: (connected: boolean) => void) {
    this.connectionListeners.add(callback)
    callback(this.connected)
    return new ChatClientSubscription(() => this.connectionListeners.delete(callback))
  }

  private setConnected(connected: boolean) {
    if (this.connected === connected) return
    this.connected = connected
    for (const callback of this.connectionListeners) callback(connected)
  }

  private connectionLost(reason: string) {
    this.proxySubscriptions.clear()
    this.clearReadyRequests()
    this.setConnected(false)
    for (const pending of this.pendingSends.values()) {
      clearTimeout(pending.timeout)
      pending.reject(new Error(reason))
    }
    this.pendingSends.clear()
  }

  private clearReadyRequests() {
    for (const request of this.readyRequests.values()) clearTimeout(request.timeout)
    this.readyRequests.clear()
  }

  private receiveReady(message: IMessage) {
    const response: IChatReadyRsp = JSON.parse(message.body)
    const request = this.readyRequests.get(response.requestId)
    if (!request || request.conversationId !== response.conversationId) return
    clearTimeout(request.timeout)
    this.readyRequests.delete(response.requestId)
    if (!response.success) {
      this.connectionLost(response.error || '无法订阅聊天室')
      this.client.forceDisconnect()
      return
    }
    if (this.readyRequests.size === 0) this.setConnected(true)
  }

  private registerProxy(channel: number) {
    if (this.proxySubscriptions.has(channel)) return
    const proxy = this.client.subscribe(`/topic/conversations/${channel}/messages`, (message) => {
      const payload: IReceiveChatMessageRsp = JSON.parse(message.body)
      for (const callback of this.subscriptions.get(channel) ?? []) callback(payload)
    })
    this.proxySubscriptions.set(channel, proxy)
    this.setConnected(false)
    const requestId = globalThis.crypto.randomUUID()
    const timeout = setTimeout(() => {
      this.connectionLost('未收到订阅确认，请等待重新连接')
      this.client.forceDisconnect()
    }, 10000)
    this.readyRequests.set(requestId, { conversationId: channel, timeout })
    this.client.publish({
      destination: `/app/conversations/${channel}/ready`,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ requestId }),
    })
  }

  subscribe(channel: number, callback: MessageCallback) {
    requireConversation(channel)
    if (this.disposed) throw new Error('聊天连接已关闭')
    let callbacks = this.subscriptions.get(channel)
    if (!callbacks) {
      callbacks = new Set()
      this.subscriptions.set(channel, callbacks)
    }
    callbacks.add(callback)
    if (this.connected && this.client.connected) this.registerProxy(channel)

    return new ChatClientSubscription(() => {
      callbacks.delete(callback)
      if (callbacks.size > 0) return
      this.subscriptions.delete(channel)
      const proxy = this.proxySubscriptions.get(channel)
      if (this.client.connected) proxy?.unsubscribe()
      this.proxySubscriptions.delete(channel)
      for (const [id, request] of this.readyRequests) {
        if (request.conversationId !== channel) continue
        clearTimeout(request.timeout)
        this.readyRequests.delete(id)
      }
      if (this.client.connected && !this.disposed && this.readyRequests.size === 0)
        this.setConnected(true)
    })
  }

  private receiveAcknowledgement(message: IMessage) {
    const acknowledgement: ISendChatMessageRsp = JSON.parse(message.body)
    const pending = this.pendingSends.get(acknowledgement.clientMessageId)
    if (!pending) return
    clearTimeout(pending.timeout)
    this.pendingSends.delete(acknowledgement.clientMessageId)
    if (acknowledgement.success) pending.resolve()
    else pending.reject(new Error(acknowledgement.error || '服务器未能发送消息，请稍后重试'))
  }

  async send(conversationId: number, message: string) {
    requireConversation(conversationId)
    if (!this.connected || !this.client.connected || !this.proxySubscriptions.has(conversationId)) {
      throw new Error('聊天室尚未连接，请等待连接恢复后重试')
    }
    const clientMessageId = globalThis.crypto.randomUUID()
    return new Promise<void>((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pendingSends.delete(clientMessageId)
        reject(new Error('未收到服务器确认，消息可能已发送，请检查聊天记录后再重试'))
      }, 15000)
      this.pendingSends.set(clientMessageId, { resolve, reject, timeout })
      try {
        this.client.publish({
          destination: `/app/conversations/${conversationId}/messages/send`,
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ message, clientMessageId }),
        })
      } catch (error) {
        clearTimeout(timeout)
        this.pendingSends.delete(clientMessageId)
        reject(error)
      }
    })
  }

  dispose() {
    this.disposed = true
    this.connectionLost('聊天连接已关闭')
    this.subscriptions.clear()
    this.connectionListeners.clear()
    return this.client.deactivate({ force: true })
  }
}

export default ChatClient

let chatClientGlobal: ChatClient | null = null

export function useChatClient() {
  chatClientGlobal ??= new ChatClient()
  return chatClientGlobal
}

export function disconnectChatClient() {
  const client = chatClientGlobal
  chatClientGlobal = null
  return client?.dispose()
}
