import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { getConversationMeta, getConversations, type ConversationInfo } from '@/api'
import { HttpError } from '@/api/instance'
import { useUserStore } from './user'

export type Conversation = Required<ConversationInfo>

export const useConversationsStore = defineStore('conversations', () => {
  const user = useUserStore()
  const conversations = ref<Conversation[]>([])
  const drafts = ref<Record<string, string>>({})
  const isLoading = ref(false)
  const hasMore = ref(true)
  const error = ref<string | null>(null)
  const total = ref(0)
  const currentConversation = ref<Conversation | null>(null)
  const isLoadingCurrent = ref(false)
  const currentError = ref<string | null>(null)
  const isEmpty = computed(
    () => !isLoading.value && !error.value && !hasMore.value && !conversations.value.length,
  )
  let nextPage = 0
  let generation = 0
  let currentRequest = 0

  function clearConversations() {
    generation++
    currentRequest++
    conversations.value = []
    isLoading.value = false
    hasMore.value = true
    error.value = null
    total.value = 0
    nextPage = 0
    currentConversation.value = null
    isLoadingCurrent.value = false
    currentError.value = null
  }

  function reset() {
    clearConversations()
    drafts.value = {}
  }

  function refresh() {
    clearConversations()
    return loadMore()
  }

  watch(() => user.user?.id, reset, { flush: 'sync' })

  async function loadMore() {
    if (isLoading.value || !hasMore.value || !user.isAuthenticated) return
    const version = generation
    isLoading.value = true
    error.value = null
    try {
      const response = await getConversations({ params: { page: nextPage, size: 20 } })
      if (version !== generation) return
      const page = response.content
      if (
        response.statusCode !== 200 ||
        !page ||
        !Array.isArray(page.conversations) ||
        page.page !== nextPage ||
        typeof page.hasMore !== 'boolean' ||
        page.conversations.some((entry) => !Number.isSafeInteger(entry.id) || entry.id! < 0)
      ) {
        throw new Error('会话列表响应异常，请重试')
      }
      const entries = new Map(conversations.value.map((entry) => [entry.id, entry]))
      const current = currentConversation.value
      for (const entry of page.conversations) {
        entries.set(
          entry.id!,
          current && current.id === entry.id
            ? current
            : {
                id: entry.id!,
                title: entry.title?.trim() || `会话 ${entry.id}`,
                type: entry.type ?? 'Unknown',
                avatarUrl: entry.avatarUrl?.trim() || '',
                isMuted: entry.isMuted ?? false,
                hasNewMessage: entry.hasNewMessage ?? false,
              },
        )
      }
      conversations.value = [...entries.values()]
      total.value = page.totalElements ?? entries.size
      hasMore.value = page.hasMore
      nextPage++
    } catch {
      if (version === generation) error.value = '会话列表加载失败，请重试'
    } finally {
      if (version === generation) isLoading.value = false
    }
  }

  async function loadCurrent(id: number): Promise<boolean> {
    const request = ++currentRequest
    currentConversation.value = null
    currentError.value = null
    isLoadingCurrent.value = false
    if (!user.isAuthenticated || !Number.isSafeInteger(id) || id < 0) {
      currentError.value = '会话不存在或无权访问'
      return false
    }
    isLoadingCurrent.value = true
    try {
      const response = await getConversationMeta({ pathParams: { id } })
      if (request !== currentRequest) return false
      const info = response.content?.info
      if (response.statusCode !== 200 || !info || info.id !== id) {
        throw new Error('会话信息响应异常')
      }
      currentConversation.value = {
        id,
        title: info.title?.trim() || `会话 ${id}`,
        type: info.type ?? 'Unknown',
        avatarUrl: info.avatarUrl?.trim() || '',
        isMuted: info.isMuted ?? false,
        hasNewMessage: info.hasNewMessage ?? false,
      }
      const index = conversations.value.findIndex((entry) => entry.id === id)
      if (index >= 0) conversations.value[index] = currentConversation.value
      return true
    } catch (error) {
      if (request === currentRequest) {
        currentError.value =
          error instanceof HttpError && (error.status === 403 || error.status === 404)
            ? '会话不存在或无权访问'
            : '会话信息加载失败，请重试'
      }
      return false
    } finally {
      if (request === currentRequest) isLoadingCurrent.value = false
    }
  }

  function title(id: number) {
    if (currentConversation.value?.id === id) return currentConversation.value.title
    return (
      conversations.value.find((entry) => entry.id === id)?.title ??
      (id === 0 ? '主聊天室' : `会话 ${id}`)
    )
  }

  return {
    conversations,
    drafts,
    isLoading,
    hasMore,
    error,
    total,
    isEmpty,
    loadMore,
    refresh,
    title,
    currentConversation,
    isLoadingCurrent,
    currentError,
    loadCurrent,
  }
})
