export { api } from './instance'
export { logout } from './auth'
export { loadMessageHistory } from './chatHistoryApi'
export { login } from './generated/services/authenticateController'
export { me, register, updateProfile, changePassword } from './generated/services/usersController'
export { getConversationMeta, getConversations } from './generated/services/conversationsController'
export {
  acceptFriendshipRequest,
  deleteFriend,
  getConversationIdOfFriend,
  getFriendInfo,
  getFriendshipRequests,
  getMyFriends,
  ignoreFriendshipRequest,
  rejectFriendshipRequest,
  sendFriendshipRequest,
} from './generated/services/friendsController'
export type * from './generated/components'
