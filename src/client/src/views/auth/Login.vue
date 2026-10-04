<script setup lang="ts">
import type { FormInst, FormRules } from 'naive-ui'
import { HttpError } from '@/api/instance'
import { getLoginRedirect } from '@/router/guards'
import { useUserStore } from '@/stores/user'

defineOptions({ name: 'AuthLogin' })

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const message = useMessage()
const formRef = ref<FormInst | null>(null)
const credentials = reactive({
  username: typeof route.query.username === 'string' ? route.query.username : '',
  password: '',
})
const loading = ref(false)
const rules: FormRules = {
  username: {
    required: true,
    validator: (_rule, value: string) => !!value?.trim() || new Error('请输入用户名'),
    trigger: ['input', 'blur'],
  },
  password: { required: true, message: '请输入密码', trigger: ['input', 'blur'] },
}

async function submit() {
  if (loading.value || !formRef.value) return
  loading.value = true
  try {
    try {
      await formRef.value.validate()
    } catch {
      return
    }

    await user.login({ username: credentials.username.trim(), password: credentials.password })
    credentials.password = ''
    message.success('登录成功')
    await router.replace(getLoginRedirect(router, route.query.redirect))
  } catch (error) {
    if (error instanceof HttpError && (error.status === 401 || error.status === 403)) {
      message.error('用户名或密码错误')
    } else {
      message.error(
        error instanceof HttpError ? '登录失败，请稍后重试' : '无法完成登录，请检查网络后重试',
      )
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="flex flex-col gap-2">
    <div>
      <h1 class="text-primary font-medium">CiRCLE Chat</h1>
      <h2 class="text-xl">登录</h2>
    </div>

    <n-form ref="formRef" :model="credentials" :rules="rules" @submit.prevent="submit">
      <n-form-item label="用户名" path="username">
        <n-input
          v-model:value="credentials.username"
          placeholder="请输入用户名"
          autocomplete="username"
          :disabled="loading"
        />
      </n-form-item>
      <n-form-item label="密码" path="password">
        <n-input
          v-model:value="credentials.password"
          type="password"
          show-password-on="click"
          placeholder="请输入密码"
          autocomplete="current-password"
          :disabled="loading"
        />
      </n-form-item>
      <n-button type="primary" attr-type="submit" block :loading="loading" :disabled="loading">
        登录
      </n-button>
    </n-form>

    <div class="self-center text-sm">
      还没有账号？
      <router-link
        class="text-primary"
        :to="{ name: 'auth.register', query: { redirect: route.query.redirect } }"
      >
        注册
      </router-link>
    </div>
  </div>
</template>

<style scoped></style>
