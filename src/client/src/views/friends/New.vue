<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
  type HTMLAttributes,
} from 'vue'
import { useRouter } from 'vue-router'
import {
  NAvatar,
  NButton,
  NButtonGroup,
  NDropdown,
  NEmpty,
  NIcon,
  NInput,
  NSpin,
  NTabPane,
  NTabs,
  NTag,
  useMessage,
  useThemeVars,
} from 'naive-ui'
import {
  ChevronDownOutline,
  PersonAddOutline,
  RefreshOutline,
  SearchOutline,
} from '@vicons/ionicons5'
import { HttpError } from '@/api/instance'
import {
  useFriendsStore,
  type FriendRequest,
  type RequestAction,
  type RequestDirection,
} from '@/stores/friends'
import { useUserStore } from '@/stores/user'

defineOptions({ name: 'NewFriends' })
const store = useFriendsStore()
const user = useUserStore()
const router = useRouter()
const message = useMessage()
const themeVars = useThemeVars()
const activeTab = ref<RequestDirection>('received')
const search = ref('')
const isSearching = ref(false)
const searchError = ref<string | null>(null)
const page = computed(() => store.requestPages[activeTab.value])
const sectionElement = ref<HTMLElement | null>(null)
const tabs: { name: RequestDirection; label: string }[] = [
  { name: 'received', label: '收到的请求' },
  { name: 'sent', label: '发出的请求' },
]
const openMenus = ref(new Set<number>())
const rejectOptions = [{ label: '拒绝', key: 'reject' }]
let viewVersion = 0

function otherUsername(info: FriendRequest) {
  return activeTab.value === 'received' ? info.senderUsername : info.targetUsername
}

function otherName(info: FriendRequest) {
  return (
    (activeTab.value === 'received' ? info.senderDisplayName : info.targetDisplayName)?.trim() ||
    otherUsername(info)
  )
}

function otherAvatar(info: FriendRequest) {
  return (
    (activeTab.value === 'received' ? info.senderAvatarUrl : info.targetAvatarUrl)?.trim() ||
    undefined
  )
}

function requestStatus(info: FriendRequest) {
  if (info.state === 'Open' || (activeTab.value === 'sent' && info.state === 'Ignored'))
    return '待接收'
  return { Accepted: '已接受', Rejected: '已拒绝', Ignored: '已忽略' }[info.state]
}

function requestTime(value?: string) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { hour12: false })
}

function loadRequests(refresh = true) {
  return store.loadRequests(activeTab.value, refresh)
}

async function findUser() {
  if (isSearching.value || !user.isAuthenticated) return
  const username = search.value.trim()
  if (!username || username.length > 32) {
    searchError.value = '请输入不超过 32 个字符的用户名'
    return
  }
  const version = viewVersion
  const location = router.currentRoute.value.fullPath
  isSearching.value = true
  searchError.value = null
  try {
    const profile = await store.searchUser(username)
    if (version !== viewVersion || router.currentRoute.value.fullPath !== location || !profile)
      return
    await router.push({ name: 'friends.details', params: { username: profile.username } })
  } catch (cause) {
    if (version !== viewVersion || router.currentRoute.value.fullPath !== location) return
    searchError.value =
      cause instanceof HttpError && cause.status === 404
        ? '用户不存在或已被删除'
        : '查找用户失败，请重试'
  } finally {
    if (version === viewVersion) isSearching.value = false
  }
}

async function handleRequest(id: number, action: RequestAction) {
  const version = viewVersion
  const location = router.currentRoute.value.fullPath
  openMenus.value.delete(id)
  const success = await store.processRequest(id, action)
  if (version === viewVersion && router.currentRoute.value.fullPath === location) {
    if (success) {
      message.success(
        { accept: '已接受好友申请', ignore: '已忽略好友申请', reject: '已拒绝好友申请' }[action],
      )
    } else {
      const error = store.requestActionErrors.get(id)
      if (error) message.error(error)
    }
  }
}

function updateMenu(id: number, show: boolean) {
  if (show) openMenus.value.add(id)
  else openMenus.value.delete(id)
}

function refreshAccepted(username: string) {
  void store.loadFriends()
  void store.loadDetails(username)
}

function tabProps(direction: RequestDirection): HTMLAttributes {
  return {
    id: `friend-requests-tab-${direction}`,
    role: 'tab',
    tabindex: activeTab.value === direction ? 0 : -1,
    'aria-selected': activeTab.value === direction,
    'aria-controls': `friend-requests-panel-${direction}`,
    class:
      'focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-(--color-primary)',
    onKeydown: (event: KeyboardEvent) => {
      let next = direction
      if (event.key === 'ArrowRight' || event.key === 'ArrowLeft')
        next = direction === 'received' ? 'sent' : 'received'
      else if (event.key === 'Home') next = 'received'
      else if (event.key === 'End') next = 'sent'
      else if (event.key !== 'Enter' && event.key !== ' ') return
      event.preventDefault()
      activeTab.value = next
      void nextTick(() =>
        sectionElement.value?.querySelector<HTMLElement>(`#friend-requests-tab-${next}`)?.focus(),
      )
    },
  }
}

watch(activeTab, () => {
  openMenus.value.clear()
  void loadRequests()
})
watch(
  [() => user.user?.id, () => user.isAuthenticated],
  () => {
    viewVersion++
    search.value = ''
    searchError.value = null
    isSearching.value = false
    openMenus.value.clear()
    activeTab.value = 'received'
    void loadRequests()
  },
  { flush: 'sync' },
)
onMounted(() => {
  // Naive UI exposes tabProps, but its tab navigation has no role of its own.
  const navigation = sectionElement.value?.querySelector('.n-tabs-nav')
  navigation?.setAttribute('role', 'tablist')
  navigation?.setAttribute('aria-label', '好友申请')
  void loadRequests()
})
onBeforeUnmount(() => viewVersion++)
</script>

<template>
  <section
    ref="sectionElement"
    class="h-full overflow-y-auto"
    aria-label="新好友"
    :style="{
      '--friend-border-color': themeVars.borderColor,
      '--friend-error-color': themeVars.errorColor,
    }"
  >
    <div class="mx-auto w-full max-w-190 px-6 pt-10 pb-10 sm:px-10 sm:pt-16">
      <header class="mb-8">
        <h1 class="m-0 text-xl font-semibold sm:text-xl mb-6">新好友</h1>
        <form class="flex items-end gap-3" @submit.prevent="findUser">
          <div class="min-w-0 flex-1">
            <label for="friend-search" class="mb-2 block text-sm">用户名</label>
            <n-input
              v-model:value="search"
              :input-props="{
                id: 'friend-search',
                'aria-describedby': searchError ? 'friend-search-error' : undefined,
              }"
              placeholder="输入用户名，精确查找用户"
              :status="searchError ? 'error' : undefined"
              :disabled="isSearching"
              size="large"
              clearable
            >
              <template #prefix><n-icon :component="SearchOutline" aria-hidden="true" /></template>
            </n-input>
          </div>
          <n-button
            attr-type="submit"
            type="primary"
            size="large"
            :loading="isSearching"
            :disabled="isSearching"
            class="transition-transform! duration-150! ease-[ease-out]! motion-safe:enabled:active:scale-[0.96] motion-reduce:transition-none!"
            >查找</n-button
          >
        </form>
        <p
          v-if="searchError"
          id="friend-search-error"
          class="mb-0 text-sm text-(--friend-error-color)"
          role="alert"
        >
          {{ searchError }}
        </p>
      </header>

      <n-tabs v-model:value="activeTab" type="line" :animated="false">
        <n-tab-pane
          v-for="tab in tabs"
          :key="tab.name"
          :name="tab.name"
          :tab="tab.label"
          :tab-props="tabProps(tab.name)"
          :id="`friend-requests-panel-${tab.name}`"
          role="tabpanel"
          :aria-labelledby="`friend-requests-tab-${tab.name}`"
          display-directive="if"
        >
          <div class="mt-4 flex justify-end">
            <n-button
              quaternary
              size="small"
              :loading="page.isLoading"
              :disabled="page.isLoading"
              @click="loadRequests()"
            >
              <template #icon><n-icon :component="RefreshOutline" /></template>
              刷新请求
            </n-button>
          </div>
          <div
            :aria-busy="page.isLoading"
            :aria-label="activeTab === 'received' ? '收到的请求列表' : '发出的请求列表'"
          >
            <div v-if="page.error" class="my-4 text-sm text-(--friend-error-color)" role="alert">
              <p>{{ page.error }}</p>
              <n-button
                size="small"
                :disabled="page.isLoading"
                @click="loadRequests(page.retryRefresh)"
                >重试</n-button
              >
            </div>
            <div
              v-if="!page.hasLoaded && page.isLoading && !page.requests.length"
              class="flex justify-center gap-3 py-16"
              role="status"
            >
              <n-spin size="small" /><span class="text-sm opacity-65">正在加载好友申请…</span>
            </div>
            <n-empty
              v-else-if="page.hasLoaded && !page.error && !page.requests.length"
              class="py-16"
              :description="activeTab === 'received' ? '暂无收到的好友申请' : '暂无发出的好友申请'"
            >
              <template #icon><n-icon :component="PersonAddOutline" /></template>
            </n-empty>
            <ul
              v-if="page.requests.length"
              class="m-0 list-none divide-y divide-(--friend-border-color) p-0"
            >
              <li v-for="info in page.requests" :key="info.id" class="py-5">
                <div class="flex flex-wrap items-center gap-x-4 gap-y-4">
                  <router-link
                    :to="{ name: 'friends.details', params: { username: otherUsername(info) } }"
                    :aria-label="`查看 ${otherName(info)} 的资料`"
                    class="shrink-0 rounded-full focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--color-primary)"
                  >
                    <n-avatar
                      round
                      :size="44"
                      :src="otherAvatar(info)"
                      object-fit="cover"
                      :img-props="{ alt: '' }"
                      class="ring-1 ring-black/10 dark:ring-white/10"
                    >
                      <template v-if="!otherAvatar(info)" #default>{{
                        Array.from(otherName(info))[0]?.toUpperCase()
                      }}</template>
                      <template #fallback>{{
                        Array.from(otherName(info))[0]?.toUpperCase()
                      }}</template>
                    </n-avatar>
                  </router-link>
                  <div class="min-w-0 flex-1 basis-32">
                    <router-link
                      :to="{ name: 'friends.details', params: { username: otherUsername(info) } }"
                      class="font-medium text-inherit no-underline wrap-anywhere hover:text-(--color-primary) focus-visible:outline-2 focus-visible:outline-(--color-primary)"
                      >{{ otherName(info) }}</router-link
                    >
                    <p class="mt-1 mb-0 text-xs wrap-anywhere opacity-65">
                      @{{ otherUsername(info) }}
                    </p>
                  </div>
                  <div
                    v-if="activeTab === 'received' && info.state === 'Open'"
                    class="flex items-center gap-2"
                  >
                    <n-button
                      type="primary"
                      :loading="store.processingRequests.get(info.id) === 'accept'"
                      :disabled="store.processingRequests.has(info.id)"
                      class="transition-transform! duration-150! ease-[ease-out]! motion-safe:enabled:active:scale-[0.96] motion-reduce:transition-none!"
                      @click="handleRequest(info.id, 'accept')"
                      >接受</n-button
                    >
                    <n-button-group>
                      <n-button
                        :loading="store.processingRequests.get(info.id) === 'ignore'"
                        :disabled="store.processingRequests.has(info.id)"
                        @click="handleRequest(info.id, 'ignore')"
                        >忽略</n-button
                      >
                      <n-dropdown
                        trigger="click"
                        placement="bottom-end"
                        :options="rejectOptions"
                        :show="openMenus.has(info.id) && !store.processingRequests.has(info.id)"
                        @update:show="updateMenu(info.id, $event)"
                        @select="handleRequest(info.id, 'reject')"
                      >
                        <n-button
                          :aria-label="`对 ${otherName(info)} 的申请执行更多操作`"
                          aria-haspopup="menu"
                          :aria-expanded="openMenus.has(info.id)"
                          :loading="store.processingRequests.get(info.id) === 'reject'"
                          :disabled="store.processingRequests.has(info.id)"
                        >
                          <template #icon><n-icon :component="ChevronDownOutline" /></template>
                        </n-button>
                      </n-dropdown>
                    </n-button-group>
                  </div>
                  <n-tag
                    v-else
                    :bordered="false"
                    round
                    :type="info.state === 'Accepted' ? 'success' : 'default'"
                    >{{ requestStatus(info) }}</n-tag
                  >
                </div>
                <div class="mt-3 sm:ps-15">
                  <p
                    v-if="info.note?.trim()"
                    class="mt-0 mb-2 text-sm whitespace-pre-wrap wrap-anywhere"
                  >
                    {{ info.note }}
                  </p>
                  <time
                    v-if="requestTime(info.createdAt)"
                    :datetime="info.createdAt"
                    class="text-xs opacity-55"
                    >{{ requestTime(info.createdAt) }}</time
                  >
                  <p
                    v-if="store.requestActionErrors.get(info.id)"
                    class="mb-0 text-sm text-(--friend-error-color)"
                    role="alert"
                  >
                    {{ store.requestActionErrors.get(info.id) }}
                  </p>
                  <p
                    v-if="
                      info.state === 'Accepted' &&
                      activeTab === 'received' &&
                      (store.error || store.detailErrors.get(info.senderUsername))
                    "
                    class="mb-0 text-sm"
                    role="alert"
                  >
                    申请已接受，好友资料刷新失败。
                    <n-button
                      text
                      type="primary"
                      size="small"
                      @click="refreshAccepted(info.senderUsername)"
                      >重试刷新</n-button
                    >
                  </p>
                </div>
              </li>
            </ul>
            <div v-if="page.hasLoaded && page.hasMore" class="mt-6 flex justify-center">
              <n-button
                :loading="page.isLoading"
                :disabled="page.isLoading"
                @click="loadRequests(false)"
                >加载更多</n-button
              >
            </div>
          </div>
        </n-tab-pane>
      </n-tabs>
    </div>
  </section>
</template>
