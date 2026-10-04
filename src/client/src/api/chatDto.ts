export interface IChatMessage {
  id: number
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
