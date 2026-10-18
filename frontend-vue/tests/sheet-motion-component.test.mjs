import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { effectScope, markRaw, nextTick, reactive, ref, watch } from 'vue'
import { compileSources } from './helpers/load-source.mjs'

const { createSheetFlight, sheetFor, sheetTransition, sheets } = await compileSources()('utils/sheets')
const source = readFileSync(new URL('../src/components/studio/MorphSheet.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
const parsed = ts.createSourceFile('MorphSheet.ts', script, ts.ScriptTarget.ES2022, true, ts.ScriptKind.TS)
const body = ts.createPrinter().printFile(ts.factory.updateSourceFile(parsed, parsed.statements.filter(statement => !ts.isImportDeclaration(statement))))
const compiled = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText
const flush = async () => { for (let i = 0; i < 16; i++) await Promise.resolve() }

class Events {
  listeners = new Map()
  addEventListener(name, callback) {
    if (!this.listeners.has(name)) this.listeners.set(name, new Set())
    this.listeners.get(name).add(callback)
  }
  removeEventListener(name, callback) { this.listeners.get(name)?.delete(callback) }
  emit(name) { for (const callback of this.listeners.get(name) ?? []) callback() }
  count() { return [...this.listeners.values()].reduce((sum, list) => sum + list.size, 0) }
}

const rect = (left, top, width, height) => ({ left, top, width, height })
const pxBox = box => Object.fromEntries(Object.entries(box).map(([key, value]) => [key, `${value}px`]))

function harness(t, options = {}) {
  let now = 0, sequence = 0, target = options.box ?? rect(24, 104, 789, 330), disposed = false
  const timers = new Map(), animations = [], mounted = [], cleanup = [], observers = []
  const schedule = (callback, delay) => { const id = ++sequence; timers.set(id, { callback, at: now + delay }); return id }
  const clear = id => timers.delete(id)
  async function advance(ms) {
    await flush()
    const until = now + ms
    while (true) {
      const next = [...timers].filter(([, timer]) => timer.at <= until).sort((a, b) => a[1].at - b[1].at)[0]
      if (!next) break
      now = next[1].at
      timers.delete(next[0]); next[1].callback(); await flush()
    }
    now = until
    await flush()
  }
  const panel = markRaw({
    style: { removeProperty(name) { delete this[name] } },
    animate(keyframes, settings) {
      let finish, reject
      const finished = new Promise((yes, no) => { finish = yes; reject = no })
      const animation = { keyframes, settings, finished, start: now, canceled: false, completed: false,
        cancel() { this.canceled = true; clear(this.timer); reject(Error('AbortError')) },
      }
      animation.timer = schedule(() => { animation.completed = true; finish() }, settings.duration)
      animations.push(animation)
      return animation
    },
  })
  if (options.noWaapi) panel.animate = undefined
  const media = new Events(); media.matches = !!options.reduced
  const win = Object.assign(new Events(), {
    innerWidth: options.width ?? 837, setTimeout: schedule, clearTimeout: clear, matchMedia: () => media,
  })
  class ResizeObserver {
    constructor(callback) { this.callback = callback; this.connected = false; observers.push(this) }
    observe() { this.connected = true }
    disconnect() { this.connected = false }
  }
  const anchor = markRaw({ getBoundingClientRect: () => target, closest: () => ({}) })
  const props = reactive({ anchor, sheetId: options.sheetId ?? 'foreshadows' })
  const studio = reactive({ spatialMotion: options.spatialMotion !== false, focused: false })
  const runtime = {
    ref, watch, nextTick, defineProps: () => props, useStudioStore: () => studio,
    onMounted: callback => mounted.push(callback), onBeforeUnmount: callback => cleanup.push(callback),
    createSheetFlight, sheetFor, sheetTransition, sheets, window: win, ResizeObserver,
    requestAnimationFrame: callback => schedule(callback, 16), cancelAnimationFrame: clear,
    getComputedStyle: () => {
      // Model the current WAAPI geometry, so interrupted navigation starts from
      // the visible box rather than the destination written to inline styles.
      const active = animations.findLast(animation => !animation.canceled && !animation.completed && 'left' in animation.keyframes[0])
      if (!active) return panel.style
      const progress = Math.min(1, (now - active.start) / active.settings.duration)
      return Object.fromEntries(['left', 'top', 'width', 'height'].map(key => {
        const start = parseFloat(active.keyframes[0][key]), end = parseFloat(active.keyframes[1][key])
        return [key, `${start + (end - start) * progress}px`]
      }))
    },
  }
  const scope = effectScope()
  const component = scope.run(() => new Function(...Object.keys(runtime), compiled + '; return { panel, displayed, reversed, moving, placed, transition }')(...Object.values(runtime)))
  component.panel.value = panel
  for (const callback of mounted) callback()
  async function dispose() {
    if (disposed) return
    disposed = true
    for (const callback of cleanup) callback()
    scope.stop(); await flush()
  }
  t.after(dispose)
  return { ...component, props, studio, media, win, timers, animations, observers, advance, dispose,
    async navigate(id, box = target) { target = box; props.sheetId = id; await advance(16) },
  }
}

test('same-position navigation replaces the module while hidden and never rotates its face', async t => {
  const h = harness(t)
  await h.advance(16)
  await h.navigate('characters')
  assert.equal(h.transition.value, 'handoff')
  assert.equal(h.moving.value, true)
  const turn = h.animations[1]
  assert.ok(turn.keyframes.every(frame => !frame.transform.includes('rotate')))
  assert.equal(turn.keyframes[1].opacity, 0)
  assert.equal(turn.keyframes[2].opacity, 0)
  await h.advance(159)
  assert.equal(h.displayed.value, 'foreshadows')
  await h.advance(1)
  assert.equal(h.displayed.value, 'characters')
  assert.equal(h.reversed.value, false)
  await h.advance(160)
  assert.equal(h.moving.value, false)
  assert.equal(h.timers.size, 0)
})

test('real geometry distinguishes foreshadows, characters and relationships on desktop', async t => {
  const right = rect(960, 204, 286, 478), left = rect(280, 204, 254, 478), wide = rect(280, 104, 966, 248)
  const h = harness(t, { box: right, width: 1280 })
  await h.advance(16)
  for (const [id, box] of [['characters', left], ['relationships', wide]]) {
    const start = Object.fromEntries(['left', 'top', 'width', 'height'].map(key => [key, h.panel.value.style[key]]))
    await h.navigate(id, box)
    assert.equal(h.transition.value, 'flight')
    assert.deepEqual(h.animations.at(-2).keyframes, [start, pxBox(box)])
    await h.advance(820)
    assert.equal(h.displayed.value, id)
    assert.equal(h.moving.value, false)
  }
})

test('all neighboring modules including wraparound hand off when responsive docks coincide', async t => {
  const h = harness(t, { sheetId: sheets[0].id, width: 390, box: rect(20, 104, 350, 322) })
  await h.advance(16)
  for (const destination of [...sheets.slice(1), sheets[0]]) {
    await h.navigate(destination.id)
    assert.equal(h.transition.value, 'handoff', destination.id)
    assert.ok(h.animations.at(-1).keyframes.every(frame => !frame.transform.includes('rotate')))
    await h.advance(320)
    assert.equal(h.displayed.value, destination.id)
    assert.equal(h.moving.value, false)
  }
})

for (const midway of [80, 220]) {
  test(`rapid navigation after ${midway}ms settles only the latest module and releases earlier effects`, async t => {
    const h = harness(t)
    await h.advance(16)
    await h.navigate('characters')
    await h.advance(midway)
    await h.navigate('relationships')
    await h.advance(60)
    await h.navigate('foreshadows')
    await h.advance(500)
    assert.equal(h.displayed.value, 'foreshadows')
    assert.equal(h.moving.value, false)
    assert.equal(h.reversed.value, false)
    assert.equal(h.timers.size, 0)
    assert.ok(h.animations.slice(0, -2).every(animation => animation.canceled))
  })
}

for (const options of [{ reduced: true }, { spatialMotion: false }, { noWaapi: true }]) {
  test(`motion preference or unsupported animation directly places the destination: ${JSON.stringify(options)}`, async t => {
    const h = harness(t, options)
    await h.advance(16)
    await h.navigate('characters')
    assert.equal(h.displayed.value, 'characters')
    assert.equal(h.moving.value, false)
    assert.equal(h.transition.value, 'none')
    assert.equal(h.animations.length, 0)
  })
}

for (const event of ['reduced', 'spatialOff', 'resize', 'scroll']) {
  test(`${event} during a handoff immediately settles the current destination`, async t => {
    const h = harness(t)
    await h.advance(16)
    await h.navigate('characters')
    await h.advance(80)
    if (event === 'reduced') { h.media.matches = true; h.media.emit('change') }
    else if (event === 'spatialOff') h.studio.spatialMotion = false
    else h.win.emit(event)
    await h.advance(500)
    assert.equal(h.displayed.value, 'characters')
    assert.equal(h.moving.value, false)
    assert.ok(h.animations.every(animation => animation.canceled))
    assert.equal(h.timers.size, 0)
  })
}

test('unmount cancels the active transition, observers and listeners', async t => {
  const h = harness(t)
  await h.advance(16)
  await h.navigate('characters')
  await h.dispose()
  await h.advance(1000)
  assert.equal(h.timers.size, 0)
  assert.equal(h.win.count() + h.media.count(), 0)
  assert.ok(h.observers.every(observer => !observer.connected))
  assert.ok(h.animations.every(animation => animation.canceled))
})
