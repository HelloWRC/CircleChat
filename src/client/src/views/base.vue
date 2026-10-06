<script setup lang="ts">
import type { Component } from 'vue'
import { storeToRefs } from 'pinia'
import { ChatbubbleOutline, LogOutOutline, SettingsOutline, PersonOutline } from '@vicons/ionicons5'
import { NIcon, type DropdownOption, type MenuOption } from 'naive-ui'
import logo from '@/assets/logo.svg'
import { useUserStore } from '@/stores/user'

defineOptions({ name: 'AppLayout' })

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const { displayName, avatarUrl, isLoggingOut } = storeToRefs(userStore)
const message = useMessage()
const isUserMenuOpen = ref(false)

const avatarInitial = computed(() => Array.from(displayName.value)[0]?.toUpperCase() || '用')

function renderIcon(icon: Component) {
  return () => h(NIcon, null, { default: () => h(icon) })
}

const menuOptions: MenuOption[] = [
  {
    label: '聊天',
    key: 'chat',
    icon: renderIcon(ChatbubbleOutline),
  },
  {
    label: '好友',
    key: 'friends',
    icon: renderIcon(PersonOutline),
  },
]

const activeMenuKey = computed(
  () =>
    menuOptions.find((option) => route.matched.some((record) => record.name === option.key))?.key ??
    null,
)

const userMenuOptions: DropdownOption[] = [
  {
    label: '设置',
    key: 'settings',
    icon: renderIcon(SettingsOutline),
  },
  {
    label: '登出',
    key: 'logout',
    icon: renderIcon(LogOutOutline),
  },
]

function handleMenuSelect(key: string) {
  void router.push({ name: key })
}

async function handleUserMenuSelect(key: string | number) {
  if (isLoggingOut.value) return
  if (key === 'settings') {
    await router.push({ name: 'settings' })
    return
  }

  if (key === 'logout') {
    try {
      await userStore.logout()
      message.success('已退出登录')
    } catch {
      message.error('本地登录状态已清除，但服务端登出失败，请检查网络后重试')
    } finally {
      await router.replace({ name: 'auth.login' })
    }
  }
}
</script>

<template>
  <n-layout has-sider class="min-h-dvh">
    <n-layout-sider
      bordered
      :width="72"
      content-class="flex! flex-col items-center px-2 py-3"
      class="h-dvh min-h-dvh"
    >
      <router-link
        :to="{ name: 'chat' }"
        class="mb-4 grid size-12 place-items-center rounded-[14px] transition-[background-color,transform] duration-150 hover:bg-(--color-primary)/12 active:scale-96 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--color-primary)"
        aria-label="CircleChat 首页"
      >
        <img :src="logo" width="36" height="36" alt="" />
      </router-link>

      <nav class="w-14" aria-label="主导航">
        <n-menu
          collapsed
          :collapsed-width="56"
          :collapsed-icon-size="22"
          :options="menuOptions"
          :value="activeMenuKey"
          :theme-overrides="{ itemHeight: '44px', borderRadius: '12px' }"
          @update:value="handleMenuSelect"
        />
      </nav>

      <div class="mt-auto">
        <n-dropdown
          v-model:show="isUserMenuOpen"
          trigger="click"
          placement="right-end"
          show-arrow
          :options="userMenuOptions"
          @select="handleUserMenuSelect"
        >
          <button
            type="button"
            class="grid size-12 cursor-pointer place-items-center rounded-[14px] border-0 bg-transparent p-0 text-inherit transition-[background-color,transform] duration-150 hover:bg-(--color-primary)/12 active:scale-96 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--color-primary) aria-expanded:bg-(--color-primary)/12"
            :aria-label="`打开 ${displayName} 的用户菜单`"
            aria-haspopup="menu"
            :aria-expanded="isUserMenuOpen"
            :aria-busy="isLoggingOut"
            :disabled="isLoggingOut"
          >
            <n-avatar
              round
              :size="36"
              :src="avatarUrl"
              object-fit="cover"
              :img-props="{ alt: '' }"
              class="ring-1 ring-black/10 dark:ring-white/10"
            >
              <template v-if="!avatarUrl" #default>{{ avatarInitial }}</template>
              <template #fallback>
                <span class="grid size-full place-items-center">{{ avatarInitial }}</span>
              </template>
            </n-avatar>
          </button>
        </n-dropdown>
      </div>
    </n-layout-sider>

    <n-layout class="min-h-dvh min-w-0">
      <n-layout-content content-class="min-w-0">
        <router-view />
      </n-layout-content>
    </n-layout>
  </n-layout>
</template>
