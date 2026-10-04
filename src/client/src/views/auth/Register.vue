<script setup lang="ts">
import type { FormInst, FormRules } from 'naive-ui'
import { register } from '@/api'
import { HttpError } from '@/api/instance'

defineOptions({ name: 'AuthRegister' })

const route = useRoute()
const router = useRouter()
const message = useMessage()
const formRef = ref<FormInst | null>(null)
const form = reactive({
  username: '',
  displayName: '',
  email: '',
  password: '',
  confirmPassword: '',
})
const loading = ref(false)
const loginLocation = computed(() => ({
  name: 'auth.login',
  query: { redirect: route.query.redirect },
}))
const rules: FormRules = {
  username: {
    required: true,
    validator: (_rule, value: string) => !!value?.trim() || new Error('请输入用户名'),
    trigger: ['input', 'blur'],
  },
  displayName: {
    required: true,
    validator: (_rule, value: string) => !!value?.trim() || new Error('请输入昵称'),
    trigger: ['input', 'blur'],
  },
  email: {
    required: true,
    validator: (_rule, value: string) => {
      if (!value?.trim()) return new Error('请输入邮箱')
      return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.trim()) || new Error('请输入有效的邮箱地址')
    },
    trigger: ['input', 'blur'],
  },
  password: { required: true, message: '请输入密码', trigger: ['input', 'blur'] },
  confirmPassword: {
    required: true,
    validator: (_rule, value: string) => {
      if (!value) return new Error('请再次输入密码')
      return value === form.password || new Error('两次输入的密码不一致')
    },
    trigger: ['input', 'blur'],
  },
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

    const response = await register({
      data: {
        username: form.username.trim(),
        displayName: form.displayName.trim(),
        email: form.email.trim(),
        password: form.password,
      },
    })
    if (response.statusCode !== 200) {
      message.error(response.message || '注册失败，请稍后重试')
      return
    }

    form.password = ''
    form.confirmPassword = ''
    message.success('注册成功，请登录')
    await router.replace({
      ...loginLocation.value,
      query: { ...loginLocation.value.query, username: form.username.trim() },
    })
  } catch (error) {
    if (error instanceof HttpError) {
      if (error.status === 409) message.error('用户名或邮箱已被使用')
      else if (error.status === 400) message.error('注册信息不正确，请检查后重试')
      else message.error('注册失败，请稍后重试')
    } else {
      message.error('无法完成注册，请检查网络后重试')
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
      <h2 class="text-xl">注册</h2>
    </div>

    <n-form ref="formRef" :model="form" :rules="rules" @submit.prevent="submit">
      <n-form-item label="用户名" path="username">
        <div class="flex w-full flex-col gap-1">
          <n-input
            v-model:value="form.username"
            placeholder="请输入用户名"
            autocomplete="username"
            :input-props="{ 'aria-describedby': 'register-username-description' }"
            :disabled="loading"
          />
          <n-text id="register-username-description" :depth="3" class="text-xs">
            用户名将用于登录，注册后不可修改
          </n-text>
        </div>
      </n-form-item>
      <n-form-item label="昵称" path="displayName">
        <n-input
          v-model:value="form.displayName"
          placeholder="请输入昵称"
          autocomplete="nickname"
          :disabled="loading"
        />
      </n-form-item>
      <n-form-item label="邮箱" path="email">
        <n-input
          v-model:value="form.email"
          placeholder="请输入邮箱"
          autocomplete="email"
          :input-props="{ type: 'email', inputmode: 'email' }"
          :disabled="loading"
        />
      </n-form-item>
      <n-form-item label="密码" path="password">
        <n-input
          v-model:value="form.password"
          type="password"
          show-password-on="click"
          placeholder="请输入密码"
          autocomplete="new-password"
          :disabled="loading"
        />
      </n-form-item>
      <n-form-item label="确认密码" path="confirmPassword">
        <n-input
          v-model:value="form.confirmPassword"
          type="password"
          show-password-on="click"
          placeholder="请再次输入密码"
          autocomplete="new-password"
          :disabled="loading"
        />
      </n-form-item>
      <n-button type="primary" attr-type="submit" block :loading="loading" :disabled="loading">
        注册
      </n-button>
    </n-form>

    <div class="self-center text-sm">
      已有账号？
      <router-link class="text-primary" :to="loginLocation">登录</router-link>
    </div>
  </div>
</template>
