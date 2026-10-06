import { ref, watch } from 'vue'
import { defineStore } from 'pinia'
import {
  acceptFriendshipRequest,
  deleteFriend,
  getConversationIdOfFriend,
  getFriendInfo,
  getFriendshipRequests,
  getMyFriends,
  ignoreFriendshipRequest,
  rejectFriendshipRequest,
  sendFriendshipRequest,
  type FriendInfo,
  type FriendshipRequestInfo,
} from '@/api'
import { HttpError } from '@/api/instance'
import { useUserStore } from './user'
import { useConversationsStore } from './conversations'

type Friend = FriendInfo & Required<Pick<FriendInfo, 'userId' | 'username' | 'isFriend'>>
export type FriendRequest = FriendshipRequestInfo &
  Required<Pick<FriendshipRequestInfo, 'id' | 'senderUsername' | 'targetUsername' | 'state'>>
export type RequestDirection = 'received' | 'sent'
export type RequestAction = 'accept' | 'ignore' | 'reject'

function createRequestPage() {
  return {
    requests: [] as FriendRequest[],
    nextPage: 0,
    hasMore: true,
    hasLoaded: false,
    isLoading: false,
    error: null as string | null,
    retryRefresh: true,
  }
}

function isFriendRequest(value: unknown): value is FriendRequest {
  if (!value || typeof value !== 'object') return false
  const info = value as FriendshipRequestInfo
  return (
    Number.isSafeInteger(info.id) &&
    info.id! > 0 &&
    typeof info.senderUsername === 'string' &&
    !!info.senderUsername.trim() &&
    typeof info.targetUsername === 'string' &&
    !!info.targetUsername.trim() &&
    ['Open', 'Accepted', 'Rejected', 'Ignored'].includes(info.state ?? '')
  )
}

function isFriendInfo(value: unknown): value is Friend {
  if (!value || typeof value !== 'object') return false
  const info = value as FriendInfo
  return (
    Number.isSafeInteger(info.userId) &&
    typeof info.username === 'string' &&
    !!info.username.trim() &&
    typeof info.isFriend === 'boolean'
  )
}

export const useFriendsStore = defineStore('friends', () => {
  const user = useUserStore()
  const conversations = useConversationsStore()
  const friends = ref<Friend[]>([])
  const profiles = ref(new Map<string, Friend>())
  const isLoading = ref(false)
  const hasLoaded = ref(false)
  const error = ref<string | null>(null)
  const loadingDetails = ref(new Set<string>())
  const detailErrors = ref(new Map<string, string>())
  const detailRequests = new Map<string, Promise<void>>()
  const profileVersions = new Map<string, number>()
  const detailOrders = new Map<string, number>()
  const requestPages = ref({ received: createRequestPage(), sent: createRequestPage() })
  const processingRequests = ref(new Map<number, RequestAction>())
  const requestActionErrors = ref(new Map<number, string>())
  const sendingRequests = new Set<string>()
  const pageOrders = { received: 0, sent: 0 }
  const pageRequests = new Map<RequestDirection, Promise<void>>()
  const requestUpdates = new Map<number, { order: number; info: FriendRequest }>()
  let listRequest: Promise<void> | null = null
  let listOrder = 0
  let generation = 0
  let sequence = 0

  function reset() {
    generation++
    sequence = 0
    friends.value = []
    profiles.value.clear()
    isLoading.value = false
    hasLoaded.value = false
    error.value = null
    loadingDetails.value.clear()
    detailErrors.value.clear()
    detailRequests.clear()
    profileVersions.clear()
    detailOrders.clear()
    requestPages.value = { received: createRequestPage(), sent: createRequestPage() }
    processingRequests.value.clear()
    requestActionErrors.value.clear()
    sendingRequests.clear()
    pageRequests.clear()
    requestUpdates.clear()
    pageOrders.received = pageOrders.sent = 0
    listOrder = 0
    listRequest = null
  }

  watch([() => user.user?.id, () => user.isAuthenticated], reset, { flush: 'sync' })

  function cacheProfile(info: Friend, requestOrder: number) {
    if ((profileVersions.get(info.username) ?? 0) > requestOrder) return
    profiles.value.set(info.username, info)
    profileVersions.set(info.username, requestOrder)
    detailErrors.value.delete(info.username)
  }

  function loadFriends(): Promise<void> {
    if (!user.isAuthenticated) return Promise.resolve()
    if (listRequest) return listRequest
    const version = generation
    const requestOrder = ++sequence
    listOrder = requestOrder
    isLoading.value = true
    error.value = null
    listRequest = (async () => {
      try {
        // Deduplicate in this store so requests cannot be shared across login sessions.
        const response = await getMyFriends({ shareRequest: false })
        if (version !== generation || listOrder !== requestOrder) return
        const entries = response.content?.friends
        if (
          response.statusCode !== 200 ||
          !Array.isArray(entries) ||
          !entries.every(isFriendInfo)
        ) {
          throw new Error('好友列表响应异常')
        }
        for (const info of entries) cacheProfile(info, requestOrder)
        friends.value = entries
          .map((info) => profiles.value.get(info.username))
          .filter((info): info is Friend => !!info?.isFriend)
        hasLoaded.value = true
      } catch {
        if (version === generation && listOrder === requestOrder)
          error.value = '好友列表加载失败，请重试'
      } finally {
        if (version === generation && listOrder === requestOrder) {
          isLoading.value = false
          listRequest = null
        }
      }
    })()
    return listRequest
  }

  function loadDetails(username: string): Promise<void> {
    if (!user.isAuthenticated) return Promise.resolve()
    const pending = detailRequests.get(username)
    if (pending) return pending
    const version = generation
    const requestOrder = ++sequence
    detailOrders.set(username, requestOrder)
    loadingDetails.value.add(username)
    detailErrors.value.delete(username)
    const request = (async () => {
      try {
        const response = await getFriendInfo({
          pathParams: { username: encodeURIComponent(username) },
          shareRequest: false,
        })
        if (version !== generation || detailOrders.get(username) !== requestOrder) return
        const info = response.content
        if (response.statusCode !== 200 || !isFriendInfo(info) || info.username !== username) {
          throw new Error('好友资料响应异常')
        }
        cacheProfile(info, requestOrder)
        const current = profiles.value.get(username)
        friends.value = friends.value
          .map((entry) => (entry.username === username ? current : entry))
          .filter((entry): entry is Friend => !!entry?.isFriend)
      } catch (cause) {
        if (
          version !== generation ||
          detailOrders.get(username) !== requestOrder ||
          (profileVersions.get(username) ?? 0) > requestOrder
        )
          return
        const missing = cause instanceof HttpError && cause.status === 404
        if (missing) {
          profiles.value.delete(username)
          profileVersions.set(username, requestOrder)
          friends.value = friends.value.filter((entry) => entry.username !== username)
        }
        detailErrors.value.set(
          username,
          missing ? '用户不存在或已被删除' : '好友资料加载失败，请重试',
        )
      } finally {
        if (version === generation && detailOrders.get(username) === requestOrder) {
          loadingDetails.value.delete(username)
          detailRequests.delete(username)
        }
      }
    })()
    detailRequests.set(username, request)
    return request
  }

  async function searchUser(username: string): Promise<Friend | null> {
    if (!user.isAuthenticated) return null
    const target = username.trim()
    if (!target || target.length > 32) throw new Error('请输入不超过 32 个字符的用户名')
    const version = generation
    const requestOrder = ++sequence
    const response = await getFriendInfo({
      pathParams: { username: encodeURIComponent(target) },
      shareRequest: false,
    })
    if (version !== generation) return null
    const info = response.content
    if (response.statusCode !== 200 || !isFriendInfo(info) || info.username !== target) {
      throw new Error('用户资料响应异常')
    }
    cacheProfile(info, requestOrder)
    return info
  }

  function loadRequests(direction: RequestDirection, refresh = false): Promise<void> {
    if (!user.isAuthenticated) return Promise.resolve()
    const state = requestPages.value[direction]
    const pending = pageRequests.get(direction)
    if (pending && !refresh) return pending
    if (!refresh && !state.hasMore) return Promise.resolve()
    const version = generation
    const order = ++sequence
    pageOrders[direction] = order
    const page = refresh ? 0 : state.nextPage
    const username = user.user?.username
    state.isLoading = true
    state.error = null
    state.retryRefresh = refresh
    const request = (async () => {
      try {
        const response = await getFriendshipRequests({
          params: { sent: direction === 'sent', page, size: 20 },
          shareRequest: false,
        })
        if (version !== generation || pageOrders[direction] !== order) return
        const result = response.content
        if (
          response.statusCode !== 200 ||
          !result ||
          result.page !== page ||
          typeof result.hasMore !== 'boolean' ||
          !Array.isArray(result.content) ||
          !result.content.every(
            (info) =>
              isFriendRequest(info) &&
              (direction === 'sent' ? info.senderUsername : info.targetUsername) === username,
          )
        )
          throw new Error('好友申请列表响应异常')
        const entries = new Map((refresh ? [] : state.requests).map((info) => [info.id, info]))
        for (const info of result.content as FriendRequest[]) {
          const update = requestUpdates.get(info.id)
          entries.set(info.id, update && update.order > order ? update.info : info)
        }
        // A just-sent request may not appear in an older page snapshot.
        for (const update of requestUpdates.values()) {
          if (
            update.order > order &&
            (direction === 'sent' ? update.info.senderUsername : update.info.targetUsername) ===
              username
          ) {
            entries.set(update.info.id, update.info)
          }
        }
        state.requests = [...entries.values()].sort((a, b) => b.id - a.id)
        state.nextPage = page + 1
        state.hasMore = result.hasMore
        state.hasLoaded = true
      } catch {
        if (version === generation && pageOrders[direction] === order)
          state.error = '好友申请加载失败，请重试'
      } finally {
        if (version === generation && pageOrders[direction] === order) {
          state.isLoading = false
          pageRequests.delete(direction)
        }
      }
    })()
    pageRequests.set(direction, request)
    return request
  }

  async function sendRequest(username: string, note: string): Promise<FriendRequest | null> {
    if (!user.isAuthenticated || sendingRequests.has(username)) return null
    if (
      !username.trim() ||
      username.length > 32 ||
      username === user.user?.username ||
      note.length > 255
    ) {
      throw new Error('好友申请参数无效')
    }
    const version = generation
    sendingRequests.add(username)
    try {
      const response = await sendFriendshipRequest({
        data: { targetUsername: username, ...(note.trim() ? { note: note.trim() } : {}) },
        shareRequest: false,
      })
      if (version !== generation) return null
      const info = response.content
      if (
        response.statusCode !== 200 ||
        !isFriendRequest(info) ||
        info.senderUsername !== user.user?.username ||
        info.targetUsername !== username ||
        info.state !== 'Open'
      ) {
        throw new Error('发送好友申请响应异常')
      }
      requestUpdates.set(info.id, { order: ++sequence, info })
      const state = requestPages.value.sent
      state.requests = [info, ...state.requests.filter((entry) => entry.id !== info.id)]
      void loadRequests('sent', true)
      return info
    } catch (cause) {
      if (
        version === generation &&
        cause instanceof HttpError &&
        [404, 409].includes(cause.status)
      ) {
        void loadRequests('sent', true)
        void loadRequests('received', true)
        void loadDetails(username)
      }
      throw cause
    } finally {
      if (version === generation) sendingRequests.delete(username)
    }
  }

  async function processRequest(id: number, action: RequestAction): Promise<boolean> {
    const info = requestPages.value.received.requests.find((entry) => entry.id === id)
    if (!user.isAuthenticated || !info || info.state !== 'Open' || processingRequests.value.has(id))
      return false
    const version = generation
    processingRequests.value.set(id, action)
    requestActionErrors.value.delete(id)
    try {
      const operation = {
        accept: acceptFriendshipRequest,
        ignore: ignoreFriendshipRequest,
        reject: rejectFriendshipRequest,
      }[action]
      const response = await operation({ pathParams: { id }, shareRequest: false })
      if (version !== generation) return false
      if (response.statusCode !== 200) throw new Error('处理好友申请响应异常')
      const updated: FriendRequest = {
        ...info,
        state: action === 'accept' ? 'Accepted' : action === 'ignore' ? 'Ignored' : 'Rejected',
      }
      requestUpdates.set(id, { order: ++sequence, info: updated })
      requestPages.value.received.requests = requestPages.value.received.requests.map((entry) =>
        entry.id === id ? updated : entry,
      )
      if (action === 'accept') {
        // Invalidate pre-accept reads so neither stale profiles nor lists undo the new friendship.
        listOrder = ++sequence
        listRequest = null
        detailRequests.delete(info.senderUsername)
        const profile = profiles.value.get(info.senderUsername)
        if (profile) cacheProfile({ ...profile, isFriend: true }, sequence)
        void loadFriends()
        void loadDetails(info.senderUsername)
        void conversations.refresh()
      }
      return true
    } catch (cause) {
      if (version !== generation) return false
      requestActionErrors.value.set(
        id,
        cause instanceof HttpError && cause.status === 409
          ? '申请已处理或已成为好友，请查看刷新后的状态'
          : cause instanceof HttpError && cause.status === 404
            ? '申请不存在或已无法处理，请刷新列表'
            : '处理好友申请失败，请重试',
      )
      if (cause instanceof HttpError && [404, 409].includes(cause.status)) {
        void loadRequests('received', true)
        void loadDetails(info.senderUsername)
        void loadFriends()
      }
      return false
    } finally {
      if (version === generation) processingRequests.value.delete(id)
    }
  }

  async function getConversationId(username: string): Promise<number | null> {
    if (!user.isAuthenticated) return null
    const version = generation
    const response = await getConversationIdOfFriend({
      pathParams: { username: encodeURIComponent(username) },
      shareRequest: false,
    })
    if (version !== generation) return null
    const id = response.content?.id
    if (response.statusCode !== 200 || !Number.isSafeInteger(id) || id! <= 0) {
      throw new Error('好友会话响应异常')
    }
    return id!
  }

  async function removeFriend(username: string): Promise<boolean> {
    if (!user.isAuthenticated) return false
    const version = generation
    const response = await deleteFriend({
      pathParams: { username: encodeURIComponent(username) },
      shareRequest: false,
    })
    if (version !== generation) return false
    if (response.statusCode !== 200) throw new Error('删除好友响应异常')

    // Older list/detail responses must not restore the deleted relationship.
    const requestOrder = ++sequence
    const profile = profiles.value.get(username)
    if (profile) cacheProfile({ ...profile, isFriend: false }, requestOrder)
    else profileVersions.set(username, requestOrder)
    friends.value = friends.value.filter((entry) => entry.username !== username)
    void conversations.refresh()
    return true
  }

  return {
    friends,
    profiles,
    isLoading,
    hasLoaded,
    error,
    loadingDetails,
    detailErrors,
    requestPages,
    processingRequests,
    requestActionErrors,
    searchUser,
    loadRequests,
    sendRequest,
    processRequest,
    loadFriends,
    loadDetails,
    getConversationId,
    removeFriend,
  }
})
