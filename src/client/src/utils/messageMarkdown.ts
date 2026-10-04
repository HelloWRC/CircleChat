import MarkdownIt from 'markdown-it'

// Messages are untrusted: keep HTML disabled and use the parser's URL validation.
const markdown = new MarkdownIt({ html: false, breaks: true, linkify: true }).disable('image')

markdown.renderer.rules.link_open = (tokens, index, options, _env, renderer) => {
  const token = tokens[index]!
  token.attrSet('target', '_blank')
  token.attrSet('rel', 'noopener noreferrer')
  return renderer.renderToken(tokens, index, options)
}

export function renderMessageMarkdown(body: string): string {
  return markdown.render(body)
}
