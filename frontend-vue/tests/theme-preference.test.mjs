import test from 'node:test'
import assert from 'node:assert/strict'
import { setImmediate as nextTurn } from 'node:timers/promises'
import { nextTick } from 'vue'
import { createPinia, disposePinia, getActivePinia, setActivePinia } from 'pinia'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources()
const { useStudioStore } = await load('stores/studio')
const { createThemeTransition } = await load('utils/themeTransition')
const THEME_KEY = 'ink-studio-theme'
const COLOR_QUERY = '(prefers-color-scheme: dark)'

async function flush() {
  await nextTick()
  // Drain promise adoption/catch/finally chains as well as Vue's watcher queue.
  await nextTurn()
  await nextTick()
}

function harness(t, { stored, dark = false, mediaApi = 'modern', reducedMotion = false, spatialOff = false, failStorage = false } = {}) {
  const storage = new Map(stored === undefined ? [] : [[THEME_KEY, stored]])
  if (spatialOff) storage.set('ink-spatial-motion', 'off')
  const writes = [], queries = [], listeners = new Set(), added = [], removed = []
  const previousPinia = getActivePinia()
  const originals = new Map()
  function replace(name, value) {
    originals.set(name, Object.getOwnPropertyDescriptor(globalThis, name))
    Object.defineProperty(globalThis, name, { configurable: true, writable: true, value })
  }
  const media = { media: COLOR_QUERY, matches: dark }
  if (mediaApi === 'modern') {
    media.addEventListener = (event, listener) => {
      assert.equal(event, 'change')
      added.push(listener); listeners.add(listener)
    }
    media.removeEventListener = (event, listener) => {
      assert.equal(event, 'change')
      removed.push(listener); listeners.delete(listener)
    }
  } else if (mediaApi === 'legacy') {
    media.addListener = listener => { added.push(listener); listeners.add(listener) }
    media.removeListener = listener => { removed.push(listener); listeners.delete(listener) }
  }
  const matchMedia = mediaApi === 'missing' ? undefined : query => {
    queries.push(query)
    if (mediaApi === 'throwing') throw new Error('media queries unavailable')
    if (query === '(prefers-reduced-motion: reduce)') return { media: query, matches: reducedMotion }
    assert.equal(query, COLOR_QUERY)
    return media
  }
  replace('localStorage', {
    getItem: key => storage.get(key) ?? null,
    setItem(key, value) {
      writes.push([key, String(value)])
      if (failStorage) throw new Error('preference storage is unavailable')
      storage.set(key, String(value))
    },
    removeItem: key => storage.delete(key),
  })
  replace('window', { matchMedia })
  replace('matchMedia', matchMedia)
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useStudioStore()
  let disposed = false
  function dispose() {
    if (disposed) return
    disposed = true
    store.$dispose()
  }
  t.after(async () => {
    dispose()
    disposePinia(pinia)
    await flush()
    setActivePinia(previousPinia)
    for (const [name, descriptor] of originals) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor)
      else delete globalThis[name]
    }
  })
  return {
    store, storage, queries, listeners, added, removed, dispose,
    themeWrites: () => writes.filter(([key]) => key === THEME_KEY).map(([, value]) => value),
    emit(value) {
      media.matches = value
      const event = { matches: value, media: COLOR_QUERY, target: media }
      for (const listener of [...listeners]) listener.call(media, event)
    },
  }
}

/** Only the presentation bridge is controlled; all preference and race handling is the real store. */
function bridge(t, store) {
  const runs = [], finishes = []
  const unregister = store.registerThemeTransition((from, to, source) => {
    let resolve, reject
    const promise = new Promise((done, fail) => { resolve = done; reject = fail })
    runs.push({ from, to, source, resolve, reject })
    return promise
  }, async targetOverride => {
    finishes.push(targetOverride)
    const target = targetOverride ?? runs.at(-1)?.to
    if (target) store.commitTheme(target)
    // A real controller may resolve its old run later. Deliberately leave it pending here.
  })
  t.after(unregister)
  return {
    runs, finishes,
    complete(index = runs.length - 1) {
      const run = runs[index]
      store.commitTheme(run.to)
      run.resolve(true)
    },
  }
}

/** The production controller owns phase progression and commit; tests release only animation promises. */
function controllerBridge(t, store) {
  const calls = [], shown = [], phases = [], commits = [], pending = new Set()
  let cleanups = 0
  const controller = createThemeTransition({
    enabled: () => true,
    show(...args) { shown.push(args) },
    phase(name, duration) {
      let resolve, reject
      const promise = new Promise((done, fail) => { resolve = done; reject = fail })
      const phase = { name, duration, resolve, reject }
      phases.push(phase); pending.add(phase)
      return promise
    },
    commit(target) { commits.push(target); store.commitTheme(target) },
    nextTick,
    cleanup() {
      cleanups++
      for (const phase of pending) phase.reject(new Error('cancelled animation'))
      pending.clear()
    },
  })
  const unregister = store.registerThemeTransition((...args) => {
    calls.push(args)
    return controller.request(...args)
  }, target => controller.finish(target))
  t.after(async () => { unregister(); await controller.dispose() })
  return {
    controller, calls, shown, phases, commits, pending,
    get cleanups() { return cleanups },
    async release(name) {
      const phase = phases.at(-1)
      assert.equal(phase?.name, name)
      assert.ok(pending.delete(phase), 'each animation promise is released only once')
      phase.resolve()
      await flush()
    },
  }
}

for (const stored of ['dark', 'light']) {
  test(`legacy ${stored} preferences remain explicit and ignore OS changes`, async t => {
    const h = harness(t, { stored, dark: stored !== 'dark' })
    const animation = bridge(t, h.store)
    assert.equal(h.store.themePreference, stored)
    assert.equal(h.store.theme, stored)
    h.emit(true); h.emit(false)
    await flush()
    assert.equal(h.store.theme, stored)
    assert.deepEqual(animation.runs, [])
    assert.deepEqual(h.themeWrites(), [])
    assert.equal(h.storage.get(THEME_KEY), stored)
  })
}

test('missing preference keeps the existing dark default rather than opting into the OS theme', async t => {
  const h = harness(t, { dark: false })
  assert.equal(h.store.themePreference, 'dark')
  assert.equal(h.store.theme, 'dark')
  h.emit(false)
  await flush()
  assert.equal(h.store.theme, 'dark')
  assert.deepEqual(h.themeWrites(), [])
})

for (const dark of [false, true]) {
  test(`system initialization resolves OS ${dark ? 'dark' : 'light'} and follows changes without animation or preference writes`, async t => {
    const h = harness(t, { stored: 'system', dark })
    const animation = bridge(t, h.store)
    assert.equal(h.store.themePreference, 'system')
    assert.equal(h.store.theme, dark ? 'dark' : 'light')
    assert.ok(h.queries.includes(COLOR_QUERY))
    h.emit(!dark)
    await flush()
    assert.equal(h.store.theme, dark ? 'light' : 'dark')
    h.emit(dark)
    await flush()
    assert.equal(h.store.theme, dark ? 'dark' : 'light')
    assert.equal(h.store.themePreference, 'system')
    assert.equal(h.storage.get(THEME_KEY), 'system')
    assert.deepEqual(h.themeWrites(), [])
    assert.deepEqual(animation.runs, [])
  })
}

test('selecting system with the same effective color changes only the persisted preference', async t => {
  const h = harness(t, { stored: 'dark', dark: true })
  const animation = bridge(t, h.store)
  await h.store.requestTheme('system')
  await flush()
  assert.equal(h.store.theme, 'dark')
  assert.equal(h.store.themePreference, 'system')
  assert.deepEqual(h.themeWrites(), ['system'])
  assert.deepEqual(animation.runs, [])
  h.emit(false)
  await flush()
  assert.equal(h.store.theme, 'light')
  assert.equal(h.store.themePreference, 'system')
  assert.deepEqual(h.themeWrites(), ['system'])
  assert.deepEqual(animation.runs, [])
})

test('selecting the current explicit color exits system without an unnecessary animation', async t => {
  const h = harness(t, { stored: 'system', dark: false })
  const animation = bridge(t, h.store)
  await h.store.requestTheme('light')
  await flush()
  assert.equal(h.store.themePreference, 'light')
  assert.equal(h.store.theme, 'light')
  assert.deepEqual(h.themeWrites(), ['light'])
  assert.deepEqual(animation.runs, [])
  h.emit(true)
  await flush()
  assert.equal(h.store.theme, 'light')
  assert.deepEqual(h.themeWrites(), ['light'])
})

for (const action of ['toggle', 'select']) {
  test(`${action} exits system at intent time so OS changes cannot preempt a manual transition`, async t => {
    const h = harness(t, { stored: 'system', dark: true })
    const animation = bridge(t, h.store)
    const pending = action === 'toggle' ? h.store.toggleTheme() : h.store.requestTheme('light')
    await flush()
    assert.equal(h.store.themePreference, 'light')
    assert.equal(h.store.themeTransitionBusy, true)
    assert.equal(h.store.theme, 'dark', 'the presentation bridge has not committed the target yet')
    assert.deepEqual(animation.runs.map(({ from, to }) => [from, to]), [['dark', 'light']])
    assert.deepEqual(h.themeWrites(), ['light'])
    h.emit(false)
    await flush()
    assert.equal(h.store.theme, 'dark', 'an OS event must not bypass the pending manual presentation')
    h.emit(true)
    await flush()
    assert.deepEqual(animation.finishes, [])
    animation.complete()
    await pending
    await flush()
    assert.equal(h.store.theme, 'light')
    assert.equal(h.store.themePreference, 'light')
    assert.equal(h.store.themeTransitionBusy, false)
    assert.deepEqual(h.themeWrites(), ['light'])
    h.emit(true)
    await flush()
    assert.equal(h.store.theme, 'light')
  })
}

test('an OS event interrupts a transition into system and late completion cannot overwrite the latest system color', async t => {
  const h = harness(t, { stored: 'dark', dark: false })
  const animation = bridge(t, h.store)
  const systemRequest = h.store.requestTheme('system')
  await flush()
  assert.equal(h.store.themePreference, 'system')
  assert.equal(h.store.themeTransitionBusy, true)
  assert.deepEqual(animation.runs.map(({ from, to }) => [from, to]), [['dark', 'light']])
  h.emit(true)
  await flush()
  assert.deepEqual(animation.finishes, ['dark'])
  assert.equal(h.store.theme, 'dark')
  assert.equal(h.store.themePreference, 'system')
  assert.equal(h.store.themeTransitionBusy, false)
  assert.deepEqual(h.themeWrites(), ['system'])

  // OS settlement must release the busy latch even if the cancelled bridge resolves later.
  const manualRequest = h.store.requestTheme('light')
  await flush()
  assert.equal(animation.runs.length, 2, 'the completed system interruption must not leave requests blocked')
  animation.runs[0].resolve(false)
  await systemRequest
  await flush()
  assert.equal(h.store.theme, 'dark', 'the obsolete system request must not commit its old light target')
  assert.equal(h.store.themePreference, 'light')
  assert.equal(h.store.themeTransitionBusy, true, 'late settlement must not release the newer manual transition')
  animation.complete(1)
  await manualRequest
  await flush()
  assert.equal(h.store.theme, 'light')
  assert.equal(h.store.themePreference, 'light')
  assert.equal(h.store.themeTransitionBusy, false)
  assert.deepEqual(h.themeWrites(), ['system', 'light'])
})

for (const mediaApi of ['missing', 'throwing']) {
  test(`${mediaApi} matchMedia preserves system preference with the dark fallback`, async t => {
    const h = harness(t, { stored: 'system', mediaApi })
    const animation = bridge(t, h.store)
    assert.equal(h.store.themePreference, 'system')
    assert.equal(h.store.theme, 'dark')
    await h.store.requestTheme('system')
    await flush()
    assert.equal(h.storage.get(THEME_KEY), 'system')
    assert.deepEqual(h.themeWrites(), [])
    assert.deepEqual(animation.runs, [])
    assert.equal(h.listeners.size, 0)
  })
}

test('selecting system without matchMedia still persists system rather than the fallback color', async t => {
  const h = harness(t, { stored: 'dark', mediaApi: 'missing' })
  const animation = bridge(t, h.store)
  await h.store.requestTheme('system')
  await flush()
  assert.equal(h.store.theme, 'dark')
  assert.equal(h.store.themePreference, 'system')
  assert.equal(h.storage.get(THEME_KEY), 'system')
  assert.deepEqual(h.themeWrites(), ['system'])
  assert.deepEqual(animation.runs, [])
})

for (const mediaApi of ['modern', 'legacy']) {
  test(`${mediaApi} system listeners follow the OS and are removed by real store disposal`, async t => {
    const h = harness(t, { stored: 'system', dark: true, mediaApi })
    assert.equal(h.listeners.size, 1)
    assert.equal(h.added.length, 1)
    h.emit(false)
    await flush()
    assert.equal(h.store.theme, 'light')
    h.dispose()
    await flush()
    assert.equal(h.listeners.size, 0)
    assert.deepEqual(h.removed, h.added, 'remove must use the exact registered listener identity')
    h.emit(true)
    await flush()
    assert.equal(h.store.theme, 'light', 'a disposed store must no longer follow OS events')
    assert.deepEqual(h.themeWrites(), [])
  })
}

for (const [from, to] of [['dark', 'light'], ['light', 'dark']]) {
  test(`real controller and store complete ${from} to ${to} with one preference write and no queued repeated toggles`, { timeout: 1500 }, async t => {
    const h = harness(t, { stored: from })
    const animation = controllerBridge(t, h.store)
    const result = h.store.toggleTheme()
    await flush()
    assert.equal(h.store.themePreference, to)
    assert.deepEqual(h.themeWrites(), [to])

    for (const phase of ['cover', 'hold', 'morph', 'fade']) {
      assert.equal(animation.phases.at(-1)?.name, phase)
      assert.equal(h.store.themeTransitionBusy, true)
      assert.equal(animation.controller.busy, true)
      assert.equal(h.store.theme, phase === 'fade' ? to : from)
      assert.deepEqual(animation.commits, phase === 'fade' ? [to] : [])
      assert.equal(await h.store.toggleTheme(), false, `${phase} must ignore a repeated toggle`)
      assert.equal(await h.store.requestTheme('system'), false, 'busy requests cannot replace the original preference')
      await nextTick()
      assert.equal(h.store.themePreference, to)
      assert.deepEqual(h.themeWrites(), [to])
      assert.equal(animation.calls.length, 1)
      await animation.release(phase)
    }

    assert.equal(await result, true)
    await flush()
    assert.equal(h.store.theme, to)
    assert.equal(h.store.themePreference, to)
    assert.equal(h.store.themeTransitionBusy, false)
    assert.equal(animation.controller.busy, false)
    assert.deepEqual(animation.commits, [to], 'only the real controller commits during a normal animated transition')
    assert.equal(animation.cleanups, 1)
    assert.equal(animation.pending.size, 0)
    assert.equal(animation.calls.length, 1)
    assert.equal(animation.shown.length, 1)
    assert.deepEqual(animation.phases.map(({ name }) => name), ['cover', 'hold', 'morph', 'fade'])
    assert.deepEqual(h.themeWrites(), [to])
  })
}

for (const [reason, options] of [['spatial motion disabled', { spatialOff: true }], ['reduced motion requested', { reducedMotion: true }]]) {
  test(`${reason} changes the real store directly without invoking the registered controller`, async t => {
    const h = harness(t, { stored: 'dark', ...options })
    const animation = controllerBridge(t, h.store)

    assert.equal(await h.store.requestTheme('light'), true)
    await flush()

    assert.equal(h.store.themePreference, 'light')
    assert.equal(h.store.theme, 'light')
    assert.equal(h.store.themeTransitionBusy, false)
    assert.equal(animation.controller.busy, false)
    assert.deepEqual(animation.calls, [])
    assert.deepEqual(animation.shown, [])
    assert.deepEqual(animation.phases, [])
    assert.deepEqual(h.themeWrites(), ['light'])
  })
}

test('a rejected registered handler still settles the chosen theme and releases the real store busy flag', { timeout: 1500 }, async t => {
  const h = harness(t, { stored: 'dark' })
  const animation = bridge(t, h.store)
  const result = h.store.requestTheme('light')
  await flush()
  assert.equal(h.store.themeTransitionBusy, true)
  assert.equal(h.store.theme, 'dark')
  animation.runs[0].reject(new Error('presentation host failed'))

  assert.equal(await result, true)
  await flush()
  assert.equal(h.store.theme, 'light')
  assert.equal(h.store.themePreference, 'light')
  assert.equal(h.store.themeTransitionBusy, false)
  assert.deepEqual(h.themeWrites(), ['light'])
})

test('storage write failure does not prevent the real controller and store from reaching the requested theme', { timeout: 1500 }, async t => {
  const h = harness(t, { stored: 'dark', failStorage: true })
  const animation = controllerBridge(t, h.store)
  const result = h.store.requestTheme('light')
  await flush()
  assert.deepEqual(h.themeWrites(), ['light'], 'record the failed persistence attempt without retrying it during each phase')
  for (const phase of ['cover', 'hold', 'morph', 'fade']) await animation.release(phase)

  assert.equal(await result, true)
  await flush()
  assert.equal(h.store.theme, 'light')
  assert.equal(h.store.themePreference, 'light')
  assert.equal(h.store.themeTransitionBusy, false)
  assert.equal(animation.controller.busy, false)
  assert.deepEqual(animation.commits, ['light'])
  assert.equal(animation.cleanups, 1)
  assert.equal(animation.pending.size, 0)
  assert.equal(h.storage.get(THEME_KEY), 'dark', 'storage remains unchanged while the session uses the requested target')
  assert.deepEqual(h.themeWrites(), ['light'])
})
