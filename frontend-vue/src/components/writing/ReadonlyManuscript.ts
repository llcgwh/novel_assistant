import { defineComponent, h, type PropType, type VNodeChild } from 'vue'
import type { DocNode } from '@/types/writing'

// Render the supported manuscript vocabulary as Vue text nodes. No HTML,
// URLs, styles, or event attributes are copied from imported document data.
export function renderManuscriptNode(node: DocNode): VNodeChild {
  if (node.type === 'text') {
    let content: VNodeChild = node.text || ''
    const marks: Record<string, string> = {
      bold: 'strong',
      italic: 'em',
      underline: 'u',
      strike: 's',
      code: 'code',
    }
    for (const mark of node.marks || []) {
      const tag = Object.prototype.hasOwnProperty.call(marks, mark.type)
        ? marks[mark.type]
        : undefined
      if (tag) content = h(tag, null, [content])
    }
    return content
  }
  if (node.type === 'hardBreak') return h('br')
  const tags: Record<string, string> = {
    paragraph: 'p',
    blockquote: 'blockquote',
    horizontalRule: 'hr',
    bulletList: 'ul',
    orderedList: 'ol',
    listItem: 'li',
    codeBlock: 'pre',
  }
  const tag =
    node.type === 'heading'
      ? `h${Math.min(3, Math.max(1, Math.floor(Number(node.attrs?.level) || 1)))}`
      : Object.prototype.hasOwnProperty.call(tags, node.type)
        ? tags[node.type]
        : undefined
  const children = (node.content || []).map(renderManuscriptNode)
  if (!tag) return children
  const attrs: Record<string, unknown> = {}
  if (typeof node.attrs?.id === 'string')
    attrs['data-reader-block'] = node.attrs.id
  const start = node.attrs?.start
  if (tag === 'ol' && Number.isInteger(start)) attrs.start = start
  return h(
    tag,
    attrs,
    children.length ? children : tag === 'p' ? [h('br')] : undefined,
  )
}

export default defineComponent({
  name: 'ReadonlyManuscript',
  props: { doc: { type: Object as PropType<DocNode>, required: true } },
  setup(props) {
    return () =>
      h('div', { class: 'reader-prose' }, [renderManuscriptNode(props.doc)])
  },
})
