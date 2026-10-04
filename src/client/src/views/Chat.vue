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
  <section class="chat" aria-label="主聊天室">
    <header class="chat-header">
      <h1>主聊天室</h1>
    </header>

    <div ref="messageList" class="message-list" @scroll="trackScroll">
      <div v-if="!messages.length" class="chat-empty">
        <p>还没有消息</p>
        <span>发送第一条消息，开始聊天吧</span>
      </div>

      <ol
        class="message-timeline"
        aria-label="聊天消息"
        aria-live="polite"
        aria-relevant="additions"
      >
        <li v-for="entry in timeline" :key="entry.message.key" class="message-item">
          <div v-if="entry.timeLabel" class="time-divider">
            <time :datetime="entry.message.sendTime" :title="entry.fullTime">
              {{ entry.timeLabel }}
            </time>
          </div>

          <article class="message-row" :class="{ 'message-row--own': entry.isOwn }">
            <n-avatar
              round
              :size="40"
              :src="entry.message.senderAvatarUrl || undefined"
              :img-props="{ alt: '' }"
              object-fit="cover"
              class="message-avatar"
            >
              <template v-if="!entry.message.senderAvatarUrl" #default>
                {{ Array.from(entry.name)[0] }}
              </template>
              <template #fallback>{{ Array.from(entry.name)[0] }}</template>
            </n-avatar>

            <div class="message-content">
              <div class="message-meta">
                <span class="message-name" :title="entry.name">{{ entry.name }}</span>
              </div>
              <div class="message-bubble" :title="entry.fullTime">{{ entry.message.body }}</div>
            </div>
          </article>
        </li>
      </ol>
    </div>

    <form class="chat-composer" @submit.prevent="send">
      <textarea
        ref="composer"
        v-model="pending"
        class="composer-input"
        aria-label="消息内容"
        placeholder="输入消息…"
        @keydown="handleKeydown"
      />
      <div class="composer-footer">
        <span class="composer-hint">{{
          isSubscribed ? 'Enter 发送 · Shift + Enter 换行' : '正在连接聊天室…'
        }}</span>
        <n-button type="primary" :disabled="!canSend" attr-type="submit" class="send-button">
          <template #icon>
            <n-icon :component="SendOutline" />
          </template>
          发送
        </n-button>
      </div>
    </form>
  </section>
</template>

<style scoped>
.chat {
  --chat-background: #f5f5f5;
  --chat-bubble: #fff;
  --chat-text: #242424;
  --chat-muted: #757575;
  --chat-divider: #e5e5e5;
  --chat-time-background: #e9e9e9;
  --chat-avatar-outline: oklch(0 0 0 / 0.1);

  display: flex;
  flex-direction: column;
  height: 100vh;
  height: 100dvh;
  min-width: 0;
  color: var(--chat-text);
  background: var(--chat-background);
}

.chat-header {
  flex-shrink: 0;
  padding: 20px 28px;
  border-bottom: 1px solid var(--chat-divider);
}

.chat-header h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  line-height: 1.5;
}

.message-list {
  flex: 1;
  min-height: 0;
  padding: 12px 28px 28px;
  overflow: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
  scrollbar-color: var(--chat-divider) transparent;
}

.message-timeline {
  display: flex;
  flex-direction: column;
  gap: 28px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.time-divider {
  display: flex;
  justify-content: center;
  margin: 16px 0 24px;
}

.time-divider time {
  padding: 4px 12px;
  font-size: 12px;
  line-height: 20px;
  color: var(--chat-muted);
  background: var(--chat-time-background);
  border-radius: 999px;
}

.message-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.message-row--own {
  flex-direction: row-reverse;
}

.message-avatar {
  flex-shrink: 0;
  margin-top: 2px;
  box-shadow: 0 0 0 1px var(--chat-avatar-outline);
}

.message-content {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  min-width: 0;
  max-width: min(72%, 640px);
}

.message-row--own .message-content {
  align-items: flex-end;
}

.message-meta {
  display: flex;
  max-width: 100%;
  margin-bottom: 5px;
  font-size: 13px;
  line-height: 20px;
  color: var(--chat-muted);
}

.message-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.message-bubble {
  max-width: 100%;
  padding: 10px 14px;
  font-size: 16px;
  line-height: 1.6;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  background: var(--chat-bubble);
  border-radius: 16px;
}

.chat-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  color: var(--chat-muted);
  text-align: center;
}

.chat-empty p {
  margin: 0 0 6px;
  font-size: 16px;
}

.chat-empty span {
  font-size: 13px;
}

.chat-composer {
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  gap: 12px;
  padding: 18px 28px 20px;
  border-top: 1px solid var(--chat-divider);
}

.composer-input {
  display: block;
  width: 100%;
  min-height: 90px;
  max-height: 200px;
  padding: 8px;
  font: inherit;
  font-size: 15px;
  line-height: 1.6;
  color: var(--chat-text);
  resize: vertical;
  background: transparent;
  border: 0;
  border-radius: 8px;
}

.composer-input::placeholder {
  color: var(--chat-muted);
}

.composer-input:focus {
  outline: none;
}

.composer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.composer-hint {
  font-size: 12px;
  color: var(--chat-muted);
}

.send-button {
  min-width: 88px;
  height: 36px;
  border-radius: 10px;
}

@media (prefers-color-scheme: dark) {
  .chat {
    --chat-background: #222;
    --chat-bubble: #3b3b3b;
    --chat-text: #f5f5f5;
    --chat-muted: #929292;
    --chat-divider: #303030;
    --chat-time-background: #191919;
    --chat-avatar-outline: oklch(1 0 0 / 0.1);
  }
}

@media (max-width: 640px) {
  .chat-header {
    padding: 16px;
  }

  .message-list {
    padding: 8px 14px 20px;
  }

  .message-timeline {
    gap: 22px;
  }

  .message-row {
    gap: 8px;
  }

  .message-content {
    max-width: calc(100% - 52px);
  }

  .message-bubble {
    padding: 9px 12px;
    font-size: 15px;
  }

  .chat-composer {
    padding: 12px 16px max(16px, env(safe-area-inset-bottom));
  }

  .composer-input {
    min-height: 64px;
  }

  .composer-hint {
    max-width: 150px;
  }
}
</style>
