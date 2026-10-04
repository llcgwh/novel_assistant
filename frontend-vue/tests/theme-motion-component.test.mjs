import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { effectScope, markRaw, nextTick, reactive, ref, watch } from 'vue'
import { compileSources } from './helpers/load-source.mjs'

const { createThemeTransition, resolveThemeOrigin } = await compileSources()('utils/themeTransition')
const flush = async () => { for (let i = 0; i < 24; i++) await Promise.resolve() }
const turn = () => new Promise(resolve => setImmediate(resolve))
const source = readFileSync(new URL('../src/components/studio/ThemeTransition.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
const parsed = ts.createSourceFile('ThemeTransition.ts', script, ts.ScriptTarget.ES2022, true, ts.ScriptKind.TS)
// Execute the real setup script; remove import declarations with the TS parser, including multiline imports.
const body = ts.createPrinter().printFile(ts.factory.updateSourceFile(parsed, parsed.statements.filter(statement => !ts.isImportDeclaration(statement))))
const compiled = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText

class EventHub {
  listeners = new Map()
  addEventListener(name, callback) {
    if (!this.listeners.has(name)) this.listeners.set(name, new Set())
    this.listeners.get(name).add(callback)
  }
  removeEventListener(name, callback) { this.listeners.get(name)?.delete(callback) }
  emit(name, properties = {}) {
    const event = { type: name, prevented: false, stopped: false, preventDefault() { this.prevented = true }, stopPropagation() { this.stopped = true }, ...properties }
    for (const callback of [...(this.listeners.get(name) ?? [])]) callback(event)
    return event
  }
  count() { return [...this.listeners.values()].reduce((total, list) => total + list.size, 0) }
}

function harness(t, options = {}) {
  let now = 0, timerId = 0, animateCalls = 0, modal = !!options.modal, unmounted = false, registration = null
  const timers = new Map(), animations = [], commits = [], observers = [], mounted = [], beforeUnmount = [], probes = new Set()
  function schedule(fn, delay) { const id = ++timerId; timers.set(id, { at: now + delay, fn }); return id }
  const clear = id => timers.delete(id)
  async function advance(ms) {
    const end = now + ms
    await flush()
    while (true) {
      const next = [...timers].filter(([, task]) => task.at <= end).sort((a, b) => a[1].at - b[1].at)[0]
      if (!next) break
      now = next[1].at; timers.delete(next[0]); next[1].fn(); await flush()
    }
    now = end
    await flush()
  }
  class FakeElement {
    constructor(name) { this.name = name; this.style = {}; markRaw(this) }
    remove() { probes.delete(this) }
    animate(keyframes, settings) {
      animateCalls++
      if (animateCalls === options.throwAt) throw Error('animate synchronously failed')
      let resolve, reject
      const finished = options.missingFinished ? undefined : new Promise((yes, no) => { resolve = yes; reject = no })
      const animation = { name: this.name, keyframes, settings, start: now, finished, canceled: false,
        resolve, reject,
        cancel() { this.canceled = true; clear(this.timer); reject?.(Error('AbortError: animation canceled')) },
      }
      if (options.missingFinished) delete animation.finished
      else if (!options.neverFinish) animation.timer = schedule(resolve, settings.duration)
      animations.push(animation)
      return animation
    }
  }
  if (options.noWaapi) FakeElement.prototype.animate = undefined
  const media = new EventHub()
  media.matches = !!options.reduced
  if (options.legacyMedia) {
    media.addListener = callback => EventHub.prototype.addEventListener.call(media, 'change', callback)
    media.removeListener = callback => EventHub.prototype.removeEventListener.call(media, 'change', callback)
    media.addEventListener = undefined; media.removeEventListener = undefined
  }
  const viewport = new EventHub(), win = new EventHub(), doc = new EventHub()
  win.visualViewport = viewport
  doc.hidden = false
  doc.querySelector = () => modal ? {} : null
  doc.createElement = name => new FakeElement(name)
  doc.body = { appendChild: element => probes.add(element) }
  class Observer {
    constructor(callback) { this.callback = callback; this.connected = false; observers.push(this) }
    observe() { this.connected = true }
    disconnect() { this.connected = false }
  }
  const route = reactive({ fullPath: '/novels/1/chapters' })
  const studio = reactive({
    spatialMotion: options.spatialMotion !== false, theme: 'dark',
    commitTheme(target) { commits.push({ at: now, target }); studio.theme = target },
    registerThemeTransition(run, finish) { registration = { run, finish }; return () => { registration = null } },
  })
  const runtime = {
    ref, watch, nextTick, useId: () => ':test:1', useRoute: () => route, useStudioStore: () => studio,
    onMounted: callback => mounted.push(callback), onBeforeUnmount: callback => beforeUnmount.push(callback),
    createThemeTransition, resolveThemeOrigin, window: win, document: doc, Element: FakeElement,
    innerWidth: 1000, innerHeight: 600, setTimeout: schedule, clearTimeout: clear,
    matchMedia: options.noMatchMedia ? undefined : () => { if (options.throwMedia) throw Error('media unavailable'); return media },
    CSS: options.noCss ? undefined : { supports: () => { if (options.throwCss) throw Error('CSS unavailable'); return !options.noClip } },
    MutationObserver: options.noObserver ? undefined : Observer,
    getComputedStyle: element => ({ backgroundColor: element.style.backgroundColor ? '#f4f1e9' : '#171915', color: element.style.color ? '#292b23' : '#e4e7d8' }),
  }
  const scope = effectScope()
  const h = scope.run(() => new Function(...Object.keys(runtime), compiled + '; return { transition, frame, phase, surface, icon, cutout, rays, enabled, runThemeTransition, finish }')(...Object.values(runtime)))
  for (const name of ['surface', 'icon', 'cutout', 'rays']) h[name].value = new FakeElement(name)
  for (const callback of mounted) callback()
  async function unmount() {
    if (unmounted) return
    unmounted = true
    for (const callback of beforeUnmount) callback()
    scope.stop()
    await flush()
  }
  t.after(async () => { await unmount(); await turn() })
  const clean = () => {
    assert.equal(h.transition.busy, false)
    assert.equal(h.frame.value, null)
    assert.equal(timers.size, 0)
    assert.equal(win.count() + doc.count() + viewport.count() + media.count(), 0)
    assert.ok(observers.every(observer => !observer.connected))
    assert.ok(animations.every(animation => animation.canceled))
    assert.equal(probes.size, 0)
  }
  return { ...h, studio, route, media, doc, win, viewport, animations, commits, timers, observers, advance, unmount, clean,
    openModal() { modal = true; for (const observer of observers) if (observer.connected) observer.callback() },
    get registration() { return registration }, get now() { return now },
  }
}

for (const [from, to] of [['dark', 'light'], ['light', 'dark']]) {
  test(`real component ${from} to ${to} animates all layers for 980ms then removes every transient resource`, async t => {
    const h = harness(t)
    const pending = h.runThemeTransition(from, to)
    await h.advance(0)
    assert.equal(h.frame.value.from, from)
    assert.equal(h.frame.value.to, to)
    assert.equal(h.phase.value, 'cover')
    await h.advance(400)
    assert.equal(h.phase.value, 'morph')
    const cutout = h.animations.find(animation => animation.name === 'cutout')
    assert.equal(cutout.keyframes[0].transform, from === 'dark' ? 'translate(0px, 0px)' : 'translate(32px, -24px)')
    assert.equal(cutout.keyframes[1].transform, to === 'dark' ? 'translate(0px, 0px)' : 'translate(32px, -24px)')
    await h.advance(380)
    assert.deepEqual(h.commits, [{ at: 780, target: to }])
    assert.equal(h.phase.value, 'fade')
    await h.advance(199)
    assert.equal(h.transition.busy, true)
    await h.advance(1)
    assert.equal(await pending, true)
    assert.deepEqual(h.animations.map(animation => [animation.start, animation.settings.duration]), [[0, 260], [0, 260], [260, 140], [400, 380], [400, 380], [400, 380], [400, 380], [780, 200]])
    h.clean()
    await h.unmount()
    assert.equal(h.registration, null)
  })
}

for (const reason of ['noWaapi', 'noMatchMedia', 'throwMedia', 'noCss', 'noClip', 'throwCss', 'noObserver', 'reduced', 'modal', 'spatialOff']) {
  test(`${reason} falls back to the target without showing the component or starting an animation`, async t => {
    const h = harness(t, reason === 'spatialOff' ? { spatialMotion: false } : { [reason]: true })
    assert.equal(await h.runThemeTransition('dark', 'light'), true)
    assert.deepEqual(h.commits, [{ at: 0, target: 'light' }])
    assert.equal(h.animations.length, 0)
    h.clean()
  })
}

for (const [reason, interrupt] of [
  ['Escape', h => h.doc.emit('keydown', { key: 'Escape' })],
  ['resize', h => h.win.emit('resize')],
  ['orientation', h => h.win.emit('orientationchange')],
  ['visual viewport resize', h => h.viewport.emit('resize')],
  ['hidden page', h => { h.doc.hidden = true; h.doc.emit('visibilitychange') }],
  ['reduced motion change', h => { h.media.matches = true; h.media.emit('change') }],
  ['spatial preference off', h => { h.studio.spatialMotion = false }],
  ['route change', h => { h.route.fullPath = '/novels/1/characters' }],
  ['modal opened', h => h.openModal()],
  ['component unmount', h => h.unmount()],
]) {
  test(`${reason} finishes an in-flight component with its target and consumes canceled WAAPI promises`, { timeout: 1500 }, async t => {
    const h = harness(t)
    const pending = h.runThemeTransition('dark', 'light')
    await h.advance(450)
    await interrupt(h)
    await flush()
    assert.equal(await pending, true)
    await turn()
    assert.deepEqual(h.commits, [{ at: 450, target: 'light' }])
    h.clean()
  })
}

test('unrelated keys and a visible-page event preserve the animation, while Escape is consumed', async t => {
  const h = harness(t)
  const pending = h.runThemeTransition('dark', 'light')
  await h.advance(0)
  assert.equal(h.doc.emit('keydown', { key: 'Enter' }).prevented, false)
  h.doc.emit('visibilitychange')
  assert.equal(h.transition.busy, true)
  const event = h.doc.emit('keydown', { key: 'Escape' })
  assert.equal(event.prevented, true)
  assert.equal(event.stopped, true)
  await pending
  h.clean()
})

test('legacy reduced-motion listeners are removed with their original callback after cancellation', async t => {
  const h = harness(t, { legacyMedia: true })
  const pending = h.runThemeTransition('dark', 'light')
  await h.advance(0)
  assert.equal(h.media.count(), 1)
  h.media.matches = true
  h.media.emit('change')
  await pending
  h.clean()
})

for (const throwAt of [2, 5]) {
  test(`a synchronous failure creating animation ${throwAt} cancels earlier effects without leaking their rejections`, async t => {
    const h = harness(t, { throwAt })
    const pending = h.runThemeTransition('dark', 'light')
    await h.advance(500)
    assert.equal(await pending, true)
    await turn()
    assert.equal(h.commits.length, 1)
    assert.equal(h.studio.theme, 'light')
    h.clean()
  })
}

test('a spontaneously rejected WAAPI finished promise finishes the target immediately', async t => {
  const h = harness(t)
  const pending = h.runThemeTransition('dark', 'light')
  await h.advance(0)
  h.animations[0].reject(Error('browser canceled the effect'))
  assert.equal(await pending, true)
  await turn()
  assert.deepEqual(h.commits, [{ at: 0, target: 'light' }])
  h.clean()
})

test('an incomplete WAAPI implementation without finished degrades to a successful direct switch', async t => {
  const h = harness(t, { missingFinished: true })
  const pending = h.runThemeTransition('dark', 'light')
  await flush()
  assert.equal(await pending, true)
  assert.equal(h.studio.theme, 'light')
  h.clean()
})

test('the watchdog releases a browser animation that never finishes, with no overlay or busy latch left behind', async t => {
  const h = harness(t, { neverFinish: true })
  const pending = h.runThemeTransition('dark', 'light')
  await h.advance(1599)
  assert.equal(h.transition.busy, true)
  assert.equal(h.commits.length, 0)
  await h.advance(1)
  assert.equal(await pending, true)
  await turn()
  assert.deepEqual(h.commits, [{ at: 1600, target: 'light' }])
  h.clean()
})
