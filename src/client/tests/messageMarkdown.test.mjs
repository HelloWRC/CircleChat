import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

let server
let renderMessageMarkdown

before(async () => {
  server = await createServer({
    configFile: false,
    root: fileURLToPath(new URL('../', import.meta.url)),
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: 'custom',
  })
  ;({ renderMessageMarkdown } = await server.ssrLoadModule('/src/utils/messageMarkdown.ts'))
})

after(async () => {
  await server?.close()
})

test('renders inline formatting and preserves chat line breaks', () => {
  assert.equal(
    renderMessageMarkdown('**粗体** *斜体* ~~删除线~~ `const x = 1`\n第二行'),
    '<p><strong>粗体</strong> <em>斜体</em> <s>删除线</s> <code>const x = 1</code><br>\n第二行</p>\n',
  )
  assert.equal(renderMessageMarkdown('你好 👋\r\n世界'), '<p>你好 👋<br>\n世界</p>\n')
  assert.equal(renderMessageMarkdown(''), '')
})

test('renders headings, nested lists, quotes and paragraphs', () => {
  const html = renderMessageMarkdown(
    '# 标题\n\n- 第一项\n  - 子项\n- 第二项\n\n1. 步骤\n\n> 引用\n\n正文',
  )
  assert.match(html, /<h1>标题<\/h1>/)
  assert.match(html, /<ul>\n<li>第一项\n<ul>\n<li>子项<\/li>/)
  assert.match(html, /<ol>\n<li>步骤<\/li>/)
  assert.match(html, /<blockquote>\n<p>引用<\/p>\n<\/blockquote>/)
  assert.match(html, /<p>正文<\/p>/)
})

test('keeps code literal and escapes HTML in fenced code', () => {
  const code = '```html\n<script>alert("x")</script>\n  **literal**\n```'
  assert.equal(
    renderMessageMarkdown(code),
    '<pre><code class="language-html">&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;\n  **literal**\n</code></pre>\n',
  )
})

test('renders explicit and automatic links with safe new-tab attributes', () => {
  const html = renderMessageMarkdown('[网站](https://example.com "标题") https://example.org')
  assert.match(
    html,
    /<a href="https:\/\/example.com" title="标题" target="_blank" rel="noopener noreferrer">网站<\/a>/,
  )
  assert.match(html, /<a href="https:\/\/example.org" target="_blank" rel="noopener noreferrer">/)
})

test('renders raw HTML as text and never embeds message images', () => {
  const html = renderMessageMarkdown(
    '<script>alert(1)</script>\n<img src=x onerror=alert(1)>\n![图片](https://example.com/pixel.png)',
  )
  assert.doesNotMatch(html, /<(script|img)\b/i)
  assert.match(html, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/)
  assert.match(html, /&lt;img src=x onerror=alert\(1\)&gt;/)
})

test('rejects executable and encoded dangerous link protocols', () => {
  for (const url of [
    'javascript:alert(1)',
    'JaVaScRiPt:alert(1)',
    'javascript&#58;alert(1)',
    'jav&#x61;script:alert(1)',
    'vbscript:msgbox(1)',
    'data:text/html;base64,PHNjcmlwdD4=',
    'file:///etc/passwd',
  ]) {
    assert.doesNotMatch(renderMessageMarkdown(`[点击](${url})`), /<a\b/i, url)
  }
})

test('escapes link titles and fence language attributes', () => {
  const html = renderMessageMarkdown(
    '[链接](https://example.com "&quot; onmouseover=&quot;alert(1)")\n\n```x"onclick="alert(1)\ncode\n```',
  )
  assert.doesNotMatch(html, / (onmouseover|onclick)="/i)
  assert.match(html, /title="&quot; onmouseover=&quot;alert\(1\)"/)
  assert.match(html, /class="language-x&quot;onclick=&quot;alert\(1\)"/)
})
