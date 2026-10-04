import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'
import type {IReceiveChatMessageRsp} from "@/api/chatDto.ts";
import TaskCompletionSource from "@/TaskCompletionSource.ts";

export class ChatClientSubscription {
  dispose() {
  }

  constructor(disposeCallback: () => void) {
    this.dispose = disposeCallback
  }
}

class ChatClient {
  private client: Client
  private subscriptions: Record<string, Set<(message: IReceiveChatMessageRsp) => void>> = {}
  private proxies: Record<string, (message: IMessage) => void> = {}
  private proxySubscriptions: Record<string, StompSubscription> = {}
  private connected: boolean = false
  private setupTcs: TaskCompletionSource<boolean>

  constructor() {
    this.setupTcs = new TaskCompletionSource()

    console.log(this.setupTcs)
    this.client = new Client({
      brokerURL: `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/ws`,
      reconnectDelay: 5000,
      onConnect: () => {
        console.log('STOMP connected')
        console.log(this.setupTcs)
        this.setupTcs.setResult(true)
      },

      onStompError: (frame) => {
        console.error('STOMP error:', frame.headers['message'])
        console.error(frame.body)
      },
    })

    this.client.activate()
  }


  private async registerProxyIfNotRegistered(channel: string) {
    await this.setupTcs.promise
    if (this.proxies[channel] != undefined) {
      return
    }

    if (this.subscriptions[channel] === undefined) {
      this.subscriptions[channel] = new Set<(message: IReceiveChatMessageRsp) => void>()
    }
    const proxy = (message: IMessage) => {
      const msg: IReceiveChatMessageRsp = JSON.parse(message.body)
      const subscriptions = this.subscriptions[channel]
      if (!subscriptions) {
        return
      }
      subscriptions.forEach((x) => x(msg))
    }
    this.proxySubscriptions[channel] = this.client.subscribe(`/topic/chat/${channel}`, proxy)
    this.proxies[channel] = proxy
  }

  async subscribe(channel: string, callback: (message: IReceiveChatMessageRsp) => void) {
    await this.registerProxyIfNotRegistered(channel)
    this.subscriptions[channel]?.add(callback)
    console.log('[ELYSIADBG] sub SUCCESSFULLY')

    return new ChatClientSubscription(() => {
      if (this.subscriptions[channel] === undefined) {
        return
      }
      this.subscriptions[channel].delete(callback)
      if (this.subscriptions[channel].size <= 0 && this.proxySubscriptions[channel] != undefined) {
        this.proxySubscriptions[channel].unsubscribe()
        delete this.proxySubscriptions[channel]
        delete this.proxies[channel]
      }
    })
  }

  send(message: string) {
    this.client.publish({
      destination: '/app/message/send',
      body: JSON.stringify({
        message: message,
      }),
    })
  }
}

export default ChatClient

let chatClientGlobal : ChatClient | null = null;

export function useChatClient() {
  if (chatClientGlobal === null) {
    chatClientGlobal = new ChatClient()
  }

  return chatClientGlobal
}
