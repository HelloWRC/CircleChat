<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { NAvatar, NButton, NIcon, useMessage } from 'naive-ui'
import { SendOutline } from '@vicons/ionicons5'
import { useChatClient, type ChatClientSubscription } from '@/api/chat.ts'
import type { IChatMessage } from '@/api/chatDto.ts'
import { useUserStore } from '@/stores/user'

defineOptions({ name: 'ChatView' })

interface ChatMessage extends IChatMessage {
  key: number
}

const userStore = useUserStore()
const feedback = useMessage()
const messages = ref<ChatMessage[]>([])
const pending = ref('')
const messageList = ref<HTMLElement | null>(null)
const composer = ref<HTMLTextAreaElement | null>(null)
const isSubscribed = ref(false)
const isNearBottom = ref(true)
let subscription: ChatClientSubscription | undefined
let client: ReturnType<typeof useChatClient> | undefined
let isUnmounted = false
let nextMessageKey = 0

const canSend = computed(() => isSubscribed.value && pending.value.trim().length > 0)
const timeline = computed(() =>
  messages.value.map((message, index, entries) => {
    const date = new Date(message.sendTime)
    const previous = entries[index - 1]
    const previousDate = previous ? new Date(previous.sendTime) : null
    const validDate = !Number.isNaN(date.getTime())
    const showTime =
      validDate &&
      (!previousDate ||
        Number.isNaN(previousDate.getTime()) ||
        date.toDateString() !== previousDate.toDateString() ||
        date.getTime() - previousDate.getTime() >= 5 * 60 * 1000)

    return {
      message,
      isOwn: message.senderId === userStore.user?.id,
      name: message.senderDisplayName?.trim() || message.senderUsername || '用户',
      timeLabel: showTime ? formatTime(date) : '',
      fullTime: validDate ? date.toLocaleString('zh-CN') : '',
    }
  }),
)

function formatTime(date: Date) {
  const today = new Date()
  const time = date.toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
  if (date.toDateString() === today.toDateString()) return time
  const day = date.toLocaleDateString('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    ...(date.getFullYear() !== today.getFullYear() ? { year: 'numeric' } : {}),
  })
  return `${day} ${time}`
}

function trackScroll() {
  const list = messageList.value
  if (list) isNearBottom.value = list.scrollHeight - list.scrollTop - list.clientHeight < 80
}

async function scrollToLatest() {
  await nextTick()
  const list = messageList.value
  if (list) list.scrollTop = list.scrollHeight
}

onMounted(async () => {
  client = useChatClient()
  subscription = await client.subscribe('main', ({ message }) => {
    if (isUnmounted) return
    const shouldScroll = isNearBottom.value || message.senderId === userStore.user?.id
    messages.value.push({ ...message, key: nextMessageKey++ })
    if (shouldScroll) void scrollToLatest()
  })

  if (isUnmounted) subscription.dispose()
  else isSubscribed.value = true
})

onBeforeUnmount(() => {
  isUnmounted = true
  subscription?.dispose()
})

function send() {
  if (!canSend.value || !client) return
  try {
    client.send(pending.value)
    pending.value = ''
    composer.value?.focus()
  } catch {
    feedback.error('消息发送失败，请检查连接后重试')
  }
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault()
    send()
  }
}
</script>

<template>
  <section
    class="chat flex h-dvh min-w-0 flex-col text-neutral-800 dark:text-neutral-100"
    aria-label="主聊天室"
  >
    <header
      class="shrink-0 border-b border-neutral-200 p-4 sm:px-7 sm:py-5 dark:border-neutral-800"
    >
      <h1 class="m-0 text-lg leading-normal font-semibold">主聊天室</h1>
    </header>

    <div
      ref="messageList"
      class="message-list min-h-0 flex-1 overflow-auto overscroll-contain px-3.5 pt-2 pb-5 [scrollbar-width:thin] sm:px-7 sm:pt-3 sm:pb-7"
      @scroll="trackScroll"
    >
      <div
        v-if="!messages.length"
        class="chat-empty flex min-h-full flex-col items-center justify-center text-center text-neutral-500 dark:text-neutral-400"
      >
        <p class="m-0 mb-1.5 text-base">还没有消息</p>
        <span class="text-[13px]">发送第一条消息，开始聊天吧</span>
      </div>

      <ol
        class="message-timeline m-0 flex list-none flex-col gap-5.5 p-0 sm:gap-7"
        aria-label="聊天消息"
        aria-live="polite"
        aria-relevant="additions"
      >
        <li v-for="entry in timeline" :key="entry.message.key">
          <div v-if="entry.timeLabel" class="time-divider mt-4 mb-6 flex justify-center">
            <time
              class="rounded-full bg-neutral-100 px-3 py-1 text-xs leading-5 text-neutral-500 dark:bg-neutral-900 dark:text-neutral-400"
              :datetime="entry.message.sendTime"
              :title="entry.fullTime"
            >
              {{ entry.timeLabel }}
            </time>
          </div>

          <article
            class="message-row flex items-start gap-2 sm:gap-3"
            :class="{ 'message-row--own flex-row-reverse': entry.isOwn }"
          >
            <n-avatar
              round
              :size="40"
              :src="entry.message.senderAvatarUrl || undefined"
              :img-props="{ alt: '' }"
              object-fit="cover"
              class="message-avatar mt-0.5 shrink-0 ring-1 ring-black/10 dark:ring-white/10"
            >
              <template v-if="!entry.message.senderAvatarUrl" #default>
                {{ Array.from(entry.name)[0] }}
              </template>
              <template #fallback>{{ Array.from(entry.name)[0] }}</template>
            </n-avatar>

            <div
              class="flex min-w-0 max-w-[calc(100%_-_52px)] flex-col sm:max-w-[min(72%,640px)]"
              :class="entry.isOwn ? 'items-end' : 'items-start'"
            >
              <div
                class="mb-1.25 flex max-w-full text-[13px] leading-5 text-neutral-500 dark:text-neutral-400"
              >
                <span class="truncate" :title="entry.name">{{ entry.name }}</span>
              </div>
              <div
                class="message-bubble max-w-full rounded-2xl bg-neutral-100 px-3 py-2.25 text-[15px] leading-[1.6] wrap-anywhere whitespace-pre-wrap sm:px-3.5 sm:py-2.5 sm:text-base dark:bg-neutral-700"
                :title="entry.fullTime"
              >
                {{ entry.message.body }}
              </div>
            </div>
          </article>
        </li>
      </ol>
    </div>

    <form
      class="flex shrink-0 flex-col gap-3 border-t border-neutral-200 px-4 pt-3 pb-[max(16px,env(safe-area-inset-bottom))] sm:px-7 sm:pt-4.5 sm:pb-5 dark:border-neutral-800"
      @submit.prevent="send"
    >
      <textarea
        ref="composer"
        v-model="pending"
        class="composer-input block max-h-50 min-h-16 w-full resize-y rounded-lg border-0 bg-transparent p-2 text-[15px] leading-[1.6] text-inherit outline-none placeholder:text-neutral-500 focus:outline-none sm:min-h-22.5 dark:placeholder:text-neutral-400"
        aria-label="消息内容"
        placeholder="输入消息…"
        @keydown="handleKeydown"
      />
      <div class="flex items-center justify-between gap-3">
        <span
          class="composer-hint max-w-37.5 text-xs text-neutral-500 sm:max-w-none dark:text-neutral-400"
          >{{ isSubscribed ? 'Enter 发送 · Shift + Enter 换行' : '正在连接聊天室…' }}</span
        >
        <n-button
          type="primary"
          :disabled="!canSend"
          attr-type="submit"
          class="h-9! min-w-22 rounded-[10px]! text-neutral-950!"
        >
          <template #icon>
            <n-icon :component="SendOutline" />
          </template>
          发送
        </n-button>
      </div>
    </form>
  </section>
</template>
