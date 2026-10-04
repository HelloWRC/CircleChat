<script setup lang="ts">
import { MAIN_CONVERSATION_ID, useChatClient } from '@/api/chat.ts'

const client = useChatClient()
const messages = ref<Array<string>>([])
const pending = ref<string>('')

onMounted(() => {
  client.subscribe(MAIN_CONVERSATION_ID, (message) => messages.value.push(message.message.body))
})
function send() {
  client.send(MAIN_CONVERSATION_ID, pending.value)
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
