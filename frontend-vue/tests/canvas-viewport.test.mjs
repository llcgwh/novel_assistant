import test from 'node:test'
import assert from 'node:assert/strict'
import * as Vue from 'vue'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources()
const { canvasWorldPoint, canvasScreenPoint, zoomCanvasViewport, canvasWheelFactor } = await load('utils/canvasViewport')
const { useCanvasViewport } = await load('composables/useCanvasViewport')
const close = (a, b) => assert.ok(Math.abs(a - b) < 1e-8, `${a} != ${b}`)

test('zoom preserves the world point beneath the pointer, including scale bounds', () => {
  const original = { scale: 1.3, x: 17.5, y: -42 }, anchor = { x: 291, y: 144 }
  const point = canvasWorldPoint(anchor, original)
  for (const desired of [0.01, 0.9, 2.25, 10]) {
    const next = zoomCanvasViewport(original, desired, anchor)
    const after = canvasScreenPoint(point, next)
    close(after.x, anchor.x); close(after.y, anchor.y)
    assert.ok(next.scale >= 0.5 && next.scale <= 3)
  }
  assert.deepEqual(original, { scale: 1.3, x: 17.5, y: -42 })
})

test('view coordinate inversion is independent of pixel density and does not round source positions', () => {
  const point = { x: 614.375, y: 192.25 }, viewport = { scale: 2.7, x: -171.125, y: 86.5 }
  const roundTrip = canvasWorldPoint(canvasScreenPoint(point, viewport), viewport)
  close(roundTrip.x, point.x); close(roundTrip.y, point.y)
  close(canvasWheelFactor(16, 0, 600), canvasWheelFactor(1, 1, 600))
  close(canvasWheelFactor(120, 0, 600), canvasWheelFactor(0.2, 2, 600))
})

function harness(t) {
  const previous = Object.fromEntries(['requestAnimationFrame', 'cancelAnimationFrame'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  let serial = 0, draws = 0, focused = 0, viewport
  const frames = new Map(), captured = new Set()
  Object.defineProperty(globalThis, 'requestAnimationFrame', { configurable: true, value: fn => { frames.set(++serial, fn); return serial } })
  Object.defineProperty(globalThis, 'cancelAnimationFrame', { configurable: true, value: id => frames.delete(id) })
  const canvas = Vue.markRaw({ clientHeight: 600, getBoundingClientRect: () => ({ left: 80, top: 40, width: 800, height: 600 }),
    focus() { focused++ }, setPointerCapture: id => captured.add(id), hasPointerCapture: id => captured.has(id), releasePointerCapture: id => captured.delete(id) })
  const renderer = Vue.createRenderer({
    createComment: () => ({}), createText: () => ({}), createElement: () => ({}),
    insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode() {}, nextSibling() {},
  })
  const app = renderer.createApp({ setup() { viewport = useCanvasViewport(Vue.ref(canvas), () => draws++); return () => null } })
  app.mount({})
  let unmounted = false
  const unmount = () => { if (!unmounted) { app.unmount(); unmounted = true } }
  t.after(() => {
    unmount()
    assert.equal(frames.size, 0); assert.equal(captured.size, 0)
    for (const [key, value] of Object.entries(previous)) { if (value) Object.defineProperty(globalThis, key, value); else delete globalThis[key] }
  })
  const event = (x = 200, y = 200, id = 1) => ({ clientX: x, clientY: y, pointerId: id, pointerType: 'mouse', button: 0, prevented: false, preventDefault() { this.prevented = true } })
  const flush = () => { const pending = [...frames.values()]; frames.clear(); for (const fn of pending) fn() }
  return { viewport, event, captured, frames, flush, unmount, get draws() { return draws }, get focused() { return focused } }
}

test('pointer capture pans only after a CSS-pixel threshold and consumes the following click once', t => {
  const h = harness(t), v = h.viewport
  v.onPointerDown(h.event())
  assert.deepEqual([...h.captured], [1]); assert.equal(h.focused, 1)
  v.onPointerMove(h.event(203, 203))
  assert.deepEqual({ ...v.state }, { scale: 1, x: 0, y: 0 })
  assert.equal(v.dragging.value, false)
  v.onPointerMove(h.event(220, 230))
  assert.deepEqual({ ...v.state }, { scale: 1, x: 20, y: 30 })
  assert.equal(v.dragging.value, true)
  v.onPointerUp(h.event(220, 230))
  assert.equal(v.dragging.value, false); assert.equal(h.captured.size, 0)
  assert.equal(v.consumeClick(), true); assert.equal(v.consumeClick(), false)
  h.flush(); assert.equal(h.draws, 1)
})

test('stationary taps remain clickable and an unrelated pointer cannot move or end a gesture', t => {
  const h = harness(t), v = h.viewport
  v.onPointerDown(h.event())
  v.onPointerMove(h.event(240, 230, 2)); v.onPointerUp(h.event(240, 230, 2))
  assert.equal(h.captured.size, 1); assert.equal(v.state.x, 0)
  v.onPointerMove(h.event(202, 203)); v.onPointerUp(h.event(202, 203))
  assert.equal(v.consumeClick(), false)
  assert.equal(h.frames.size, 0)
})

for (const method of ['onPointerCancel', 'onLostPointerCapture']) {
  test(`${method} releases capture and cannot turn an interrupted gesture into a business click`, t => {
    const h = harness(t), v = h.viewport
    v.onPointerDown(h.event()); v.onPointerMove(h.event(230, 240)); v[method](h.event(230, 240))
    assert.equal(h.captured.size, 0); assert.equal(v.dragging.value, false)
    assert.equal(v.consumeClick(), true)
    v.onPointerDown(h.event(230, 240)); v.onPointerUp(h.event(230, 240))
    assert.equal(v.consumeClick(), false, 'a new tap must not inherit a previous gesture guard')
  })
}

test('wheel zoom anchors at the actual client position and toolbar zoom anchors at the visible center', t => {
  const h = harness(t), v = h.viewport
  const pointer = { ...h.event(330, 240), deltaY: -80, deltaMode: 0 }
  const before = v.toWorld(pointer)
  v.onWheel(pointer)
  assert.equal(pointer.prevented, true)
  const after = v.toWorld(pointer)
  close(before.x, after.x); close(before.y, after.y)
  const center = { clientX: 480, clientY: 340 }, centerBefore = v.toWorld(center)
  v.zoomBy(1.2)
  const centerAfter = v.toWorld(center)
  close(centerBefore.x, centerAfter.x); close(centerBefore.y, centerAfter.y)
  h.flush(); assert.equal(h.draws, 1)
})

test('keyboard panning, zoom and reset are available without intercepting browser shortcuts', t => {
  const h = harness(t), v = h.viewport
  const key = (key, extras = {}) => ({ key, ...extras, prevented: false, preventDefault() { this.prevented = true } })
  for (const event of [key('ArrowRight'), key('ArrowDown', { shiftKey: true })]) { v.onKeydown(event); assert.equal(event.prevented, true) }
  assert.equal(v.state.x, 40); assert.equal(v.state.y, 100)
  v.onKeydown(key('+')); close(v.state.scale, 1.2)
  const browserZoom = key('+', { ctrlKey: true }); v.onKeydown(browserZoom)
  assert.equal(browserZoom.prevented, false); close(v.state.scale, 1.2)
  v.onKeydown(key('Home'))
  assert.deepEqual({ ...v.state }, { scale: 1, x: 0, y: 0 })
})

test('many pointer moves are drawn once per frame and unmount cancels queued work and capture', t => {
  const h = harness(t), v = h.viewport
  v.onPointerDown(h.event())
  for (let i = 1; i <= 100; i++) v.onPointerMove(h.event(200 + i, 200 + i))
  assert.equal(h.frames.size, 1); h.flush(); assert.equal(h.draws, 1)
  v.onPointerMove(h.event(350, 350)); h.unmount()
  assert.equal(h.frames.size, 0); assert.equal(h.captured.size, 0)
  h.flush(); assert.equal(h.draws, 1)
})
