import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources(), select = await load('utils/select'), { useDialog } = await load('composables/useDialog')
const source = readFileSync(new URL('../src/components/common/BaseSelect.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
const compiled = compileScript(descriptor, { id: 'select-regression', inlineTemplate: true })
const code = ts.transpileModule(compiled.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
const module = { exports: {} }
new Function('require', 'module', 'exports', code)(name => {
  if (name === 'vue') return Vue
  if (name === '@/utils/select') return select
  throw Error(`Unexpected component dependency: ${name}`)
}, module, module.exports)
const BaseSelect = module.exports.default
const settle = async () => { await Vue.nextTick(); await Vue.nextTick() }
const all = (node, predicate) => [...(predicate(node) ? [node] : []), ...node.children.flatMap(child => all(child, predicate))]
const textOf = node => node.text + node.children.map(textOf).join('')

class Events {
  listeners = new Map()
  addEventListener(name, callback) { if (!this.listeners.has(name)) this.listeners.set(name, new Set()); this.listeners.get(name).add(callback) }
  removeEventListener(name, callback) { this.listeners.get(name)?.delete(callback) }
  emit(name, event) { for (const callback of [...(this.listeners.get(name) ?? [])]) callback(event) }
  count() { return [...this.listeners.values()].reduce((sum, list) => sum + list.size, 0) }
}

// Use the real compiled template, Teleport, setup and parent-dialog keyboard
// handler. The renderer supplies only DOM primitives, not selection behavior.
async function mount(t, options = {}) {
  const previous = Object.fromEntries(['document', 'window', 'innerWidth', 'innerHeight'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  const doc = new Events(), win = Object.assign(new Events(), { innerWidth: options.width ?? 1024, innerHeight: options.height ?? 768 })
  const events = [], parentCloses = [], nativeCancels = []
  let body, appElement
  let rect = options.rect ?? { left: 110, top: 140, bottom: 180, width: 240 }
  const node = (type, text = '') => Vue.markRaw({
    type, text, children: [], props: {}, parent: null, style: {}, inert: false,
    get isConnected() { return this === body || !!this.parent?.isConnected },
    contains(other) { return this === other || this.children.some(child => child.contains(other)) },
    closest(selector) {
      for (let current = this; current; current = current.parent) {
        if (selector === 'dialog' && current.type === 'dialog') return current
        if (selector.includes('[inert]') && (current.inert || current.props.inert)) return current
        if (selector.includes('[disabled]') && current.props.disabled) return current
        if (selector.includes('[role="combobox"]') && current.props.role === 'combobox' && current.props['aria-expanded']) return current
      }
      return null
    },
    matches(selector) {
      if (selector !== ':disabled') throw Error(`Unsupported match: ${selector}`)
      if (this.props.disabled) return true
      for (let current = this.parent; current; current = current.parent) if (current.type === 'fieldset' && current.props.disabled) return true
      return false
    },
    getBoundingClientRect() { return rect },
    getClientRects() { return this.isConnected ? [{}] : [] },
    querySelector(selector) { const id = selector.match(/^\[id="(.+)"\]$/)?.[1]; return all(this, child => child.props.id === id)[0] },
    querySelectorAll() { return all(this, child => ['button', 'input', 'select', 'textarea'].includes(child.type) && !child.matches(':disabled')) },
    scrollIntoView() { this.scrolled = true },
    focus() { doc.activeElement = this },
  })
  body = node('body'); body.style.overflow = 'auto'; appElement = node('app'); const root = node('root')
  body.children.push(appElement); appElement.parent = body; appElement.children.push(root); root.parent = appElement
  Object.assign(doc, { body, activeElement: body, getElementById: () => appElement })
  for (const [key, value] of Object.entries({ document: doc, window: win, innerWidth: win.innerWidth, innerHeight: win.innerHeight })) Object.defineProperty(globalThis, key, { configurable: true, value })
  const renderer = Vue.createRenderer({
    createElement: node, createText: text => node('#text', text), createComment: text => node('#comment', text),
    setText: (element, value) => { element.text = value }, setElementText: (element, value) => { element.text = value; element.children = [] },
    patchProp: (element, key, _old, value) => { element.props[key] = value },
    parentNode: element => element.parent, nextSibling: element => element.parent?.children[element.parent.children.indexOf(element) + 1] ?? null,
    querySelector: selector => selector === 'body' ? body : null,
    insert(element, parent, anchor = null) {
      if (element.parent) element.parent.children.splice(element.parent.children.indexOf(element), 1)
      element.parent = parent; const index = anchor ? parent.children.indexOf(anchor) : -1
      if (index < 0) parent.children.push(element); else parent.children.splice(index, 0, element)
    },
    remove(element) { if (element.parent) element.parent.children.splice(element.parent.children.indexOf(element), 1); element.parent = null },
  })
  const state = Vue.reactive({ value: null, disabled: false, fieldsetDisabled: false,
    options: [{ value: 7, label: '第七章' }, { value: '7', label: '文字编号七' }, { value: 8, label: '不可选', disabled: true }, { value: 9, label: '第九章' }], ...options.state })
  const Parent = Vue.defineComponent({
    setup() {
      const dialog = Vue.ref(null)
      if (options.native) useDialog(dialog, () => state.fieldsetDisabled, () => parentCloses.push('close'))
      return () => {
        const fieldset = Vue.h('fieldset', { disabled: state.fieldsetDisabled }, [Vue.h(BaseSelect, {
          modelValue: state.value, options: state.options, disabled: state.disabled, placeholder: '请选择章节',
          'onUpdate:modelValue': value => { state.value = value; events.push(['update', value]) },
          onChange: value => events.push(['change', value]),
        })])
        return options.native ? Vue.h(Vue.Teleport, { to: 'body' }, Vue.h('dialog', { ref: dialog, onCancel: () => nativeCancels.push('cancel') }, [fieldset])) : fieldset
      }
    },
  })
  const app = renderer.createApp(Parent); app.mount(root); await settle()
  t.after(() => {
    app.unmount(); assert.equal(doc.count() + win.count(), 0)
    for (const [key, value] of Object.entries(previous)) { if (value) Object.defineProperty(globalThis, key, value); else delete globalThis[key] }
  })
  const find = predicate => all(body, predicate)[0]
  const trigger = () => find(element => element.props.role === 'combobox')
  const menu = () => find(element => element.props.role === 'listbox')
  const click = async element => { element.props.onClick?.({ target: element, currentTarget: element }); await settle() }
  const key = async name => {
    const event = { key: name, target: trigger(), prevented: false, stopped: false,
      preventDefault() { this.prevented = true }, stopPropagation() { this.stopped = true } }
    doc.emit('keydown', event)
    if (!event.stopped) trigger().props.onKeydown(event)
    if (name === 'Escape' && options.native && !event.prevented) find(element => element.type === 'dialog').props.onCancel(event)
    await settle(); return event
  }
  return { state, events, doc, win, body, find, trigger, menu, click, key, parentCloses, nativeCancels, setRect: value => { rect = value } }
}

for (const native of [false, true]) {
  test(`the rendered menu belongs to ${native ? 'its native dialog top layer' : 'the document body'}`, async t => {
    const h = await mount(t, { native })
    assert.equal(h.state.value, null)
    assert.match(textOf(h.trigger()), /请选择章节/)
    await h.click(h.trigger())
    assert.equal(h.menu().parent, native ? h.find(node => node.type === 'dialog') : h.body)
    assert.equal(h.trigger().props['aria-controls'], h.menu().props.id)
    assert.equal(h.state.value, null, 'opening a nullable select must not choose the first option')
    assert.deepEqual(h.events, [])
  })
}

for (const disabledMode of ['disabled', 'fieldsetDisabled']) {
  test(`${disabledMode} prevents click and keyboard paths from opening or emitting a selection`, async t => {
    const h = await mount(t, { native: true, state: { [disabledMode]: true } })
    await h.click(h.trigger())
    for (const key of ['ArrowDown', 'Home', 'End', 'Enter', ' ', 'a']) await h.key(key)
    assert.equal(h.menu(), undefined)
    assert.deepEqual(h.events, [])
    assert.equal(h.trigger().props['aria-expanded'], false)
  })
}

test('Escape closes the select before either the shared parent handler or native dialog cancellation', async t => {
  const h = await mount(t, { native: true })
  await h.key('ArrowDown')
  const first = await h.key('Escape')
  assert.equal(first.prevented, true); assert.equal(first.stopped, true)
  assert.equal(h.menu(), undefined)
  assert.deepEqual(h.parentCloses, []); assert.deepEqual(h.nativeCancels, [])
  assert.equal(h.state.value, null)
  await h.key('Escape')
  assert.deepEqual(h.parentCloses, ['close'])
  assert.deepEqual(h.nativeCancels, [])
})

test('choosing keeps number, string, and runtime null values distinct and skips disabled options', async t => {
  const h = await mount(t, { state: { options: [{ value: null, label: '无关联' }, { value: 7, label: '数字' }, { value: '7', label: '文字' }, { value: 8, label: '禁用', disabled: true }] } })
  for (const [label, value] of [['数字', 7], ['文字', '7'], ['无关联', null]]) {
    await h.click(h.trigger())
    const selected = h.find(node => node.props.role === 'option' && node.props['aria-selected'])
    assert.ok(selected)
    await h.click(h.find(node => node.props.role === 'option' && textOf(node).includes('禁用')))
    assert.ok(h.menu(), 'a disabled row must not choose or close')
    await h.click(h.find(node => node.props.role === 'option' && textOf(node).includes(label)))
    assert.equal(h.state.value, value)
    assert.deepEqual(h.events.slice(-2), [['update', value], ['change', value]])
    assert.equal(h.menu(), undefined)
    assert.ok(textOf(h.trigger()).includes(label))
  }
})

test('keyboard choice respects strict model identity and retains numeric values', async t => {
  const h = await mount(t, { state: { value: '7' } })
  await h.key('ArrowDown')
  assert.ok(h.trigger().props['aria-activedescendant'].endsWith('-1'))
  await h.key('ArrowDown')
  assert.ok(h.trigger().props['aria-activedescendant'].endsWith('-3'))
  await h.key('Enter')
  assert.equal(h.state.value, 9)
  assert.deepEqual(h.events, [['update', 9], ['change', 9]])
})

test('native-dialog menus use viewport coordinates and reposition when their trigger scrolls', async t => {
  const h = await mount(t, { native: true, width: 390, height: 700, rect: { left: 220, top: 610, bottom: 650, width: 150 } })
  await h.click(h.trigger())
  const first = h.menu().props.style
  assert.equal(parseFloat(first.left) + parseFloat(first.width), 378)
  assert.equal(parseFloat(first.top) + parseFloat(first.maxHeight), 604)
  assert.equal(parseFloat(first.width), 184)
  h.setRect({ left: 24, top: 200, bottom: 240, width: 280 })
  h.doc.emit('scroll', { target: h.find(node => node.type === 'dialog') }); await settle()
  assert.equal(h.menu().props.style.left, '24px')
  assert.equal(h.menu().props.style.top, '246px')
  assert.equal(h.menu().props.style.width, '280px')
  h.setRect({ left: 24, top: -70, bottom: -30, width: 280 })
  h.doc.emit('scroll', { target: h.find(node => node.type === 'dialog') }); await settle()
  assert.equal(h.menu(), undefined, 'scrolling the trigger out of view closes its menu')
})

test('inside pointer activity keeps the menu open; outside pointer and viewport changes cleanly dismiss it', async t => {
  const h = await mount(t, { native: true })
  await h.click(h.trigger())
  h.doc.emit('pointerdown', { target: h.menu().children.find(node => node.props.role === 'option') }); await settle()
  assert.ok(h.menu())
  h.doc.emit('pointerdown', { target: h.body }); await settle()
  assert.equal(h.menu(), undefined)
  await h.click(h.trigger()); h.win.emit('resize', {}); await settle()
  assert.equal(h.menu(), undefined)
  assert.deepEqual(h.events, [])
})
