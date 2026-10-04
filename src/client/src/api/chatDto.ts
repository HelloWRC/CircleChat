export interface IChatMessage {
  id: string
  conversationId: number
  body: string
  senderDisplayName: string
  senderUsername: string
  senderId: number
  senderAvatarUrl: string
  sendTime: string
}

export interface IReceiveChatMessageRsp {
  message: IChatMessage
}

export interface ISendChatMessageReq {
  message: string
  clientMessageId: string
}

export interface ISendChatMessageRsp {
  clientMessageId: string
  success: boolean
  error: string | null
}

export interface IChatReadyRsp {
  requestId: string
  conversationId: number
  success: boolean
  error: string | null
}

export interface IMessageHistoryRsp {
  messages: IChatMessage[]
  nextCursor: string | null
  hasMore: boolean
  snapshotCursor: string
}

export interface MessageHistoryQuery {
  before?: string
  after?: string
  until?: string
  limit?: number
}
