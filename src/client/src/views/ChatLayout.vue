<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { NLayout, NLayoutSider, NLayoutContent, NButton, NIcon } from 'naive-ui'
import { ChatbubblesOutline, PersonOutline, VolumeMuteOutline, MenuOutline, CloseOutline } from '@vicons/ionicons5'
import { useConversationsStore } from '@/stores/conversations'

const route = useRoute()
const store = useConversationsStore()
const isCompact = ref(false)
const listOpen = ref(false)
const selectedId = computed(() => String(route.params.id ?? ''))
let media: MediaQueryList | undefined
function updateWidth() {
  isCompact.value = media?.matches ?? false
}
onMounted(() => {
  media = window.matchMedia('(max-width: 700px)')
  updateWidth()
  media.addEventListener('change', updateWidth)
  if (!store.conversations.length) void store.loadMore()
})
onBeforeUnmount(() => media?.removeEventListener('change', updateWidth))
watch(selectedId, () => { listOpen.value = false })
</script>

<template>
  <div class="flex h-dvh min-w-0 flex-col">
    <div v-if="isCompact" class="border-b border-(--n-border-color) px-3 py-1.5">
      <n-button quaternary :aria-expanded="listOpen" aria-controls="conversation-list" @click="listOpen = !listOpen">
        <template #icon><n-icon :component="listOpen ? CloseOutline : MenuOutline" /></template>
        {{ listOpen ? '关闭会话列表' : '会话列表' }}
      </n-button>
    </div>
    <n-layout has-sider class="min-h-0 flex-1!">
      <n-layout-sider
        id="conversation-list" bordered :width="256" :collapsed-width="0"
        :collapsed="isCompact && !listOpen" :native-scrollbar="false"
        :inert="isCompact && !listOpen" class="h-full" :class="{ 'absolute! start-0 inset-y-0 z-3!': isCompact }"
        content-class="flex! h-full flex-col"
        @keydown.esc="listOpen = false"
      >
        <header class="flex items-center gap-3 px-5 pt-5.5 pb-4.5">
          <h1 class="m-0 text-lg leading-[inherit] font-semibold">会话</h1>
          <span v-if="store.total" class="text-xs opacity-65">{{ store.total }}</span>
        </header>
        <nav class="min-h-0 flex-1 overflow-y-auto px-3 pb-5" aria-label="聊天会话" :aria-busy="store.isLoading">
          <router-link
            v-for="conversation in store.conversations" :key="conversation.id"
            :to="{ name: 'chat.conversation', params: { id: conversation.id } }"
            class="mb-1.5 flex min-h-17 items-center gap-3 rounded-xl px-3 py-2.5 text-inherit no-underline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-(--color-primary)"
            :class="selectedId === String(conversation.id)
              ? 'bg-(--color-primary)/16 hover:bg-(--color-primary)/16'
              : 'hover:bg-(--color-primary)/8'"
            :aria-current="selectedId === String(conversation.id) ? 'page' : undefined"
            @click="listOpen = false"
          >
            <span class="grid size-9 shrink-0 place-items-center rounded-[10px] bg-(--color-primary)/10 text-xl">
              <n-icon :component="conversation.type === 'Friend' ? PersonOutline : ChatbubblesOutline" />
            </span>
            <span class="flex min-w-0 flex-1 flex-col gap-0.5">
              <span class="truncate font-medium" :title="conversation.title">{{ conversation.title }}</span>
              <span class="text-xs opacity-65">{{ conversation.type === 'Friend' ? '好友' : conversation.type === 'Chatroom' ? '聊天室' : '会话' }}</span>
            </span>
            <n-icon v-if="conversation.isMuted" :component="VolumeMuteOutline" aria-label="已静音" />
            <span v-if="conversation.hasNewMessage" class="size-1.75 shrink-0 rounded-full bg-(--color-primary)" role="img" aria-label="有未读消息" />
          </router-link>
          <p v-if="store.isEmpty" class="my-4.5 text-center text-[13px]">暂无会话</p>
          <div v-if="store.error" class="my-4.5 text-center text-[13px]" role="alert">
            <p>{{ store.error }}</p><n-button size="small" @click="store.loadMore">重试</n-button>
          </div>
          <div v-else-if="store.hasMore" class="my-4.5 text-center text-[13px]">
            <n-button size="small" :loading="store.isLoading" @click="store.loadMore">
              {{ store.isLoading ? '正在加载…' : '加载更多会话' }}
            </n-button>
          </div>
        </nav>
      </n-layout-sider>
      <button v-if="isCompact && listOpen" type="button" class="absolute inset-0 z-2 border-0 bg-black/30" aria-label="关闭会话列表" @click="listOpen = false" />
      <n-layout-content class="min-w-0 flex-1!" content-class="h-full min-w-0">
        <router-view :key="selectedId" />
      </n-layout-content>
    </n-layout>
  </div>
</template>
