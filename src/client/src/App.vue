<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue'
import { zhCN, dateZhCN, darkTheme } from 'naive-ui'
import type { GlobalThemeOverrides } from 'naive-ui'
import themeVariables from './assets/theme.module.scss'

const themeOverrides: GlobalThemeOverrides = {
  common: {
    fontFamily: themeVariables.fontFamily,
    primaryColor: themeVariables.primaryColor,
    primaryColorHover: themeVariables.primaryColorHover,
    primaryColorPressed: themeVariables.primaryColorPressed,
    primaryColorSuppl: themeVariables.primaryColor,
  },
}

const colorScheme = window.matchMedia('(prefers-color-scheme: dark)')
const prefersDark = ref(colorScheme.matches)
const theme = computed(() => (prefersDark.value ? darkTheme : null))

function syncColorScheme(event: MediaQueryListEvent) {
  prefersDark.value = event.matches
}

colorScheme.addEventListener('change', syncColorScheme)
onScopeDispose(() => colorScheme.removeEventListener('change', syncColorScheme))
</script>

<template>
  <n-config-provider
    :locale="zhCN"
    :date-locale="dateZhCN"
    :theme="theme"
    :theme-overrides="themeOverrides"
    class="contents"
  >
    <n-global-style />
    <n-loading-bar-provider>
      <n-dialog-provider>
        <n-notification-provider>
          <n-message-provider>
            <RouterView />
          </n-message-provider>
        </n-notification-provider>
      </n-dialog-provider>
    </n-loading-bar-provider>
  </n-config-provider>
</template>
