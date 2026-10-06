<script setup lang="ts">
import {
  NAvatar,
  NButton,
  NForm,
  NFormItem,
  NInput,
  NText,
  type FormInst,
  type FormRules,
} from 'naive-ui'
import { changePassword } from '@/api'
import { HttpError } from '@/api/instance'
import { useUserStore } from '@/stores/user'

defineOptions({ name: 'AccountSettings' })

const userStore = useUserStore()
const message = useMessage()
const profileFormRef = ref<FormInst | null>(null)
const passwordFormRef = ref<FormInst | null>(null)
const profile = reactive({ displayName: userStore.user?.displayName ?? '' })
const passwords = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const savingProfile = ref(false)
const savingPassword = ref(false)
const profileError = ref('')
const passwordError = ref('')
const disabled = computed(() => !userStore.isAuthenticated || userStore.isLoggingOut)
const avatarUrl = computed(() => userStore.user?.avatarLargeUrl?.trim() || userStore.avatarUrl)
const avatarInitial = computed(() => Array.from(userStore.displayName)[0]?.toUpperCase() || '用')
let active = true

const profileRules: FormRules = {
  displayName: {
    required: true,
    validator: (_rule, value: string) => {
      const name = value?.trim()
      if (!name) return new Error('请输入显示名称')
      return name.length <= 64 || new Error('显示名称不能超过 64 个字符')
    },
    trigger: ['input', 'blur'],
  },
}
const passwordRules: FormRules = {
  currentPassword: { required: true, message: '请输入当前密码', trigger: ['input', 'blur'] },
  newPassword: {
    required: true,
    validator: (_rule, value: string) => {
      if (!value) return new Error('请输入新密码')
      return (
        new TextEncoder().encode(value).length <= 72 || new Error('新密码不能超过 72 个 UTF-8 字节')
      )
    },
    trigger: ['input', 'blur'],
  },
  confirmPassword: {
    required: true,
    validator: (_rule, value: string) => {
      if (!value) return new Error('请再次输入新密码')
      return value === passwords.newPassword || new Error('两次输入的新密码不一致')
    },
    trigger: ['input', 'blur'],
  },
}

function clearPasswords() {
  passwords.currentPassword = ''
  passwords.newPassword = ''
  passwords.confirmPassword = ''
}

watch(
  () => userStore.isAuthenticated,
  (authenticated) => {
    if (!authenticated) {
      clearPasswords()
      profile.displayName = ''
      profileError.value = ''
      passwordError.value = ''
    }
  },
)
onScopeDispose(() => {
  active = false
  clearPasswords()
})

async function saveProfile() {
  if (savingProfile.value || disabled.value || !profileFormRef.value) return
  savingProfile.value = true
  profileError.value = ''
  try {
    try {
      await profileFormRef.value.validate()
    } catch {
      return
    }
    if (!active || disabled.value) return
    const user = await userStore.updateProfile(profile.displayName.trim())
    if (!active || disabled.value) return
    profile.displayName = user.displayName ?? ''
    message.success('显示名称已保存')
  } catch (error) {
    if (!active || disabled.value) return
    profileError.value =
      error instanceof HttpError && error.status === 400
        ? error.message
        : '无法保存显示名称，请检查网络后重试'
  } finally {
    savingProfile.value = false
  }
}

async function savePassword() {
  if (savingPassword.value || disabled.value || !passwordFormRef.value) return
  savingPassword.value = true
  passwordError.value = ''
  try {
    try {
      await passwordFormRef.value.validate()
    } catch {
      return
    }
    if (!active || disabled.value) return
    const response = await changePassword({
      data: { currentPassword: passwords.currentPassword, newPassword: passwords.newPassword },
    })
    if (!active || disabled.value) return
    if (response.statusCode !== 200) {
      passwordError.value = response.message || '无法修改密码，请稍后重试'
      return
    }
    clearPasswords()
    passwordFormRef.value?.restoreValidation()
    message.success('密码已修改')
  } catch (error) {
    if (!active || disabled.value) return
    passwordError.value =
      error instanceof HttpError && error.status === 400
        ? error.message
        : '无法修改密码，请检查网络后重试'
  } finally {
    savingPassword.value = false
  }
}
</script>

<template>
  <div class="h-dvh min-h-0 overflow-y-auto overscroll-contain">
    <main class="mx-auto flex w-full max-w-2xl flex-col gap-8 px-4 py-6 sm:px-8 sm:py-10">
      <header>
        <h1 class="m-0 text-xl font-semibold">账户设置</h1>
      </header>

      <section aria-labelledby="settings-avatar-heading" class="flex flex-col gap-4">
        <h2 id="settings-avatar-heading" class="m-0 text-lg font-medium">头像</h2>
        <div class="flex flex-col gap-4 sm:flex-row sm:items-center">
          <n-avatar
            round
            :size="80"
            :src="avatarUrl"
            object-fit="cover"
            :img-props="{ alt: `${userStore.displayName} 的头像` }"
            class="shrink-0"
          >
            <template v-if="!avatarUrl" #default>{{ avatarInitial }}</template>
            <template #fallback>
              <span class="grid size-full place-items-center text-2xl">{{ avatarInitial }}</span>
            </template>
          </n-avatar>
          <div class="flex min-w-0 flex-col gap-2">
            <n-text :depth="3">头像通过 Gravatar 获取，请使用本账户邮箱设置</n-text>
            <n-text class="break-all">{{ userStore.user?.email || '此账户尚未设置邮箱' }}</n-text>
            <a
              href="https://gravatar.com/"
              target="_blank"
              rel="noopener noreferrer"
              class="text-primary w-fit rounded hover:underline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-(--color-primary)"
              >前往 Gravatar 设置头像（新窗口）</a
            >
          </div>
        </div>
      </section>

      <section
        aria-labelledby="settings-profile-heading"
        class="border-t border-black/10 pt-6 dark:border-white/10"
      >
        <h2 id="settings-profile-heading" class="m-0 mb-4 text-lg font-medium">显示名称</h2>
        <n-text :depth="3" class="mb-4 block break-all"
          >用户名：{{ userStore.user?.username }}</n-text
        >
        <n-form
          ref="profileFormRef"
          :model="profile"
          :rules="profileRules"
          @submit.prevent="saveProfile"
        >
          <n-form-item label="显示名称" path="displayName" label-for="settings-display-name">
            <n-input
              v-model:value="profile.displayName"
              placeholder="请输入显示名称"
              autocomplete="nickname"
              :input-props="{
                id: 'settings-display-name',
                'aria-describedby': 'settings-name-description',
              }"
              :disabled="savingProfile || disabled"
            />
          </n-form-item>
          <n-text id="settings-name-description" :depth="3" class="mb-4 block text-sm"
            >显示名称须为 1–64 个字符</n-text
          >
          <p v-if="profileError" role="alert" class="mt-0 text-red-600 dark:text-red-400">
            {{ profileError }}
          </p>
          <n-button
            type="primary"
            attr-type="submit"
            :loading="savingProfile"
            :disabled="savingProfile || disabled"
          >
            保存显示名称
          </n-button>
        </n-form>
      </section>

      <section
        aria-labelledby="settings-password-heading"
        class="border-t border-black/10 pt-6 dark:border-white/10"
      >
        <h2 id="settings-password-heading" class="m-0 mb-4 text-lg font-medium">修改密码</h2>
        <n-form
          ref="passwordFormRef"
          :model="passwords"
          :rules="passwordRules"
          @submit.prevent="savePassword"
        >
          <n-form-item
            label="当前密码"
            path="currentPassword"
            label-for="settings-current-password"
          >
            <n-input
              v-model:value="passwords.currentPassword"
              type="password"
              show-password-on="click"
              placeholder="请输入当前密码"
              autocomplete="current-password"
              :input-props="{ id: 'settings-current-password' }"
              :disabled="savingPassword || disabled"
            />
          </n-form-item>
          <n-form-item label="新密码" path="newPassword" label-for="settings-new-password">
            <n-input
              v-model:value="passwords.newPassword"
              type="password"
              show-password-on="click"
              placeholder="请输入新密码"
              autocomplete="new-password"
              :input-props="{
                id: 'settings-new-password',
                'aria-describedby': 'settings-password-description',
              }"
              :disabled="savingPassword || disabled"
              @update:value="passwordFormRef?.restoreValidation()"
            />
          </n-form-item>
          <n-form-item
            label="确认新密码"
            path="confirmPassword"
            label-for="settings-confirm-password"
          >
            <n-input
              v-model:value="passwords.confirmPassword"
              type="password"
              show-password-on="click"
              placeholder="请再次输入新密码"
              autocomplete="new-password"
              :input-props="{ id: 'settings-confirm-password' }"
              :disabled="savingPassword || disabled"
            />
          </n-form-item>
          <p v-if="passwordError" role="alert" class="mt-0 text-red-600 dark:text-red-400">
            {{ passwordError }}
          </p>
          <n-button
            type="primary"
            attr-type="submit"
            :loading="savingPassword"
            :disabled="savingPassword || disabled"
          >
            修改密码
          </n-button>
        </n-form>
      </section>
    </main>
  </div>
</template>
