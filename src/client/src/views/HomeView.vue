<script setup lang="ts">
import { useChatClient } from '@/api/chat.ts'

const client = useChatClient()
const messages = ref<Array<string>>([])
const pending = ref<string>('')

onMounted(() => {
  client.subscribe('main', (message) => messages.value.push(message.message))
})
function send() {
  client.send(pending.value)
  pending.value = ''
  messages.value.push(pending.value)
}
</script>

<template>
  <n-input v-model:value="pending" />
  <n-button :onclick="send">发送</n-button>
  <div>
    <p v-for="i in messages">{{ i }}</p>
  </div>
</template>
