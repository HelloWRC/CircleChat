<script setup lang="ts">
import { computed } from 'vue'
import { renderMessageMarkdown } from '@/utils/messageMarkdown'

const props = defineProps<{ body: string }>()
const html = computed(() => renderMessageMarkdown(props.body))
</script>

<template>
  <div class="message-markdown" v-html="html" />
</template>

<style scoped>
.message-markdown {
  min-width: 0;
  overflow-wrap: anywhere;
  white-space: normal;
}

.message-markdown :deep(p),
.message-markdown :deep(ul),
.message-markdown :deep(ol),
.message-markdown :deep(blockquote),
.message-markdown :deep(pre),
.message-markdown :deep(table) {
  margin: 0.6em 0;
}

.message-markdown :deep(> :first-child) {
  margin-top: 0;
}

.message-markdown :deep(> :last-child) {
  margin-bottom: 0;
}

.message-markdown :deep(h1),
.message-markdown :deep(h2),
.message-markdown :deep(h3),
.message-markdown :deep(h4),
.message-markdown :deep(h5),
.message-markdown :deep(h6) {
  margin: 0.75em 0 0.35em;
  font-size: 1em;
  font-weight: 600;
  line-height: 1.4;
}

.message-markdown :deep(h1) {
  font-size: 1.3em;
}

.message-markdown :deep(h2) {
  font-size: 1.15em;
}

.message-markdown :deep(ul),
.message-markdown :deep(ol) {
  padding-left: 1.5em;
}

.message-markdown :deep(ul) {
  list-style-type: disc;
}

.message-markdown :deep(ol) {
  list-style-type: decimal;
}

.message-markdown :deep(li + li) {
  margin-top: 0.2em;
}

.message-markdown :deep(blockquote) {
  border-left: 3px solid color-mix(in srgb, currentColor 30%, transparent);
  padding-left: 0.75em;
  color: color-mix(in srgb, currentColor 80%, transparent);
}

.message-markdown :deep(blockquote > :first-child) {
  margin-top: 0;
}

.message-markdown :deep(blockquote > :last-child) {
  margin-bottom: 0;
}

.message-markdown :deep(a) {
  color: inherit;
  text-decoration: underline;
  text-underline-offset: 0.15em;
}

.message-markdown :deep(a:focus-visible) {
  border-radius: 2px;
  outline: 2px solid currentColor;
  outline-offset: 2px;
}

.message-markdown :deep(code) {
  border-radius: 4px;
  background: color-mix(in srgb, currentColor 8%, transparent);
  padding: 0.1em 0.3em;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 0.9em;
}

.message-markdown :deep(pre) {
  max-width: 100%;
  overflow-x: auto;
  border-radius: 8px;
  background: color-mix(in srgb, currentColor 8%, transparent);
  padding: 0.75em;
  white-space: pre;
  overflow-wrap: normal;
}

.message-markdown :deep(pre code) {
  border-radius: 0;
  background: transparent;
  padding: 0;
}

.message-markdown :deep(table) {
  display: block;
  max-width: 100%;
  overflow-x: auto;
  border-collapse: collapse;
}

.message-markdown :deep(th),
.message-markdown :deep(td) {
  border: 1px solid color-mix(in srgb, currentColor 25%, transparent);
  padding: 0.3em 0.6em;
}

.message-markdown :deep(hr) {
  margin: 0.75em 0;
  border: 0;
  border-top: 1px solid color-mix(in srgb, currentColor 25%, transparent);
}
</style>
