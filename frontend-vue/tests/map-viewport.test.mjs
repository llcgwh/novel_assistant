import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { computed, effectScope, nextTick, reactive, ref, watch } from 'vue'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources(), map = await load('utils/map'), geometry = await load('utils/canvasViewport')
const clone = value => JSON.parse(JSON.stringify(value))
const transpile = source => ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText.replace(/^import .* from .*\n/gm, '').replace(/^export /gm, '')
const viewSource = readFileSync(new URL('../src/views/MapView.vue', import.meta.url), 'utf8')
const viewJs = transpile(viewSource.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1])
const viewportJs = transpile(readFileSync(new URL('../src/composables/useCanvasViewport.ts', import.meta.url), 'utf8'))

// Execute the actual map and shared viewport setup. Only DOM canvas, image
// decoding and API stores are replaced, so clicks exercise the real handlers.
function harness({ cssWidth = 800, ratio = 1, locations = [] } = {}) {
  const scope = effectScope(), frames = new Map(), unmount = [], images = [], draws = [], transforms = [], arcs = [], captures = new Set()
  let nextFrame = 0
  const requestAnimationFrame = fn => { const id = ++nextFrame; frames.set(id, fn); return id }
  const cancelAnimationFrame = id => frames.delete(id)
  const lifecycle = { onMounted() {}, onBeforeUnmount: fn => unmount.push(fn) }
  const viewportRuntime = { ...geometry, reactive, ref, ...lifecycle, requestAnimationFrame, cancelAnimationFrame }
  const useCanvasViewport = new Function(...Object.keys(viewportRuntime), `${viewportJs}\nreturn useCanvasViewport;`)(...Object.values(viewportRuntime))
  const rect = { left: 20, top: 90, width: cssWidth, height: cssWidth * 0.75 }
  const context = {
    setTransform: (...args) => transforms.push(args), clearRect() {}, fillRect() {}, save() {}, restore() {}, translate: (...args) => transforms.push(['translate', ...args]), scale: (...args) => transforms.push(['scale', ...args]),
    beginPath() {}, rect() {}, clip() {}, arc: (...args) => arcs.push(args), fill() {}, stroke() {}, fillText() {}, measureText: text => ({ width: text.length * 8 }),
    drawImage: image => draws.push(image.src),
  }
  const canvas = { clientWidth: rect.width, clientHeight: rect.height, width: 800, height: 600, isConnected: true, getBoundingClientRect: () => rect, getContext: () => context, focus() {}, setPointerCapture: id => captures.add(id), hasPointerCapture: id => captures.has(id), releasePointerCapture: id => captures.delete(id) }
  const store = reactive({ locations: clone(locations), backgroundImageUrl: null, loading: false })
  const runtime = {
    ...map, ...lifecycle, ref, reactive, computed, watch, nextTick, useCanvasViewport,
    useRoute: () => ({ params: { novelId: '6' } }), useStudioStore: () => ({ theme: 'light' }), useMapStore: () => store,
    useFormSave: () => ({ reset() {} }), useTagsStore: () => ({ tags: [] }), useScenesStore: () => ({ scenes: [] }), useEditQuery() {},
    requestAnimationFrame, cancelAnimationFrame, window: { devicePixelRatio: ratio, addEventListener() {}, removeEventListener() {} }, document: { documentElement: {} },
    getComputedStyle: () => ({ getPropertyValue: () => '#888' }),
    Image: class { constructor() { images.push(this) } },
  }
  const view = scope.run(() => new Function(...Object.keys(runtime), `${viewJs}\nreturn { mapCanvas, viewport, drawMap, scheduleDraw, onCanvasClick, form, showCreateModal, editingLocation, closeModal };`)(...Object.values(runtime)))
  view.mapCanvas.value = canvas
  return { ...view, canvas, context, rect, store, images, draws, transforms, arcs, frames,
    flush() { let count = 0; while (frames.size) { assert.ok(++count < 20, 'Drawing must settle'); const pending = [...frames.values()]; frames.clear(); for (const fn of pending) fn() } },
    stop() { for (const fn of unmount) fn(); scope.stop() },
  }
}
const event = (clientX, clientY, extra = {}) => ({ clientX, clientY, button: 0, pointerId: 1, preventDefault() {}, ...extra })

test('map coordinates and marker hit testing remain in fixed map space at responsive sizes and zoom levels', () => {
  const locations = [{ id: 1, positionX: 400, positionY: 300 }, { id: 2, positionX: 0, positionY: 0 }, { id: 3, positionX: NaN, positionY: 20 }]
  for (const width of [320, 800, 1200]) for (const scale of [0.5, 1, 3]) {
    const viewport = { scale, x: 47, y: -31 }, display = { width, height: width * 0.75 }
    const worldCss = { x: 400 * width / 800, y: 300 * display.height / 600 }
    const screen = geometry.canvasScreenPoint(worldCss, viewport)
    const point = map.mapWorldPoint(geometry.canvasWorldPoint(screen, viewport), display)
    assert.deepEqual(point, { x: 400, y: 300 })
    const radius = 12 * 800 / (width * scale)
    assert.equal(map.mapMarkerAt(locations, { x: point.x + radius * 0.9, y: point.y }, radius).id, 1)
    assert.equal(map.mapMarkerAt(locations, { x: point.x + radius * 1.1, y: point.y }, radius), undefined)
  }
  assert.equal(map.mapMarkerAt(locations, { x: 0, y: 0 }, 10).id, 2)
  assert.equal(map.mapWorldPoint({ x: 1, y: 2 }, { width: 0, height: 0 }), null)
  assert.equal(map.mapContainsPoint({ x: -1, y: 5 }), false)
})

test('actual zoomed map clicks create inverse-transformed coordinates and existing markers open editing', () => {
  for (const cssWidth of [320, 800]) {
    const h = harness({ cssWidth, ratio: 2, locations: [{ id: 7, name: '城门', positionX: 400, positionY: 300 }] })
    try {
      const original = clone(h.store.locations)
      Object.assign(h.viewport.state, { scale: 2, x: -90, y: -60 })
      const at = (x, y) => event(h.rect.left + x * cssWidth / 800 * 2 - 90, h.rect.top + y * cssWidth / 800 * 2 - 60)
      h.onCanvasClick(at(400, 300))
      assert.equal(h.editingLocation.value.id, 7); assert.equal(h.showCreateModal.value, false)
      h.closeModal(); h.onCanvasClick(at(250, 190))
      assert.equal(h.showCreateModal.value, true); assert.equal(h.form.positionX, 250); assert.equal(h.form.positionY, 190)
      h.closeModal(); h.onCanvasClick(at(-10, 190)); assert.equal(h.showCreateModal.value, false)
      assert.deepEqual(h.store.locations, original)
    } finally { h.stop() }
  }
})

test('mouse and touch panning never create locations at drag end but ordinary taps still create', () => {
  for (const pointerType of ['mouse', 'touch']) {
    const h = harness()
    try {
      const start = event(120, 190, { pointerType }), end = event(180, 230, { pointerType })
      h.viewport.onPointerDown(start); h.viewport.onPointerMove(end); h.viewport.onPointerUp(end); h.onCanvasClick(end)
      assert.equal(h.showCreateModal.value, false); assert.deepEqual(h.viewport.state, { scale: 1, x: 60, y: 40 })
      h.viewport.onPointerDown(end); h.viewport.onPointerUp(end); h.onCanvasClick(end)
      assert.equal(h.showCreateModal.value, true); assert.equal(h.form.positionX, 100); assert.equal(h.form.positionY, 100)
    } finally { h.stop() }
  }
})

test('map rendering applies backing-pixel scale once and shares pan/zoom for background and markers', () => {
  const h = harness({ cssWidth: 400, ratio: 2, locations: [{ id: 1, name: '港口', positionX: 300, positionY: 200 }] })
  try {
    Object.assign(h.viewport.state, { scale: 2, x: 35, y: -25 }); h.drawMap()
    assert.equal(h.canvas.width, 800); assert.equal(h.canvas.height, 600)
    assert.deepEqual(h.transforms.slice(0, 3), [[2, 0, 0, 2, 0, 0], ['translate', 35, -25], ['scale', 1, 1]])
    assert.ok(h.arcs.some(([x, y, radius]) => x === 300 && y === 200 && radius === 5), 'Marker remains 5 CSS pixels at this combined scale')
    h.canvas.clientWidth = h.rect.width = 320; h.canvas.clientHeight = h.rect.height = 240; h.drawMap()
    assert.equal(h.canvas.width, 640); assert.equal(h.canvas.height, 480)
    assert.deepEqual(h.store.locations[0], { id: 1, name: '港口', positionX: 300, positionY: 200 })
  } finally { h.stop() }
})

test('background images are cached across pan/zoom and late older loads cannot draw over the current map', async () => {
  const h = harness()
  try {
    h.store.backgroundImageUrl = 'old-map'; h.drawMap(); assert.equal(h.images.length, 1)
    h.viewport.zoomBy(1.2); h.flush(); assert.equal(h.images.length, 1)
    h.store.backgroundImageUrl = 'new-map'; h.drawMap(); assert.equal(h.images.length, 2)
    h.images[1].onload(); h.flush(); assert.deepEqual(h.draws, ['new-map'])
    const draws = h.draws.length
    h.images[0].onload(); h.flush(); assert.equal(h.draws.length, draws)
    h.viewport.state.x = 80; h.drawMap(); assert.equal(h.images.length, 2); assert.equal(h.draws.at(-1), 'new-map')
    h.store.backgroundImageUrl = null; h.drawMap(); assert.equal(h.draws.at(-1), 'new-map')
    h.store.backgroundImageUrl = 'old-map'; h.drawMap(); assert.equal(h.images.length, 2); assert.equal(h.draws.at(-1), 'old-map')
    await nextTick(); h.flush()
  } finally { h.stop() }
  assert.equal(h.images[0].onload, null); assert.equal(h.images[1].onload, null)
})
