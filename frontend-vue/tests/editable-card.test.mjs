import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { baseParse } from '@vue/compiler-dom'
import { compileTemplate, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import { compileSources } from './helpers/load-source.mjs'

const { isCardEditEvent } = await compileSources()('utils/editableCard')

// Minimal DOM identity/selector/selection primitives; handlers and template bindings
// are the production code, and no control handler is replaced by card logic.
function dom(tag = 'div', attributes = {}, parent = null) {
  const node = {
    nodeType: 1, tag, attributes, parentElement: parent, children: [],
    ownerDocument: parent?.ownerDocument || { getSelection: () => null },
    contains(other) { return other === this || this.children.some(child => child.contains(other)) },
    matches(selector) {
      const not = selector.match(/:not\((.*)\)$/)
      if (not) return this.matches(selector.slice(0, not.index)) && !this.matches(not[1])
      const part = selector.match(/^([\w-]+)?(?:\[([\w-]+)(?:="([^"]*)")?\])?$/)
      if (!part) throw new Error(`Unsupported test selector ${selector}`)
      return (!part[1] || part[1] === this.tag) && (!part[2] || part[2] in this.attributes && (part[3] === undefined || String(this.attributes[part[2]]) === part[3]))
    },
    closest(selector) {
      for (let current = this; current; current = current.parentElement) if (selector.split(',').some(part => current.matches(part))) return current
      return null
    },
  }
  if (parent) parent.children.push(node)
  return node
}
function event(card, target = card, extra = {}) {
  return { type: 'click', button: 0, defaultPrevented: false, target, currentTarget: card, ...extra }
}

test('card surfaces accept ordinary clicks and a single unmodified Enter key', () => {
  const card = dom('article', { tabindex: '0', role: 'group' })
  const paragraph = dom('p', {}, card), image = dom('img', {}, card)
  for (const target of [card, paragraph, image]) assert.equal(isCardEditEvent(event(card, target)), true)
  assert.equal(isCardEditEvent(event(card, card, { type: 'keydown', key: 'Enter' })), true)
  for (const extra of [{ button: 1 }, { button: 2 }, { ctrlKey: true }, { metaKey: true }, { altKey: true }, { shiftKey: true }, { defaultPrevented: true }, { type: 'dblclick' }]) {
    assert.equal(isCardEditEvent(event(card, paragraph, extra)), false)
  }
  for (const extra of [{ key: ' ' }, { key: 'Escape' }, { key: 'Enter', repeat: true }, { key: 'Enter', isComposing: true }]) {
    assert.equal(isCardEditEvent(event(card, card, { type: 'keydown', ...extra })), false)
  }
  assert.equal(isCardEditEvent(event(card, paragraph, { type: 'keydown', key: 'Enter' })), false)
  assert.equal(isCardEditEvent(event(card, dom('div'))), false)
})

test('native controls, nested icons, links, labels, and custom widgets keep their own actions', () => {
  const card = dom('div', { tabindex: '0', role: 'group' })
  const controls = [
    ['button', {}], ['button', { disabled: true }], ['a', { href: '/details' }],
    ['input', { type: 'checkbox' }], ['select', {}], ['textarea', {}], ['label', {}], ['summary', {}],
    ['audio', { controls: true }], ['video', { controls: true }],
    ['div', { contenteditable: 'true' }], ['div', { contenteditable: '' }],
    ...['button', 'link', 'checkbox', 'radio', 'switch', 'combobox', 'textbox', 'slider'].map(role => ['span', { role }]),
    ['span', { tabindex: '-1' }], ['span', { 'data-no-card-edit': '' }],
  ]
  for (const [tag, attributes] of controls) {
    const control = dom(tag, attributes, card), icon = dom('svg', {}, control), path = dom('path', {}, icon)
    for (const target of [control, path]) {
      assert.equal(isCardEditEvent(event(card, target)), false, `${tag} ${JSON.stringify(attributes)}`)
      assert.equal(isCardEditEvent(event(card, target, { type: 'keydown', key: 'Enter' })), false)
    }
  }
  assert.equal(isCardEditEvent(event(card, dom('span', { contenteditable: 'false' }, card))), true)
})

test('selecting card text never opens editing, while unrelated selection does not disable cards', () => {
  const card = dom('article', { tabindex: '0' }), text = dom('p', {}, card), outside = dom('p')
  card.ownerDocument.getSelection = () => ({ isCollapsed: false, anchorNode: text, focusNode: text, rangeCount: 0 })
  assert.equal(isCardEditEvent(event(card, text)), false)
  card.ownerDocument.getSelection = () => ({ isCollapsed: false, anchorNode: outside, focusNode: outside, rangeCount: 1, getRangeAt: () => ({ intersectsNode: node => node === card }) })
  assert.equal(isCardEditEvent(event(card)), false)
  card.ownerDocument.getSelection = () => ({ isCollapsed: false, anchorNode: outside, focusNode: outside, rangeCount: 1, getRangeAt: () => ({ intersectsNode: () => false }) })
  assert.equal(isCardEditEvent(event(card)), true)
  card.ownerDocument.getSelection = () => ({ isCollapsed: true })
  assert.equal(isCardEditEvent(event(card)), true)
})

const item = { id: 7, title: '条目标题', name: '条目名称', content: '资料正文', description: '资料说明', status: 'planned', tags: [], characters: [], scenes: [], foreshadows: [], outlines: [], characterId1: 1, characterId2: 2 }
const cases = [
  ['ForeshadowsView', 'foreshadow in', 'foreshadowsStore', 'filteredForeshadows', 'editForeshadow'],
  ['OutlinesView', 'outline in', 'outlinesStore', 'filteredOutlines', 'editOutline'],
  ['TagsView', 'tag in', 'tagsStore', 'tags', 'editTag'],
  ['MapView', 'location in', 'mapStore', 'locations', 'editLocation'],
  ['TimelineView', 'event in', 'timelineStore', 'sortedEvents', 'editEvent'],
  ['RelationshipsView', 'rel in', 'relationshipsStore', 'relationships', 'editRelationship'],
  ['RelationshipsView', 'group in', null, 'groupTree', 'editGroup'],
]

function cardTemplate(view, loop) {
  const { descriptor } = parse(readFileSync(new URL(`../src/views/${view}.vue`, import.meta.url), 'utf8'))
  const visit = node => {
    if (node.type === 1 && node.props.some(prop => prop.type === 7 && prop.name === 'for' && prop.exp?.content.startsWith(loop))) return node
    for (const child of node.children || []) { const found = visit(child); if (found) return found }
  }
  const node = visit(baseParse(descriptor.template.content))
  assert.ok(node, `${view} list card exists`)
  const compiled = compileTemplate({ source: node.loc.source, filename: `${view}.vue`, id: view })
  assert.deepEqual(compiled.errors, [])
  const code = ts.transpileModule(compiled.code, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }
  new Function('require', 'module', 'exports', code)(name => {
    assert.equal(name, 'vue')
    return { ...Vue, resolveComponent: name => name }
  }, module, module.exports)
  return module.exports.render
}
function vnodeDOM(vnode, parent = null) {
  if (Array.isArray(vnode)) return vnode.flatMap(child => vnodeDOM(child, parent))
  if (!vnode || typeof vnode !== 'object') return []
  if (typeof vnode.type !== 'string') return vnodeDOM(vnode.children, parent)
  const element = dom(vnode.type, vnode.props || {}, parent)
  element.props = vnode.props || {}
  vnodeDOM(vnode.children, element)
  return [element]
}
const all = root => [root, ...root.children.flatMap(all)]
function invoke(handler, e) { if (Array.isArray(handler)) handler.forEach(fn => fn(e)); else handler?.(e) }

for (const [view, loop, store, list, edit] of cases) {
  test(`${view} ${loop.trim()} real template opens by click/Enter and isolates its action controls`, () => {
    const actions = []
    const context = {
      isCardEditEvent, [edit]: row => actions.push(['edit', row.id]),
      getCharacterName: id => `人物${id}`, hasRelations: () => false,
      confirmDelete: row => actions.push(['delete', row.id]), confirmDeleteGroup: row => actions.push(['delete', row.id]),
      manageTags: row => actions.push(['tags', row.id]), manageRelations: row => actions.push(['relations', row.id]),
      expandedGroups: new Set(), toggleGroupExpand: id => actions.push(['expand', id]),
      ...(store ? { [store]: { [list]: [item] } } : { [list]: [item] }),
    }
    const nodes = vnodeDOM(cardTemplate(view, loop)(context, []))
    const card = nodes.flatMap(all).find(node => 'data-editable-card' in node.attributes)
    assert.ok(card); assert.equal(card.attributes.role, 'group'); assert.equal(card.attributes.tabindex, '0')
    assert.match(card.attributes['aria-label'], /Enter/)
    const surface = card.children.find(node => node.tag !== 'button') || card
    invoke(card.props.onClick, event(card, surface))
    assert.deepEqual(actions.splice(0), [['edit', 7]])
    invoke(card.props.onKeydown, event(card, card, { type: 'keydown', key: 'Enter' }))
    assert.deepEqual(actions.splice(0), [['edit', 7]])
    for (const button of all(card).filter(node => node.tag === 'button')) {
      const e = event(card, button, { stopPropagation() {} })
      invoke(button.props.onClick, e)
      const ownActions = [...actions]
      invoke(card.props.onClick, e)
      assert.deepEqual(actions, ownActions, 'a nested action must not also edit the card')
      invoke(card.props.onKeydown, event(card, button, { type: 'keydown', key: 'Enter' }))
      assert.deepEqual(actions, ownActions, 'Enter on a nested action must not edit the card')
      actions.length = 0
    }
    card.ownerDocument.getSelection = () => ({ isCollapsed: false, anchorNode: surface, focusNode: surface, rangeCount: 0 })
    invoke(card.props.onClick, event(card, surface))
    assert.deepEqual(actions, [])
  })
}

test('map canvas, overview navigation and search links retain their original destinations', () => {
  const map = readFileSync(new URL('../src/views/MapView.vue', import.meta.url), 'utf8')
  assert.match(map, /@click="onCanvasClick"/)
  assert.doesNotMatch(map.match(/<canvas[\s\S]*?<\/canvas>/)[0], /isCardEditEvent|editable-card/)
  for (const view of ['OverviewView', 'SearchResultsView']) {
    const source = readFileSync(new URL(`../src/views/${view}.vue`, import.meta.url), 'utf8')
    assert.match(source, /RouterLink/)
    assert.doesNotMatch(source, /isCardEditEvent/)
  }
})
