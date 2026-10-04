import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { login as loginRequest, logout as logoutRequest, me } from '@/api'
import type { AuthLoginReq, UserInfo } from '@/api'
import { HttpError } from '@/api/instance'
import { disconnectChatClient } from '@/api/chat'

const STORAGE_KEY = 'circlechat.user'

function isUser(value: unknown): value is UserInfo {
  if (!value || typeof value !== 'object') return false
  const user = value as UserInfo
  return typeof user.id === 'number' && typeof user.username === 'string' && !!user.username
}

function readUser(): UserInfo | null {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    const user: unknown = saved ? JSON.parse(saved) : null
    if (isUser(user)) return user
  } catch {
    // Storage may be unavailable or contain invalid JSON; the server remains authoritative.
  }
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    // The in-memory session can still be restored without access to storage.
  }
  return null
}

export const useUserStore = defineStore('user', () => {
  const user = ref<UserInfo | null>(readUser())
  const initialized = ref(false)
  const isLoggingOut = ref(false)
  const isAuthenticated = computed(() => initialized.value && user.value !== null)
  const displayName = computed(
    () => user.value?.displayName?.trim() || user.value?.username || '用户',
  )
  const avatarUrl = computed(
    () =>
      user.value?.avatarSmallUrl?.trim() ||
      user.value?.avatarUrl?.trim() ||
      user.value?.avatarLargeUrl?.trim() ||
      undefined,
  )
  let restoring: Promise<void> | null = null
  let loggingOut: Promise<void> | null = null
  let sessionVersion = 0

  function saveUser(value: UserInfo | null) {
    user.value = value
    try {
      if (value) localStorage.setItem(STORAGE_KEY, JSON.stringify(value))
      else localStorage.removeItem(STORAGE_KEY)
    } catch {
      // Login still works when browser storage is disabled or full.
    }
  }

  function clearSession() {
    void disconnectChatClient()
    sessionVersion += 1
    saveUser(null)
    initialized.value = true
  }

  async function refreshUser() {
    if (isLoggingOut.value) return
    if (restoring) return restoring
    const version = sessionVersion
    restoring = (async () => {
      try {
        const response = await me()
        if (version !== sessionVersion) return
        if (response.statusCode !== 200 || !isUser(response.content?.user)) {
          throw new Error(response.message || '无法获取用户信息，请重新登录')
        }
        saveUser(response.content.user)
        initialized.value = true
      } catch (error) {
        if (version !== sessionVersion) return
        saveUser(null)
        if (error instanceof HttpError && (error.status === 401 || error.status === 403)) {
          initialized.value = true
          return
        }
        initialized.value = false
        throw error
      }
    })()
    try {
      await restoring
    } finally {
      restoring = null
    }
  }

  async function restoreSession() {
    if (!initialized.value) await refreshUser()
  }

  async function login(credentials: AuthLoginReq) {
    if (isLoggingOut.value) throw new Error('正在退出登录，请稍后重试')
    const version = sessionVersion
    const response = await loginRequest({ data: credentials })
    if (version !== sessionVersion) throw new Error('登录请求已取消，请重试')
    if (response.statusCode !== 200 || !isUser(response.content?.user)) {
      throw new Error(response.message || '登录失败，请稍后重试')
    }
    saveUser(response.content.user)
    initialized.value = true
  }

  function logout() {
    if (loggingOut) return loggingOut
    isLoggingOut.value = true
    clearSession()
    loggingOut = (async () => {
      try {
        await logoutRequest()
      } finally {
        clearSession()
        isLoggingOut.value = false
        loggingOut = null
      }
    })()
    return loggingOut
  }

  return {
    user,
    isAuthenticated,
    initialized,
    isLoggingOut,
    displayName,
    avatarUrl,
    login,
    logout,
    restoreSession,
    refreshUser,
    clearSession,
  }
})
