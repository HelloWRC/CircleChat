import { history } from './generated/services/messagesController'
import type { IMessageHistoryRsp, MessageHistoryQuery } from './chatDto'

export async function loadMessageHistory(
  conversationId: number,
  query: MessageHistoryQuery,
): Promise<IMessageHistoryRsp> {
  const response = await history({ pathParams: { conversationId }, params: query })
  if (!response.content) throw new Error('历史消息响应为空，请重试')
  return response.content as IMessageHistoryRsp
}
