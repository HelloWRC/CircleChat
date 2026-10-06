<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRoute } from 'vue-router'
import { NAvatar, NButton, NEmpty, NIcon, NLayout, NLayoutContent, NLayoutSider } from 'naive-ui'
import { CloseOutline, MenuOutline, PersonAddOutline, PersonOutline } from '@vicons/ionicons5'
import type { FriendInfo } from '@/api'
import { useFriendsStore } from '@/stores/friends'
import { useUserStore } from '@/stores/user'

defineOptions({ name: 'FriendsList' })

const user = useUserStore()
const store = useFriendsStore()
const { friends, isLoading, hasLoaded, error } = storeToRefs(store)
const { loadFriends } = store
const route = useRoute()
const isCompact = ref(false)
const listOpen = ref(false)
const selectedUsername = computed(() => String(route.params.username ?? ''))
let media: MediaQueryList | undefined

function updateWidth() {
  isCompact.value = media?.matches ?? false
}

function friendName(friend: FriendInfo) {
  return friend.displayName?.trim() || friend.username || '用户'
}

watch([() => user.user?.id, () => user.isAuthenticated], () => {
  void loadFriends()
})

watch(
  () => route.fullPath,
  () => {
    listOpen.value = false
  },
)

onMounted(() => {
  media = window.matchMedia('(max-width: 700px)')
  updateWidth()
  media.addEventListener('change', updateWidth)
  void loadFriends()
})
onBeforeUnmount(() => {
  media?.removeEventListener('change', updateWidth)
})
</script>

<template>
  <div class="flex h-dvh min-w-0 flex-col">
    <div v-if="isCompact" class="border-b border-(--n-border-color) px-3 py-1.5">
      <n-button
        quaternary
        :aria-expanded="listOpen"
        aria-controls="friend-list"
        @click="listOpen = !listOpen"
      >
        <template #icon><n-icon :component="listOpen ? CloseOutline : MenuOutline" /></template>
        {{ listOpen ? '关闭好友列表' : '好友列表' }}
      </n-button>
    </div>
    <n-layout has-sider class="min-h-0 flex-1!">
      <n-layout-sider
        id="friend-list"
        bordered
        :width="256"
        :collapsed-width="0"
        :collapsed="isCompact && !listOpen"
        :native-scrollbar="false"
        :inert="isCompact && !listOpen"
        class="h-full"
        :class="{ 'absolute! start-0 inset-y-0 z-3!': isCompact }"
        content-class="flex! h-full flex-col"
        @keydown.esc="listOpen = false"
      >
        <header class="flex items-center gap-3 px-5 pt-5.5 pb-4.5">
          <h1 class="m-0 text-lg leading-[inherit] font-semibold">好友</h1>
          <span v-if="hasLoaded" class="text-xs opacity-65">{{ friends.length }}</span>
        </header>
        <nav
          class="min-h-0 flex-1 overflow-y-auto px-3 pb-5"
          aria-label="好友列表"
          :aria-busy="isLoading"
        >
          <router-link
            :to="{ name: 'friends.new' }"
            class="mb-3 flex min-h-14 items-center gap-3 rounded-xl px-3 py-2.5 text-inherit no-underline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-(--color-primary)"
            :class="
              route.name === 'friends.new'
                ? 'bg-(--color-primary)/16'
                : 'hover:bg-(--color-primary)/8'
            "
            :aria-current="route.name === 'friends.new' ? 'page' : undefined"
            @click="listOpen = false"
          >
            <span
              class="grid size-9 shrink-0 place-items-center rounded-full bg-(--color-primary)/10 text-(--color-primary)"
            >
              <n-icon :component="PersonAddOutline" :size="20" />
            </span>
            <span class="font-medium">新好友</span>
          </router-link>
          <router-link
            v-for="friend in friends"
            :key="friend.userId"
            :to="{ name: 'friends.details', params: { username: friend.username } }"
            class="mb-1.5 flex min-h-17 items-center gap-3 rounded-xl px-3 py-2.5 text-inherit no-underline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-(--color-primary)"
            :class="
              selectedUsername === friend.username
                ? 'bg-(--color-primary)/16 hover:bg-(--color-primary)/16'
                : 'hover:bg-(--color-primary)/8'
            "
            :aria-current="selectedUsername === friend.username ? 'page' : undefined"
            @click="listOpen = false"
          >
            <n-avatar
              round
              :size="36"
              :src="friend.avatarUrl?.trim() || undefined"
              object-fit="cover"
              :img-props="{ alt: '' }"
              class="shrink-0"
            >
              <template v-if="!friend.avatarUrl?.trim()" #default>{{
                Array.from(friendName(friend))[0]?.toUpperCase()
              }}</template>
              <template #fallback>{{ Array.from(friendName(friend))[0]?.toUpperCase() }}</template>
            </n-avatar>
            <span class="flex min-w-0 flex-1 flex-col gap-0.5">
              <span class="truncate font-medium" :title="friendName(friend)">{{
                friendName(friend)
              }}</span>
              <span class="truncate text-xs opacity-65" :title="friend.username">{{
                friend.username
              }}</span>
            </span>
          </router-link>
          <p v-if="hasLoaded && !error && !friends.length" class="my-4.5 text-center text-[13px]">
            暂无好友
          </p>
          <div v-if="error" class="my-4.5 text-center text-[13px]" role="alert">
            <p>{{ error }}</p>
            <n-button size="small" @click="loadFriends">重试</n-button>
          </div>
          <div v-else class="my-4.5 text-center text-[13px]">
            <n-button size="small" :loading="isLoading" @click="loadFriends">
              {{ isLoading ? '正在加载…' : '刷新好友列表' }}
            </n-button>
          </div>
        </nav>
      </n-layout-sider>
      <button
        v-if="isCompact && listOpen"
        type="button"
        class="absolute inset-0 z-2 border-0 bg-black/30"
        aria-label="关闭好友列表"
        @click="listOpen = false"
      />
      <n-layout-content class="min-w-0 flex-1!" content-class="h-full min-w-0">
        <router-view v-slot="{ Component }">
          <component
            :is="Component"
            v-if="Component"
            :key="`${String(route.name)}:${selectedUsername}`"
          />
          <div v-else class="grid h-full min-h-60 place-items-center px-6">
            <n-empty description="选择一位好友，查看详细资料">
              <template #icon><n-icon :component="PersonOutline" /></template>
            </n-empty>
          </div>
        </router-view>
      </n-layout-content>
    </n-layout>
  </div>
</template>
