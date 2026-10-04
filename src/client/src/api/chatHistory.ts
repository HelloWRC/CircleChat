import type { IChatMessage, IMessageHistoryRsp, MessageHistoryQuery } from './chatDto.ts'

export function createChatHistoryState() {
  return {
    messages: [] as IChatMessage[],
    connected: false,
    isReady: false,
    isLoading: false,
    isLoadingOlder: false,
    hasMore: false,
    error: null as string | null,
  }
}

type HistoryLoader = (query: MessageHistoryQuery) => Promise<IMessageHistoryRsp>

// Keep microsecond precision; Date would truncate the server's ordering to milliseconds.
export function compareMessages(a: IChatMessage, b: IChatMessage) {
  return a.sendTime < b.sendTime
    ? -1
    : a.sendTime > b.sendTime
      ? 1
      : a.id < b.id
        ? -1
        : a.id > b.id
          ? 1
          : 0
}

export class ChatHistory {
  private generation = 0
  private syncCursor: string | undefined
  private olderCursor: string | undefined
  private olderUpper: string | undefined
  private retryOlder = false

  constructor(
    private readonly load: HistoryLoader,
    readonly state = createChatHistoryState(),
  ) {}

  addLive(message: IChatMessage) {
    const isNew = !this.state.messages.some((entry) => entry.id === message.id)
    this.merge([message])
    return isNew
  }

  private merge(messages: IChatMessage[]) {
    const merged = new Map(this.state.messages.map((message) => [message.id, message]))
    for (const message of messages) {
      // Keep the original broadcast's sender snapshot when history overlaps it.
      if (!merged.has(message.id)) merged.set(message.id, message)
    }
    this.state.messages = [...merged.values()].sort(compareMessages)
  }

  async setConnected(connected: boolean) {
    this.generation++
    this.state.connected = connected
    this.state.isReady = false
    this.state.isLoading = false
    this.state.isLoadingOlder = false
    if (connected) await this.synchronize()
  }

  async synchronize() {
    if (!this.state.connected || this.state.isLoading) return
    const generation = ++this.generation
    this.state.isLoading = true
    this.state.isReady = false
    this.state.error = null
    this.retryOlder = false
    try {
      if (this.syncCursor === undefined) {
        const page = await this.load({ limit: 50 })
        if (generation !== this.generation) return
        this.merge(page.messages)
        this.olderCursor = page.nextCursor ?? undefined
        this.olderUpper = page.snapshotCursor
        this.state.hasMore = page.hasMore
        this.syncCursor = page.snapshotCursor
      } else {
        let after = this.syncCursor
        let until: string | undefined
        for (;;) {
          const page = await this.load({ after, until, limit: 50 })
          if (generation !== this.generation) return
          until ??= page.snapshotCursor
          this.merge(page.messages)
          if (!page.hasMore) {
            // Never advance past a partially failed catch-up operation.
            this.syncCursor = until
            break
          }
          if (!page.nextCursor || page.nextCursor === after)
            throw new Error('历史消息分页异常，请重试')
          after = page.nextCursor
        }
      }
      this.state.isReady = true
    } catch (error) {
      if (generation === this.generation) {
        this.state.error = error instanceof Error ? error.message : '历史消息加载失败，请重试'
      }
    } finally {
      if (generation === this.generation) this.state.isLoading = false
    }
  }

  async loadOlder(beforeMerge?: () => void): Promise<boolean> {
    if (
      !this.state.isReady ||
      !this.state.hasMore ||
      this.state.isLoadingOlder ||
      !this.olderCursor
    )
      return false
    const generation = this.generation
    this.state.isLoadingOlder = true
    this.state.error = null
    try {
      const page = await this.load({ before: this.olderCursor, until: this.olderUpper, limit: 50 })
      if (generation !== this.generation) return false
      beforeMerge?.()
      this.merge(page.messages)
      this.olderCursor = page.nextCursor ?? undefined
      this.state.hasMore = page.hasMore
      return true
    } catch (error) {
      if (generation === this.generation) {
        this.retryOlder = true
        this.state.error = error instanceof Error ? error.message : '历史消息加载失败，请重试'
      }
      return false
    } finally {
      if (generation === this.generation) this.state.isLoadingOlder = false
    }
  }

  get retryLoadsOlder() {
    return this.retryOlder
  }

  dispose() {
    this.generation++
    this.state.connected = false
    this.state.isReady = false
  }
}
