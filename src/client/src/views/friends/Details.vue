<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  NAvatar,
  NButton,
  NIcon,
  NInput,
  NModal,
  NSpin,
  NTag,
  useMessage,
  useThemeVars,
} from 'naive-ui'
import {
  AtOutline,
  ChatbubbleOutline,
  IdCardOutline,
  PeopleOutline,
  PersonAddOutline,
  PersonOutline,
  RefreshOutline,
  TrashOutline,
} from '@vicons/ionicons5'
import { HttpError } from '@/api/instance'
import { useFriendsStore } from '@/stores/friends'
import { useUserStore } from '@/stores/user'

defineOptions({ name: 'FriendDetails' })
const props = defineProps<{ username: string }>()
const user = useUserStore()
const store = useFriendsStore()
const router = useRouter()
const message = useMessage()
const themeVars = useThemeVars()
const isOpeningChat = ref(false)
const isDeleting = ref(false)
const showDeleteConfirm = ref(false)
const showAddDialog = ref(false)
const isSending = ref(false)
const requestNote = ref('')
const actionError = ref<string | null>(null)
const isActing = computed(() => isOpeningChat.value || isDeleting.value || isSending.value)
let actionVersion = 0
const friend = computed(() => store.profiles.get(props.username) ?? null)
const isLoading = computed(() => store.loadingDetails.has(props.username))
const error = computed(() => store.detailErrors.get(props.username) ?? null)
const displayName = computed(() => friend.value?.displayName?.trim() || props.username)
const avatarUrl = computed(() => friend.value?.avatarUrl?.trim() || undefined)
const avatarInitial = computed(() => Array.from(displayName.value)[0]?.toUpperCase() || '用')
const canAddFriend = computed(
  () => !!friend.value && !friend.value.isFriend && friend.value.username !== user.user?.username,
)
function loadDetails() {
  return store.loadDetails(props.username)
}

async function openChat() {
  if (!friend.value?.isFriend || isActing.value || showDeleteConfirm.value) return
  const version = actionVersion
  const location = router.currentRoute.value.fullPath
  const username = props.username
  isOpeningChat.value = true
  actionError.value = null
  try {
    const id = await store.getConversationId(username)
    if (
      version !== actionVersion ||
      router.currentRoute.value.fullPath !== location ||
      id === null
    ) {
      return
    }
    await router.push({ name: 'chat.conversation', params: { id } })
  } catch (cause) {
    if (version !== actionVersion) return
    actionError.value =
      cause instanceof HttpError && cause.status === 404
        ? '好友会话不存在或已无权访问，请刷新资料'
        : '打开会话失败，请重试'
  } finally {
    if (version === actionVersion) isOpeningChat.value = false
  }
}

function requestDelete() {
  if (!friend.value?.isFriend || isActing.value) return
  actionError.value = null
  showDeleteConfirm.value = true
}

function requestAdd() {
  if (!canAddFriend.value || isActing.value) return
  requestNote.value = ''
  actionError.value = null
  showAddDialog.value = true
}

function cancelAdd() {
  if (isSending.value) return
  showAddDialog.value = false
  actionError.value = null
}

async function confirmAdd() {
  if (!showAddDialog.value || !canAddFriend.value || isActing.value) return false
  if (requestNote.value.length > 255) {
    actionError.value = '留言不能超过 255 个字符'
    return false
  }
  const version = actionVersion
  const location = router.currentRoute.value.fullPath
  const username = props.username
  isSending.value = true
  actionError.value = null
  try {
    const sent = await store.sendRequest(username, requestNote.value)
    if (version !== actionVersion || router.currentRoute.value.fullPath !== location || !sent)
      return false
    showAddDialog.value = false
    requestNote.value = ''
    message.success('好友申请已发送')
  } catch (cause) {
    if (version !== actionVersion || router.currentRoute.value.fullPath !== location) return false
    actionError.value =
      cause instanceof HttpError && cause.status === 409
        ? '已存在待处理申请或已成为好友，请刷新资料或前往“新好友”查看收到的请求'
        : cause instanceof HttpError && cause.status === 404
          ? '用户不存在或已被删除，请刷新资料'
          : '发送好友申请失败，请重试'
  } finally {
    if (version === actionVersion) isSending.value = false
  }
  return false
}

function cancelDelete() {
  if (isDeleting.value) return
  showDeleteConfirm.value = false
  actionError.value = null
}

async function confirmDelete() {
  if (!showDeleteConfirm.value || !friend.value?.isFriend || isActing.value) return false
  const version = actionVersion
  const location = router.currentRoute.value.fullPath
  const username = props.username
  isDeleting.value = true
  actionError.value = null
  try {
    const removed = await store.removeFriend(username)
    if (version !== actionVersion || !removed) return false
    showDeleteConfirm.value = false
    message.success('已删除好友')
    if (router.currentRoute.value.fullPath === location) {
      await router.replace({ name: 'friends' })
    }
  } catch (cause) {
    if (version !== actionVersion) return false
    actionError.value =
      cause instanceof HttpError && cause.status === 404
        ? '好友关系不存在，请刷新资料'
        : '删除好友失败，请重试'
  } finally {
    if (version === actionVersion) isDeleting.value = false
  }
  return false
}

watch(
  [() => props.username, () => user.user?.id, () => user.isAuthenticated],
  () => {
    actionVersion++
    isOpeningChat.value = false
    isDeleting.value = false
    showDeleteConfirm.value = false
    showAddDialog.value = false
    isSending.value = false
    requestNote.value = ''
    actionError.value = null
    void loadDetails()
  },
  { flush: 'sync' },
)

onMounted(() => void loadDetails())
onBeforeUnmount(() => actionVersion++)
</script>

<template>
  <section
    class="h-full overflow-y-auto"
    :style="{ '--friend-border-color': themeVars.borderColor }"
    aria-label="好友详细资料"
    :aria-busy="isLoading"
  >
    <div v-if="!friend" class="grid min-h-full place-items-center px-6 py-12">
      <div v-if="error" class="text-center" role="alert">
        <p class="mb-4">{{ error }}</p>
        <n-button @click="loadDetails">重试</n-button>
      </div>
      <div v-else class="flex flex-col items-center gap-4" role="status">
        <n-spin size="medium" />
        <span class="text-sm opacity-65">正在加载好友资料…</span>
      </div>
    </div>
    <article v-else class="mx-auto w-full max-w-190 px-6 pt-12 pb-10 sm:px-10 sm:pt-24">
      <header
        class="flex flex-wrap items-center gap-5 border-b border-(--friend-border-color) pb-8 sm:gap-7"
      >
        <n-avatar
          round
          :size="112"
          :src="avatarUrl"
          object-fit="cover"
          :img-props="{ alt: '' }"
          class="shrink-0 ring-1 ring-black/10 dark:ring-white/10"
        >
          <template v-if="!avatarUrl" #default>{{ avatarInitial }}</template>
          <template #fallback>{{ avatarInitial }}</template>
        </n-avatar>
        <div class="min-w-0 flex-1 basis-36">
          <h1 class="m-0 text-2xl leading-snug font-semibold wrap-anywhere sm:text-3xl">
            {{ displayName }}
          </h1>
          <p class="mt-2 mb-0 text-sm wrap-anywhere opacity-65">@{{ friend.username }}</p>
        </div>
        <n-tag :type="friend.isFriend ? 'success' : 'default'" :bordered="false" round>
          {{ friend.isFriend ? '好友' : '非好友' }}
        </n-tag>
      </header>

      <dl class="my-0 divide-y divide-(--friend-border-color)">
        <div class="flex flex-wrap items-start gap-x-6 gap-y-2 py-5">
          <dt class="flex w-28 shrink-0 items-center gap-3 opacity-65">
            <n-icon :component="PersonOutline" :size="20" aria-hidden="true" />
            显示名称
          </dt>
          <dd class="m-0 min-w-0 flex-1 basis-40 wrap-anywhere">{{ displayName }}</dd>
        </div>
        <div class="flex flex-wrap items-start gap-x-6 gap-y-2 py-5">
          <dt class="flex w-28 shrink-0 items-center gap-3 opacity-65">
            <n-icon :component="AtOutline" :size="20" aria-hidden="true" />
            用户名
          </dt>
          <dd class="m-0 min-w-0 flex-1 basis-40 wrap-anywhere">{{ friend.username }}</dd>
        </div>
        <div class="flex flex-wrap items-start gap-x-6 gap-y-2 py-5">
          <dt class="flex w-28 shrink-0 items-center gap-3 opacity-65">
            <n-icon :component="IdCardOutline" :size="20" aria-hidden="true" />
            用户编号
          </dt>
          <dd class="m-0 min-w-0 flex-1 basis-40 tabular-nums">{{ friend.userId }}</dd>
        </div>
        <div class="flex flex-wrap items-start gap-x-6 gap-y-2 py-5">
          <dt class="flex w-28 shrink-0 items-center gap-3 opacity-65">
            <n-icon :component="PeopleOutline" :size="20" aria-hidden="true" />
            好友关系
          </dt>
          <dd class="m-0 min-w-0 flex-1 basis-40">
            {{ friend.isFriend ? '已添加为好友' : '尚未添加为好友' }}
          </dd>
        </div>
      </dl>

      <div v-if="error" class="mt-6 text-sm" role="alert">{{ error }}</div>
      <p
        v-if="actionError && !showDeleteConfirm && !showAddDialog"
        class="mt-6 text-center text-sm text-(--friend-error-color)"
        :style="{ '--friend-error-color': themeVars.errorColor }"
        role="alert"
      >
        {{ actionError }}
      </p>
      <footer class="mt-10 flex flex-col items-center gap-5">
        <div v-if="friend.isFriend" class="flex w-full max-w-104 gap-3 sm:gap-4">
          <n-button
            size="large"
            type="error"
            secondary
            class="min-w-0 flex-1!"
            :disabled="isActing"
            @click="requestDelete"
          >
            <template #icon><n-icon :component="TrashOutline" /></template>
            删除好友
          </n-button>
          <n-button
            size="large"
            type="primary"
            class="min-w-0 flex-1!"
            :loading="isOpeningChat"
            :disabled="isActing"
            @click="openChat"
          >
            <template #icon><n-icon :component="ChatbubbleOutline" /></template>
            {{ isOpeningChat ? '正在打开…' : '发消息' }}
          </n-button>
        </div>
        <n-button
          v-else-if="canAddFriend"
          size="large"
          type="primary"
          class="w-full! max-w-104 transition-transform! duration-150! ease-[ease-out]! motion-safe:enabled:active:scale-[0.96] motion-reduce:transition-none!"
          :disabled="isActing"
          @click="requestAdd"
        >
          <template #icon><n-icon :component="PersonAddOutline" /></template>
          添加好友
        </n-button>
        <n-button
          quaternary
          size="small"
          :loading="isLoading"
          :disabled="isActing"
          @click="loadDetails"
        >
          <template #icon><n-icon :component="RefreshOutline" /></template>
          {{ error ? '重试加载' : '刷新资料' }}
        </n-button>
      </footer>
    </article>
    <n-modal
      v-model:show="showAddDialog"
      preset="dialog"
      type="info"
      title="添加好友"
      positive-text="提交申请"
      negative-text="取消"
      :loading="isSending"
      :positive-button-props="{ type: 'primary', disabled: isSending || !canAddFriend }"
      :negative-button-props="{ disabled: isSending }"
      :closable="!isSending"
      :mask-closable="!isSending"
      :close-on-esc="!isSending"
      :on-positive-click="confirmAdd"
      :on-negative-click="cancelAdd"
      :on-close="cancelAdd"
      class="max-w-[calc(100vw-2rem)]!"
    >
      <p class="mt-0 wrap-anywhere">向「{{ displayName }}」发送好友申请</p>
      <label for="friend-request-note" class="mb-2 block text-sm">留言（可选）</label>
      <n-input
        v-model:value="requestNote"
        type="textarea"
        :input-props="{
          id: 'friend-request-note',
          'aria-describedby': actionError ? 'friend-request-error' : undefined,
        }"
        placeholder="介绍一下自己吧"
        :maxlength="255"
        :autosize="{ minRows: 3, maxRows: 6 }"
        :disabled="isSending"
        show-count
      />
      <p v-if="actionError" id="friend-request-error" class="mb-0 text-sm" role="alert">
        {{ actionError }}
      </p>
    </n-modal>
    <n-modal
      v-model:show="showDeleteConfirm"
      preset="dialog"
      type="error"
      title="删除好友"
      positive-text="删除好友"
      negative-text="取消"
      :loading="isDeleting"
      :positive-button-props="{ type: 'error', disabled: isDeleting }"
      :negative-button-props="{ disabled: isDeleting }"
      :closable="!isDeleting"
      :mask-closable="!isDeleting"
      :close-on-esc="!isDeleting"
      :on-positive-click="confirmDelete"
      :on-negative-click="cancelDelete"
      :on-close="cancelDelete"
      class="max-w-[calc(100vw-2rem)]!"
    >
      <p class="mt-0 wrap-anywhere">确定删除好友「{{ displayName }}」吗？</p>
      <p class="mb-0 text-sm opacity-65">删除后，双方将无法继续在此会话中聊天或查看历史消息。</p>
      <p v-if="actionError" class="mb-0 text-sm" role="alert">{{ actionError }}</p>
    </n-modal>
  </section>
</template>
