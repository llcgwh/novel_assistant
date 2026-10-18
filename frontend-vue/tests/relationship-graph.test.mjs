import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources()
const viewportUtils = await load('utils/canvasViewport'), viewport = await load('composables/useCanvasViewport')
const studio = Vue.reactive({ theme: 'dark' })
function component(path, dependencies = {}) {
  const { descriptor } = parse(readFileSync(new URL(`../src/components/${path}.vue`, import.meta.url), 'utf8'))
  const compiled = compileScript(descriptor, { id: path.replaceAll('/', '-'), inlineTemplate: true })
  const code = ts.transpileModule(compiled.content, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText
  const module = { exports: {} }
  new Function('require', 'module', 'exports', code)(name => {
    if (name === 'vue') return Vue
    if (name === '@/utils/canvasViewport') return viewportUtils
    if (name === '@/composables/useCanvasViewport') return viewport
    if (name === '@/stores/studio') return { useStudioStore: () => studio }
    if (dependencies[name]) return dependencies[name]
    throw Error(`Unexpected component dependency: ${name}`)
  }, module, module.exports)
  return module.exports.default
}
const Toolbar = component('common/CanvasViewportToolbar')
const Graph = component('relationships/RelationshipGraph', { '@/components/common/CanvasViewportToolbar.vue': { default: Toolbar } })
const all = (node, predicate) => [...(predicate(node) ? [node] : []), ...node.children.flatMap(child => all(child, predicate))]
const textOf = node => node.text + node.children.map(textOf).join('')

async function mount(t) {
  const keys = ['window', 'document', 'getComputedStyle', 'ResizeObserver', 'requestAnimationFrame', 'cancelAnimationFrame']
  const previous = Object.fromEntries(keys.map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  const frames = new Map(), observers = [], drawing = [], events = [], captures = new Set()
  let serial = 0, bitmapWrites = 0, width = 800, height = 600
  const ctx = Object.fromEntries(['setTransform', 'clearRect', 'fillRect', 'beginPath', 'arc', 'fill', 'moveTo', 'lineTo', 'stroke', 'fillText', 'translate', 'scale'].map(name => [name, (...args) => drawing.push([name, ...args])]))
  const node = (type, text = '') => Vue.markRaw({
    type, text, children: [], props: {}, parent: null, style: {},
    get clientWidth() { return width }, get clientHeight() { return height },
    getBoundingClientRect: () => ({ left: 50, top: 100, width, height, right: width + 50, bottom: height + 100 }),
    getContext: () => ctx,
    focus() {}, setPointerCapture: id => captures.add(id), hasPointerCapture: id => captures.has(id), releasePointerCapture: id => captures.delete(id),
    get width() { return this.pixelWidth ?? 800 }, set width(value) { bitmapWrites++; this.pixelWidth = value },
    get height() { return this.pixelHeight ?? 600 }, set height(value) { bitmapWrites++; this.pixelHeight = value },
  })
  const globals = {
    window: { devicePixelRatio: 2 }, document: { documentElement: {} },
    getComputedStyle: () => ({ getPropertyValue: () => '#aabbcc' }),
    ResizeObserver: class { constructor(callback) { this.callback = callback; this.connected = false; observers.push(this) } observe() { this.connected = true } disconnect() { this.connected = false } },
    requestAnimationFrame: callback => { frames.set(++serial, callback); return serial }, cancelAnimationFrame: id => frames.delete(id),
  }
  for (const [key, value] of Object.entries(globals)) Object.defineProperty(globalThis, key, { configurable: true, value })
  const renderer = Vue.createRenderer({
    createElement: node, createText: text => node('#text', text), createComment: text => node('#comment', text),
    setText: (element, text) => { element.text = text }, setElementText: (element, text) => { element.text = text; element.children = [] },
    patchProp: (element, key, _old, value) => { element.props[key] = value; if (element.type === 'canvas' && (key === 'width' || key === 'height')) element[key] = value },
    parentNode: element => element.parent, nextSibling: element => element.parent?.children[element.parent.children.indexOf(element) + 1] ?? null,
    insert(element, parent, anchor = null) {
      if (element.parent) element.parent.children.splice(element.parent.children.indexOf(element), 1)
      element.parent = parent; const index = anchor ? parent.children.indexOf(anchor) : -1
      if (index < 0) parent.children.push(element); else parent.children.splice(index, 0, element)
    },
    remove(element) { if (element.parent) element.parent.children.splice(element.parent.children.indexOf(element), 1); element.parent = null },
  })
  const data = { characters: [1, 2, 3, 4].map(id => ({ id, name: `人物${id}` })), relationships: [{ id: 1, characterId1: 1, characterId2: 2, relationshipType: '朋友' }], groups: [{ id: 1, name: '同伴', characters: [{ id: 2 }] }] }
  const originalData = JSON.stringify(data)
  const root = node('root'), app = renderer.createApp(Graph, { ...data, onOpenCharacter: id => events.push(id) })
  app.mount(root)
  const flush = async () => { await Vue.nextTick(); while (frames.size) { const pending = [...frames.values()]; frames.clear(); for (const callback of pending) callback(); await Vue.nextTick() } }
  await flush()
  t.after(() => {
    app.unmount(); assert.equal(frames.size, 0); assert.equal(captures.size, 0); assert.ok(observers.every(observer => !observer.connected))
    assert.equal(JSON.stringify(data), originalData, 'view transforms must not write any character or relation data')
    for (const [key, value] of Object.entries(previous)) { if (value) Object.defineProperty(globalThis, key, value); else delete globalThis[key] }
  })
  const find = predicate => all(root, predicate)[0]
  const canvas = find(element => element.type === 'canvas')
  const event = (x, y, extra = {}) => ({ clientX: x + 50, clientY: y + 100, button: 0, pointerId: 1, pointerType: 'mouse', preventDefault() {}, ...extra })
  const dispatch = async (name, e) => { canvas.props[name]?.(e); await flush() }
  const button = label => find(element => element.type === 'button' && (element.props['aria-label'] === label || textOf(element) === label))
  const clickButton = async label => { button(label).props.onClick(); await flush() }
  return { canvas, ctx, drawing, events, captures, event, dispatch, find, button, clickButton, flush,
    get bitmapWrites() { return bitmapWrites }, async resize(w, h, ratio) { width = w; height = h; globals.window.devicePixelRatio = ratio; for (const observer of observers) observer.callback(); await flush() } }
}

test('graph zoom and drag keep node hit testing aligned and never resize the backing canvas during a gesture', async t => {
  const h = await mount(t)
  assert.equal(h.canvas.width, 1600); assert.equal(h.canvas.height, 1200)
  const writes = h.bitmapWrites
  await h.clickButton('放大画板')
  assert.ok(textOf(h.find(node => node.type === 'output')).includes('120%'))
  await h.dispatch('onClick', h.event(714.4, 300))
  assert.deepEqual(h.events, [2])
  await h.dispatch('onPointerdown', h.event(150, 200))
  await h.dispatch('onPointermove', h.event(190, 230))
  await h.dispatch('onPointerup', h.event(190, 230))
  await h.dispatch('onClick', h.event(754.4, 330))
  assert.deepEqual(h.events, [2], 'a pan must not open the node below its final click')
  await h.dispatch('onPointerdown', h.event(754.4, 330))
  await h.dispatch('onPointerup', h.event(754.4, 330))
  await h.dispatch('onClick', h.event(754.4, 330))
  assert.deepEqual(h.events, [2, 2])
  assert.equal(h.bitmapWrites, writes)
  assert.ok(h.drawing.some(call => call[0] === 'scale' && Math.abs(call[1] - 1.2) < 1e-8))
})

test('tooltips follow transformed nodes, hide during panning, and reset restores the original click target', async t => {
  const h = await mount(t)
  await h.clickButton('放大画板')
  await h.dispatch('onPointermove', h.event(714.4, 300))
  const tooltip = () => h.find(node => node.props.class === 'canvas-tooltip')
  assert.match(textOf(tooltip()), /人物2/); assert.match(textOf(tooltip()), /朋友/); assert.match(textOf(tooltip()), /同伴/)
  await h.dispatch('onPointerdown', h.event(200, 200)); await h.dispatch('onPointermove', h.event(250, 220))
  assert.equal(tooltip(), undefined)
  await h.dispatch('onPointerup', h.event(250, 220)); await h.dispatch('onClick', h.event(250, 220))
  await h.clickButton('复位视图')
  assert.ok(textOf(h.find(node => node.type === 'output')).includes('100%'))
  await h.dispatch('onClick', h.event(662, 300))
  assert.deepEqual(h.events, [2])
})

test('resize and higher pixel density update the bitmap and circular layout while CSS-space hits stay correct', async t => {
  const h = await mount(t)
  await h.resize(400, 300, 3)
  assert.equal(h.canvas.width, 1200); assert.equal(h.canvas.height, 900)
  await h.dispatch('onClick', h.event(312, 150))
  assert.deepEqual(h.events, [2])
  const writes = h.bitmapWrites
  await h.dispatch('onKeydown', { key: 'ArrowRight', preventDefault() {} })
  await h.dispatch('onClick', h.event(352, 150))
  assert.deepEqual(h.events, [2, 2])
  assert.equal(h.bitmapWrites, writes)
})

test('toolbar buttons expose bounded zoom and keyboard focus reaches the canvas', async t => {
  const h = await mount(t)
  assert.equal(h.canvas.props.tabindex, '0')
  assert.match(h.canvas.props['aria-label'], /方向键/)
  for (let i = 0; i < 20; i++) await h.clickButton('放大画板')
  assert.equal(h.button('放大画板').props.disabled, true)
  assert.match(textOf(h.find(node => node.type === 'output')), /300%/)
  for (let i = 0; i < 30; i++) await h.clickButton('缩小画板')
  assert.equal(h.button('缩小画板').props.disabled, true)
  assert.match(textOf(h.find(node => node.type === 'output')), /50%/)
  await h.dispatch('onKeydown', { key: 'Home', preventDefault() {} })
  assert.equal(h.button('缩小画板').props.disabled, false)
  assert.equal(h.button('放大画板').props.disabled, false)
})
