import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources()
const { createThemeTransition, THEME_PHASES, resolveThemeOrigin, preserveThemeFocus } = await load('utils/themeTransition')
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve() }
const eventLoopTurn = () => new Promise(resolve => setImmediate(resolve))

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function harness(options = {}) {
  let now = 0
  const events = [], pending = [], timers = new Set()
  const record = (name, ...args) => events.push({ at: now, name, args })
  const controller = createThemeTransition({
    enabled() { if (options.fail === 'enabled') throw Error('support detection failed'); return options.enabled !== false },
    show(...args) { record('show', ...args); if (options.fail === 'show') throw Error('overlay unavailable') },
    phase(name, duration) {
      record('phase', name, duration)
      if (options.fail === `throw:${name}`) throw Error('animate failed')
      const work = { ...deferred(), name, due: now + duration }
      pending.push(work)
      timers.add(work)
      if (options.fail === `reject:${name}`) { timers.delete(work); work.reject(Error('finished rejected')) }
      return work.promise
    },
    commit(target) { record('commit', target) },
    async nextTick() { record('tick'); if (options.fail === 'nextTick') throw Error('render failed') },
    cleanup() {
      record('cleanup')
      for (const work of timers) {
        if (options.cancelRejects !== false) work.reject(Error('Animation.cancel rejected finished'))
      }
      timers.clear()
      if (options.fail === 'cleanup') throw Error('cleanup failed')
    },
  })
  async function advance(ms) {
    const until = now + ms
    await flush()
    while (true) {
      const next = [...timers].filter(item => item.due <= until).sort((a, b) => a.due - b.due)[0]
      if (!next) break
      now = next.due
      timers.delete(next)
      next.resolve()
      await flush()
    }
    now = until
    await flush()
  }
  const named = name => events.filter(event => event.name === name)
  return { controller, events, pending, timers, advance, named, get now() { return now } }
}

for (const [from, to] of [['dark', 'light'], ['light', 'dark']]) {
  test(`theme transition ${from} to ${to} commits at 780ms and releases its overlay at 980ms`, async () => {
    const h = harness()
    const source = { identity: 'the clicked theme button' }
    const result = h.controller.request(from, to, source)
    assert.equal(h.controller.busy, true)
    await flush()
    assert.deepEqual(h.named('show')[0].args, [from, to, source])
    for (const time of [259, 260, 399, 400, 779]) {
      await h.advance(time - h.now)
      assert.equal(h.named('commit').length, 0, `no commit at ${time}ms`)
      assert.equal(h.controller.busy, true)
    }
    await h.advance(1)
    assert.deepEqual(h.named('commit'), [{ at: 780, name: 'commit', args: [to] }])
    await h.advance(199)
    assert.equal(h.controller.busy, true)
    assert.equal(h.named('cleanup').length, 0)
    await h.advance(1)
    assert.equal(await result, true)
    assert.equal(h.controller.busy, false)
    assert.deepEqual(h.named('phase').map(({ at, args }) => [at, ...args]), [
      [0, 'cover', 260], [260, 'hold', 140], [400, 'morph', 380], [780, 'fade', 200],
    ])
    assert.deepEqual(THEME_PHASES, { cover: 260, hold: 140, morph: 380, fade: 200 })
    assert.equal(h.named('cleanup')[0].at, 980)
    assert.equal(h.timers.size, 0)
  })
}

test('clicks during every phase are ignored with no reverse transition queued after completion', async () => {
  const h = harness()
  const first = h.controller.request('dark', 'light')
  for (const at of [0, 260, 400, 780, 979]) {
    await h.advance(at - h.now)
    assert.equal(await h.controller.request(at >= 780 ? 'light' : 'dark', at >= 780 ? 'dark' : 'light'), false)
    assert.equal(h.named('show').length, 1)
  }
  await h.advance(2000)
  assert.equal(await first, true)
  assert.equal(h.named('commit').length, 1)
  const next = h.controller.request('light', 'dark')
  await h.advance(980)
  assert.equal(await next, true)
  assert.deepEqual(h.named('commit').map(event => event.args[0]), ['light', 'dark'])
})

test('same-theme requests and requests after disposal have no visual or theme side effects', async () => {
  const h = harness()
  assert.equal(await h.controller.request('dark', 'dark'), false)
  assert.equal(h.controller.busy, false)
  assert.equal(h.events.length, 0)
  await h.controller.dispose()
  const previous = h.events.length
  assert.equal(await h.controller.request('dark', 'light'), false)
  assert.equal(h.events.length, previous)
})

test('disabled motion commits the target directly without showing an overlay or scheduling animation', async () => {
  const h = harness({ enabled: false })
  assert.equal(await h.controller.request('dark', 'light'), true)
  assert.deepEqual(h.named('commit').map(event => event.args), [['light']])
  assert.equal(h.named('show').length, 0)
  assert.equal(h.named('phase').length, 0)
  assert.equal(h.named('cleanup').length, 1)
  assert.equal(h.controller.busy, false)
})

for (const [phase, at] of [['cover', 0], ['hold', 260], ['morph', 400], ['fade', 780]]) {
  for (const action of ['finish', 'dispose']) {
    test(`${action} during ${phase} settles to the requested target and consumes canceled finished rejection`, { timeout: 1500 }, async () => {
      const h = harness()
      const result = h.controller.request('dark', 'light')
      await h.advance(at)
      assert.equal(h.pending.at(-1).name, phase)
      await h.controller[action]()
      assert.equal(await result, true)
      await eventLoopTurn()
      assert.equal(h.controller.busy, false)
      assert.deepEqual(h.named('commit').map(event => event.args), [['light']])
      assert.equal(h.named('cleanup').length, 1)
      assert.equal(h.timers.size, 0)
      if (action === 'dispose') assert.equal(await h.controller.request('light', 'dark'), false)
    })
  }
}

test('finish does not wait for an animation promise that never resolves and late settlement cannot affect the next run', { timeout: 1500 }, async () => {
  const h = harness({ cancelRejects: false })
  const first = h.controller.request('dark', 'light')
  await flush()
  const stale = h.pending[0]
  await h.controller.finish()
  assert.equal(await first, true)
  const next = h.controller.request('light', 'dark')
  await flush()
  stale.resolve()
  await flush()
  assert.equal(h.controller.busy, true)
  assert.equal(h.named('show').length, 2)
  assert.equal(h.named('cleanup').length, 1)
  await h.advance(980)
  assert.equal(await next, true)
  assert.deepEqual(h.named('commit').map(event => event.args), [['light'], ['dark']])
  assert.equal(h.named('cleanup').length, 2)
})

for (const failure of ['enabled', 'show', 'nextTick', 'throw:cover', 'reject:cover', 'throw:hold', 'reject:hold', 'throw:morph', 'reject:morph', 'throw:fade', 'reject:fade', 'cleanup']) {
  test(`${failure} failure still commits once, settles successfully and releases busy`, { timeout: 1500 }, async () => {
    const h = harness({ fail: failure })
    const result = h.controller.request('dark', 'light')
    await h.advance(980)
    assert.equal(await result, true)
    await eventLoopTurn()
    assert.deepEqual(h.named('commit').map(event => event.args), [['light']])
    assert.equal(h.controller.busy, false)
    assert.equal(h.named('cleanup').length, 1)
    assert.equal(h.timers.size, 0)
  })
}

for (const at of [0, 780]) {
  test(`system change at ${at}ms overrides the active target without letting the stale run overwrite it`, { timeout: 1500 }, async () => {
    const h = harness({ cancelRejects: false })
    const result = h.controller.request('dark', 'light')
    await h.advance(at)
    const stale = h.pending.at(-1)
    await h.controller.finish('dark')
    assert.equal(await result, true)
    stale.reject(Error('late canceled animation'))
    await eventLoopTurn()
    assert.equal(h.controller.busy, false)
    assert.equal(h.named('commit').at(-1).args[0], 'dark')
    assert.equal(h.named('commit').length, at === 0 ? 1 : 2)
    assert.equal(h.named('cleanup').length, 1)
  })
}

test('the cover origin uses the triggering control, clamps offscreen controls and covers every viewport corner', () => {
  const control = { getBoundingClientRect: () => ({ left: 90, top: 40, width: 20, height: 20 }) }
  const icon = { getBoundingClientRect: () => ({ left: 0, top: 0, width: 2, height: 2 }) }
  for (const source of [control, { currentTarget: control, target: icon }]) {
    const origin = resolveThemeOrigin(source, 1000, 600)
    assert.equal(origin.x, 100)
    assert.equal(origin.y, 50)
    for (const [x, y] of [[0, 0], [1000, 0], [0, 600], [1000, 600]]) assert.ok(origin.radius > Math.hypot(x - origin.x, y - origin.y))
  }
  const edge = resolveThemeOrigin({ getBoundingClientRect: () => ({ left: -100, top: 800, width: 20, height: 20 }) }, 1000, 600)
  assert.deepEqual({ x: edge.x, y: edge.y }, { x: 0, y: 600 })
})

test('missing, detached, hidden and invalid origins safely fall back to the viewport centre', () => {
  for (const source of [undefined, null, { currentTarget: null }, { getBoundingClientRect() { throw Error('detached') } },
    { getBoundingClientRect: () => ({ left: 20, top: 30, width: 0, height: 0 }) },
    { getBoundingClientRect: () => ({ left: NaN, top: 30, width: 20, height: 20 }) }]) {
    const origin = resolveThemeOrigin(source, 1000, 600)
    assert.deepEqual({ x: origin.x, y: origin.y }, { x: 500, y: 300 })
    assert.ok(Number.isFinite(origin.radius))
  }
  const invalidViewport = resolveThemeOrigin(null, NaN, 0)
  assert.ok(Object.values(invalidViewport).every(Number.isFinite))
  assert.ok(invalidViewport.radius > 0)
})

test('primary mouse activation preserves a focused text field or nested editor without refocusing it', t => {
  const original = Object.getOwnPropertyDescriptor(globalThis, 'document')
  t.after(() => original ? Object.defineProperty(globalThis, 'document', original) : delete globalThis.document)
  for (const active of [{ matches: () => true }, { matches: () => false, closest: () => ({ editable: true }) }]) {
    let prevented = 0
    active.focus = () => assert.fail('theme toggle must not refocus the editor')
    globalThis.document = { activeElement: active }
    preserveThemeFocus({ button: 0, pointerType: 'mouse', preventDefault: () => prevented++ })
    assert.equal(prevented, 1)
  }
})

test('keyboard, touch, pen, secondary clicks and noneditable focus retain native activation semantics', t => {
  const original = Object.getOwnPropertyDescriptor(globalThis, 'document')
  t.after(() => original ? Object.defineProperty(globalThis, 'document', original) : delete globalThis.document)
  globalThis.document = { activeElement: { matches: () => true } }
  for (const attributes of [{}, { button: 1, pointerType: 'mouse' }, { button: 2, pointerType: 'mouse' },
    { button: 0, pointerType: 'touch' }, { button: 0, pointerType: 'pen' }]) {
    preserveThemeFocus({ ...attributes, preventDefault: () => assert.fail('native activation should be retained') })
  }
  globalThis.document.activeElement = { matches: () => false, closest: () => null }
  preserveThemeFocus({ button: 0, pointerType: 'mouse', preventDefault: () => assert.fail('button focus should be retained') })
  globalThis.document.activeElement = null
  preserveThemeFocus({ button: 0, pointerType: 'mouse', preventDefault: () => assert.fail('no editor to preserve') })
})
