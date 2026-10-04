<script setup lang="ts">
import type { Component } from 'vue'
import { storeToRefs } from 'pinia'
import { ChatbubbleOutline, LogOutOutline, SettingsOutline } from '@vicons/ionicons5'
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
const activeMenuKey = computed(() =>
  route.matched.some((record) => record.name === 'chat') ? 'chat' : null,
)

function renderIcon(icon: Component) {
  return () => h(NIcon, null, { default: () => h(icon) })
}

const menuOptions: MenuOption[] = [
  {
    label: '聊天',
    key: 'chat',
    icon: renderIcon(ChatbubbleOutline),
  },
]

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
  <n-layout has-sider class="min-h-screen">
    <n-layout-sider
      bordered
      :width="72"
      content-style="display: flex; flex-direction: column; align-items: center; padding: 12px 8px;"
      class="app-sidebar"
    >
      <router-link :to="{ name: 'chat' }" class="logo-link" aria-label="CircleChat 首页">
        <img :src="logo" width="36" height="36" alt="" />
      </router-link>

      <nav class="sidebar-nav" aria-label="主导航">
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

      <div class="account-menu">
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
            class="account-trigger"
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
              class="user-avatar"
            >
              <template v-if="!avatarUrl" #default>{{ avatarInitial }}</template>
              <template #fallback>
                <span class="avatar-fallback">{{ avatarInitial }}</span>
              </template>
            </n-avatar>
          </button>
        </n-dropdown>
      </div>
    </n-layout-sider>

    <n-layout class="min-h-screen">
      <n-layout-content>
        <router-view />
      </n-layout-content>
    </n-layout>
  </n-layout>
</template>

<style scoped>
.app-sidebar {
  min-height: 100vh;
}

.logo-link {
  display: grid;
  width: 48px;
  height: 48px;
  margin-bottom: 16px;
  place-items: center;
  border-radius: 14px;
  transition-property: background-color, transform;
  transition-duration: 150ms;
}

.logo-link:hover {
  background-color: color-mix(in srgb, var(--color-primary) 12%, transparent);
}

.logo-link:active {
  transform: scale(0.96);
}

.logo-link:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

.sidebar-nav {
  width: 56px;
}

.account-menu {
  margin-top: auto;
}

.account-trigger {
  display: grid;
  width: 48px;
  height: 48px;
  padding: 0;
  color: inherit;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 14px;
  place-items: center;
  transition-property: background-color, transform;
  transition-duration: 150ms;
}

.account-trigger:hover,
.account-trigger[aria-expanded='true'] {
  background-color: color-mix(in srgb, var(--color-primary) 12%, transparent);
}

.account-trigger:active {
  transform: scale(0.96);
}

.account-trigger:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

.user-avatar {
  box-shadow: 0 0 0 1px oklch(0 0 0 / 0.1);
}

.avatar-fallback {
  display: grid;
  width: 100%;
  height: 100%;
  place-items: center;
}

@media (prefers-color-scheme: dark) {
  .user-avatar {
    box-shadow: 0 0 0 1px oklch(1 0 0 / 0.1);
  }
}
</style>
