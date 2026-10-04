import test from 'node:test'
import assert from 'node:assert/strict'
import { createPinia, setActivePinia } from 'pinia'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources()
const {
  createFocusState, readFocusState, startFocus, tickFocus, pauseFocus, resetFocus,
} = await load('utils/focusTimer')
const { localDate } = await load('utils/writing')
const { writingApi } = await load('api/writing')
const { useFocusStore } = await load('stores/focus')
const uidA = '11111111-1111-4111-8111-111111111111'
const uidB = '22222222-2222-4222-8222-222222222222'
const noon = new Date(2026, 9, 4, 12).getTime()
const clone = (value) => JSON.parse(JSON.stringify(value))
const seconds = (receipt) => Object.values(receipt.secondsByDate).reduce((sum, value) => sum + value, 0)
const settle = () => new Promise((resolve) => setImmediate(resolve))
const stats = (version = 1, days = []) => ({ schemaVersion: 1, version, days })

test('pause and resume retain one uid and count only running time until completion', () => {
  const state = createFocusState(900)
  startFocus(state, noon, uidA)
  tickFocus(state, noon + 10_000)
  pauseFocus(state, noon + 20_000)
  assert.equal(state.deadline, 0)
  assert.equal(state.seconds, 880)
  assert.equal(state.uid, uidA)
  assert.deepEqual(state.pending, [])
  tickFocus(state, noon + 100_000)
  assert.equal(state.seconds, 880)
  startFocus(state, noon + 120_000, uidB)
  assert.equal(state.uid, uidA)
  tickFocus(state, noon + 1_005_000)
  assert.equal(state.seconds, 0)
  assert.equal(state.deadline, 0)
  assert.equal(state.uid, null)
  assert.equal(state.pending.length, 1)
  assert.equal(state.pending[0].uid, uidA)
  assert.equal(state.pending[0].completed, true)
  assert.equal(seconds(state.pending[0]), 900)
  tickFocus(state, noon + 1_100_000)
  assert.equal(state.pending.length, 1)
})

test('cancel/reset keeps elapsed seconds without awarding a completed segment and starts a new uid', () => {
  const state = createFocusState(900)
  startFocus(state, noon, uidA)
  pauseFocus(state, noon + 12_800)
  resetFocus(state, noon + 70_000, 2700)
  assert.equal(state.duration, 2700)
  assert.equal(state.seconds, 2700)
  assert.equal(state.remainingMs, 2_700_000)
  assert.equal(state.uid, null)
  assert.equal(state.pending.length, 1)
  assert.equal(state.pending[0].completed, false)
  assert.equal(seconds(state.pending[0]), 12)
  startFocus(state, noon + 80_000, uidB)
  assert.equal(state.uid, uidB)
  resetFocus(state, noon + 80_000)
  assert.equal(state.pending.length, 1, 'an immediate zero-second cancellation creates no receipt')
})

test('reset after the deadline finishes exactly once and preserves its completion timestamp', () => {
  const state = createFocusState(900)
  startFocus(state, noon, uidA)
  resetFocus(state, noon + 910_000)
  assert.equal(state.pending.length, 1)
  assert.equal(state.pending[0].completed, true)
  assert.equal(seconds(state.pending[0]), 900)
  assert.equal(state.pending[0].endedOn, localDate(new Date(noon + 900_000)))
  assert.equal(state.seconds, 900)
  resetFocus(state, noon + 920_000)
  assert.equal(state.pending.length, 1)
})

test('refresh restores elapsed accounting, deadline, pause and pending receipts without duplication', () => {
  const state = createFocusState(900)
  startFocus(state, noon, uidA)
  tickFocus(state, noon + 120_500)
  const refreshed = readFocusState(JSON.stringify(state))
  assert.equal(refreshed.uid, uidA)
  tickFocus(refreshed, noon + 240_000)
  assert.equal(refreshed.seconds, 660)
  assert.equal(refreshed.millisecondsByDate[localDate(new Date(noon))], 240_000)
  pauseFocus(refreshed, noon + 250_000)
  const paused = readFocusState(JSON.stringify(refreshed))
  tickFocus(paused, noon + 500_000)
  assert.equal(paused.seconds, 650)
  assert.equal(paused.deadline, 0)
  startFocus(paused, noon + 500_000, uidB)
  tickFocus(paused, noon + 1_150_000)
  const receipt = clone(paused.pending[0])
  const completed = readFocusState(JSON.stringify(paused))
  tickFocus(completed, noon + 1_200_000)
  assert.deepEqual(completed.pending, [receipt])
  assert.equal(seconds(receipt), 900)
})

test('cross-midnight completion splits seconds by local date and caps a late refresh at the deadline', () => {
  const start = new Date(2026, 9, 4, 23, 59, 50).getTime()
  const state = createFocusState(900)
  startFocus(state, start, uidA)
  const refreshed = readFocusState(JSON.stringify(state))
  tickFocus(refreshed, new Date(2026, 9, 6, 12).getTime())
  assert.deepEqual(refreshed.pending, [{
    uid: uidA,
    completed: true,
    endedOn: '2026-10-05',
    timezoneOffsetMinutes: new Date(start + 900_000).getTimezoneOffset(),
    secondsByDate: { '2026-10-04': 10, '2026-10-05': 890 },
  }])
})

test('a pause spanning midnight assigns no paused seconds to either day', () => {
  const start = new Date(2026, 9, 4, 23, 59, 50).getTime()
  const resume = new Date(2026, 9, 5, 0, 10).getTime()
  const state = createFocusState(900)
  startFocus(state, start, uidA)
  pauseFocus(state, start + 5000)
  startFocus(state, resume, uidB)
  resetFocus(state, resume + 10_000)
  assert.equal(state.pending[0].completed, false)
  assert.equal(state.pending[0].endedOn, '2026-10-05')
  assert.deepEqual(state.pending[0].secondsByDate, { '2026-10-04': 5, '2026-10-05': 10 })
})

test('a deadline exactly at local midnight keeps elapsed seconds on the previous day', () => {
  const start = new Date(2026, 9, 4, 23, 45).getTime()
  const state = createFocusState(900)
  startFocus(state, start, uidA)
  tickFocus(state, start + 900_000)
  assert.equal(state.pending[0].endedOn, '2026-10-05')
  assert.equal(state.pending[0].completed, true)
  assert.deepEqual(state.pending[0].secondsByDate, { '2026-10-04': 900 })
})

test('legacy running countdowns produce no invented receipt, then a fresh start gets a new uid', () => {
  const state = readFocusState(JSON.stringify({ duration: 900, seconds: 120, deadline: noon + 120_000 }))
  assert.equal(state.legacy, true)
  assert.equal(state.uid, null)
  tickFocus(state, noon + 120_000)
  assert.equal(state.seconds, 0)
  assert.deepEqual(state.pending, [])
  startFocus(state, noon + 130_000, uidB)
  assert.equal(state.uid, uidB)
  assert.equal(state.legacy, false)
  tickFocus(state, noon + 1_030_000)
  assert.equal(state.pending.length, 1)
  assert.equal(state.pending[0].uid, uidB)
  assert.equal(seconds(state.pending[0]), 900)
})

test('legacy paused countdowns stay uncounted through resume and reset allows a fresh identified segment', () => {
  const state = readFocusState(JSON.stringify({ duration: 1500, seconds: 100, deadline: 0 }))
  startFocus(state, noon, uidA)
  pauseFocus(state, noon + 10_000)
  assert.equal(state.seconds, 90)
  assert.equal(state.uid, null)
  resetFocus(state, noon + 20_000)
  assert.deepEqual(state.pending, [])
  startFocus(state, noon + 30_000, uidB)
  assert.equal(state.uid, uidB)
  resetFocus(state, noon + 35_000)
  assert.equal(state.pending.length, 1)
  assert.equal(seconds(state.pending[0]), 5)
})

test('a completed legacy countdown starts a fresh identified segment instead of losing new statistics', () => {
  const state = readFocusState(JSON.stringify({ duration: 900, seconds: 0, deadline: 0 }))
  assert.equal(state.uid, null)
  assert.deepEqual(state.pending, [])
  startFocus(state, noon, uidA)
  assert.equal(state.uid, uidA)
  assert.equal(state.legacy, false)
  assert.equal(state.seconds, 900)
  tickFocus(state, noon + 900_000)
  assert.equal(state.pending.length, 1)
  assert.equal(state.pending[0].completed, true)
  assert.equal(state.pending[0].uid, uidA)
  assert.equal(seconds(state.pending[0]), 900)
})

test('an untouched legacy countdown starts a new uid and records only time after its first start', () => {
  const state = readFocusState(JSON.stringify({ duration: 1500, seconds: 1500, deadline: 0 }))
  assert.equal(state.uid, null)
  assert.deepEqual(state.pending, [])
  startFocus(state, noon, uidB)
  assert.equal(state.uid, uidB)
  assert.equal(state.legacy, false)
  resetFocus(state, noon + 12_000)
  assert.equal(state.pending.length, 1)
  assert.equal(state.pending[0].completed, false)
  assert.equal(state.pending[0].uid, uidB)
  assert.equal(seconds(state.pending[0]), 12)
})

test('backward wall-clock changes do not increase remaining time or duplicate already accounted time', () => {
  const state = createFocusState(900)
  startFocus(state, noon, uidA)
  tickFocus(state, noon + 20_000)
  tickFocus(state, noon + 5000)
  assert.equal(state.seconds, 880)
  tickFocus(state, noon + 30_000)
  resetFocus(state, noon + 30_000)
  assert.equal(seconds(state.pending[0]), 30)
})

function setupStore(t, entries = []) {
  assert.equal(typeof window, 'undefined', 'tests should not create a browser interval')
  const storage = new Map(entries)
  const previousStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  const failures = { read: false, write: false }
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
    getItem(key) {
      if (failures.read) throw Error('storage unavailable')
      return storage.get(key) ?? null
    },
    setItem(key, value) {
      if (failures.write) throw Error('quota exceeded')
      storage.set(key, value)
    },
    removeItem(key) { storage.delete(key) },
  } })
  const stores = []
  function freshStore() {
    setActivePinia(createPinia())
    const store = useFocusStore()
    stores.push(store)
    return store
  }
  const store = freshStore()
  t.after(() => {
    for (const item of stores) item.$reset()
    if (previousStorage) Object.defineProperty(globalThis, 'localStorage', previousStorage)
    else delete globalThis.localStorage
  })
  return { store, freshStore, storage, failures }
}

test('store controls persist start, pause, resume and cancellation, then accept uploaded statistics', async (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: noon })
  const { store, storage } = setupStore(t)
  const requests = []
  t.mock.method(writingApi, 'focus', async (id, receipt) => {
    requests.push({ id, receipt: clone(receipt) })
    return stats(3)
  })
  assert.equal(store.load(7), true)
  store.toggle()
  const uid = store.state.uid
  assert.match(uid, /^[\da-f-]{36}$/i)
  assert.equal(store.running, true)
  assert.equal(JSON.parse(storage.get('ink-studio-timer-7')).uid, uid)
  t.mock.timers.setTime(noon + 10_000)
  store.toggle()
  assert.equal(store.running, false)
  assert.equal(store.state.seconds, 1490)
  t.mock.timers.setTime(noon + 100_000)
  store.toggle()
  assert.equal(store.state.uid, uid)
  t.mock.timers.setTime(noon + 105_000)
  store.reset(900)
  await settle()
  assert.equal(requests.length, 1)
  assert.equal(requests[0].id, 7)
  assert.equal(requests[0].receipt.uid, uid)
  assert.equal(requests[0].receipt.completed, false)
  assert.equal(seconds(requests[0].receipt), 15)
  assert.equal(store.state.duration, 900)
  assert.equal(store.stats.version, 3)
  assert.deepEqual(store.state.pending, [])
  assert.deepEqual(JSON.parse(storage.get('ink-studio-timer-7')).pending, [])
})

test('network failure retains one exact payload on disk and retries it after the date changes', async (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: noon })
  const { store, storage } = setupStore(t)
  const requests = []
  t.mock.method(writingApi, 'focus', async (_id, receipt) => {
    requests.push(clone(receipt))
    if (requests.length === 1) throw Error('offline')
    return stats(4)
  })
  store.load(1)
  store.toggle()
  t.mock.timers.setTime(noon + 30_000)
  store.reset()
  await settle()
  const original = clone(store.state.pending[0])
  assert.match(store.syncError, /尚未上传.*offline.*原记录已保留/)
  assert.deepEqual(JSON.parse(storage.get('ink-studio-timer-1')).pending, [original])
  t.mock.timers.setTime(noon + 86_400_000)
  store.tick()
  await settle()
  assert.equal(requests.length, 1, 'a failed upload awaits explicit retry')
  store.retry()
  await settle()
  assert.deepEqual(requests, [original, original])
  assert.deepEqual(store.state.pending, [])
  assert.equal(store.syncError, '')
  assert.equal(store.saving, false)
})

test('server capacity rejection preserves pending records through refresh and retries the same uid', async (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: noon })
  const { store, freshStore, storage } = setupStore(t)
  let rejected = true
  const requests = []
  t.mock.method(writingApi, 'focus', async (_id, receipt) => {
    requests.push(clone(receipt))
    if (rejected) throw { response: { status: 413, data: { message: '专注统计容量已满' } } }
    return stats(5)
  })
  store.load(1)
  store.toggle()
  t.mock.timers.setTime(noon + 15_000)
  store.reset()
  await settle()
  const original = clone(store.state.pending[0])
  assert.match(store.syncError, /容量已满.*原记录已保留/)
  const refreshed = freshStore()
  refreshed.load(1)
  await settle()
  assert.deepEqual(refreshed.state.pending, [original])
  assert.deepEqual(JSON.parse(storage.get('ink-studio-timer-1')).pending, [original])
  rejected = false
  refreshed.retry()
  await settle()
  assert.equal(requests.length, 3)
  assert(requests.every((receipt) => JSON.stringify(receipt) === JSON.stringify(original)))
  assert.deepEqual(refreshed.state.pending, [])
  assert.equal(refreshed.stats.version, 5)
})

test('a lost response retries the original receipt so server-side uid deduplication counts it only once', async (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: noon })
  const { store } = setupStore(t)
  const accepted = new Map()
  const requests = []
  t.mock.method(writingApi, 'focus', async (_id, receipt) => {
    requests.push(clone(receipt))
    if (!accepted.has(receipt.uid)) accepted.set(receipt.uid, clone(receipt))
    else assert.deepEqual(receipt, accepted.get(receipt.uid))
    if (requests.length === 1) throw Error('response lost')
    return stats(accepted.size)
  })
  store.load(1)
  store.toggle()
  t.mock.timers.setTime(noon + 1_500_000)
  store.tick()
  await settle()
  assert.equal(accepted.size, 1)
  assert.equal(store.state.pending.length, 1)
  assert.equal(store.state.pending[0].completed, true)
  store.retry()
  await settle()
  assert.equal(requests.length, 2)
  assert.deepEqual(requests[0], requests[1])
  assert.equal(accepted.size, 1)
  assert.equal(seconds([...accepted.values()][0]), 1500)
  assert.deepEqual(store.state.pending, [])
  assert.equal(store.stats.version, 1)
})

test('refreshing an active store uses the saved deadline and uploads one complete segment', async (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: noon })
  const { store, freshStore } = setupStore(t)
  const requests = []
  t.mock.method(writingApi, 'focus', async (_id, receipt) => {
    requests.push(clone(receipt))
    return stats()
  })
  store.load(1)
  store.toggle()
  const uid = store.state.uid
  t.mock.timers.setTime(noon + 100_000)
  store.tick()
  const refreshed = freshStore()
  t.mock.timers.setTime(noon + 300_000)
  refreshed.load(1)
  assert.equal(refreshed.state.uid, uid)
  assert.equal(refreshed.state.seconds, 1200)
  t.mock.timers.setTime(noon + 1_600_000)
  refreshed.tick()
  await settle()
  refreshed.tick()
  await settle()
  assert.equal(requests.length, 1)
  assert.equal(requests[0].uid, uid)
  assert.equal(seconds(requests[0]), 1500)
  assert.equal(requests[0].completed, true)
})

test('localStorage write failure marks state unsafe and retains live data until persistence is retried', async (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: noon })
  const { store, storage, failures } = setupStore(t)
  t.mock.method(writingApi, 'focus', async () => stats())
  store.load(1)
  const original = storage.get('ink-studio-timer-1')
  failures.write = true
  store.toggle()
  const uid = store.state.uid
  assert.equal(store.localSafe, false)
  assert.match(store.storageError, /尚未存入本机.*保持页面打开.*重试/)
  assert.equal(store.running, true)
  assert.equal(storage.get('ink-studio-timer-1'), original)
  assert.equal(store.load(2), false, 'unsafe live state must not be discarded when changing novels')
  assert.equal(store.novelId, 1)
  assert.equal(store.state.uid, uid)
  failures.write = false
  store.retry()
  await settle()
  assert.equal(store.localSafe, true)
  assert.equal(store.storageError, '')
  assert.equal(JSON.parse(storage.get('ink-studio-timer-1')).uid, uid)
})

test('localStorage read failure blocks mutation and retry restores the preserved record', (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: noon })
  const saved = createFocusState(900)
  const raw = JSON.stringify(saved)
  const { store, storage, failures } = setupStore(t, [['ink-studio-timer-1', raw]])
  failures.read = true
  assert.equal(store.load(1), false)
  assert.equal(store.blocked, true)
  assert.equal(store.localSafe, true, 'the unreadable original was never modified')
  assert.match(store.storageError, /重试读取/)
  store.toggle()
  store.reset(2700)
  assert.equal(storage.get('ink-studio-timer-1'), raw)
  failures.read = false
  store.retry()
  assert.equal(store.blocked, false)
  assert.equal(store.localSafe, true)
  assert.equal(store.state.duration, 900)
  assert.equal(store.state.uid, null)
})

test('malformed local records stay untouched while the store is blocked', (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: noon })
  const raw = JSON.stringify({ duration: 1500, seconds: 1600, deadline: 0 })
  const { store, storage } = setupStore(t, [['ink-studio-timer-1', raw]])
  assert.equal(store.load(1), false)
  assert.equal(store.blocked, true)
  assert.match(store.storageError, /原记录已保留/)
  store.toggle()
  store.reset()
  store.tick()
  assert.equal(storage.get('ink-studio-timer-1'), raw)
})

test('a response from the previous novel cannot clear or populate the current novel state', async (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: noon })
  const { store, storage } = setupStore(t)
  let resolveOld
  t.mock.method(writingApi, 'focus', () => new Promise((resolve) => { resolveOld = resolve }))
  store.load(1)
  store.toggle()
  t.mock.timers.setTime(noon + 10_000)
  store.reset()
  assert.equal(store.saving, true)
  assert.equal(store.state.pending.length, 1)
  const original = clone(store.state.pending[0])
  assert.equal(store.load(2), true)
  resolveOld(stats(99))
  await settle()
  assert.equal(store.novelId, 2)
  assert.equal(store.stats, null)
  assert.equal(store.saving, false)
  assert.deepEqual(store.state.pending, [])
  assert.deepEqual(JSON.parse(storage.get('ink-studio-timer-1')).pending, [original])
})

test('an old upload acknowledgement preserves a newer active period written by another window', async (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: noon + 30_000 })
  const previous = createFocusState(900)
  startFocus(previous, noon, uidA)
  resetFocus(previous, noon + 30_000)
  const receipt = clone(previous.pending[0])
  const { store, storage } = setupStore(t, [['ink-studio-timer-1', JSON.stringify(previous)]])
  let acknowledge
  const requests = []
  t.mock.method(writingApi, 'focus', (_id, payload) => {
    requests.push(clone(payload))
    return new Promise((resolve) => { acknowledge = resolve })
  })
  store.load(1)
  assert.equal(store.saving, true)
  assert.deepEqual(requests, [receipt])

  const external = createFocusState(1500)
  startFocus(external, noon + 30_000, uidB)
  storage.set('ink-studio-timer-1', JSON.stringify(external))
  acknowledge(stats(1))
  await settle()

  assert.equal(store.state.uid, uidB)
  assert.equal(store.running, true)
  assert.equal(store.state.deadline, external.deadline)
  assert.equal(store.state.remainingMs, external.remainingMs)
  assert.deepEqual(store.state.pending, [])
  assert.deepEqual(JSON.parse(storage.get('ink-studio-timer-1')), external)
  assert.equal(requests.length, 1)
  assert.equal(store.saving, false)
})

test('acknowledgement removes only the matching payload when another window has a conflicting terminal receipt', async (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: noon + 900_000 })
  const local = createFocusState(900)
  startFocus(local, noon, uidA)
  resetFocus(local, noon + 30_000)
  const cancelled = clone(local.pending[0])
  const { store, storage } = setupStore(t, [['ink-studio-timer-1', JSON.stringify(local)]])
  let acknowledge
  const requests = []
  t.mock.method(writingApi, 'focus', async (_id, payload) => {
    requests.push(clone(payload))
    if (requests.length === 1) return new Promise((resolve) => { acknowledge = resolve })
    throw { response: { status: 409, data: { message: '同一专注时段已有不同结束结果' } } }
  })
  store.load(1)
  assert.equal(store.saving, true)
  assert.deepEqual(requests, [cancelled])

  const external = createFocusState(900)
  startFocus(external, noon, uidA)
  tickFocus(external, noon + 900_000)
  const completed = clone(external.pending[0])
  storage.set('ink-studio-timer-1', JSON.stringify(external))
  store.tick()
  assert.equal(store.state.pending.length, 2)
  assert.deepEqual(store.state.pending.find((receipt) => receipt.completed), completed)
  assert.deepEqual(store.state.pending.find((receipt) => !receipt.completed), cancelled)

  acknowledge(stats(1))
  await settle()
  assert.deepEqual(requests, [cancelled], 'the in-flight sync only submits its original snapshot')
  assert.deepEqual(store.state.pending, [completed])
  await store.sync()
  assert.deepEqual(requests, [cancelled, completed])
  assert.equal(completed.uid, cancelled.uid)
  assert.equal(seconds(cancelled), 30)
  assert.equal(seconds(completed), 900)
  assert.deepEqual(store.state.pending, [completed])
  assert.deepEqual(JSON.parse(storage.get('ink-studio-timer-1')).pending, [completed])
  assert.match(store.syncError, /终态冲突.*原件已保留/)
  assert.equal(store.saving, false)
  store.retry()
  await settle()
  assert.deepEqual(requests[2], completed)
  assert.deepEqual(store.state.pending, [completed])
})

test('cross-midnight fractional seconds retain the full duration across daily buckets', () => {
  const start = new Date(2026, 9, 4, 23, 59, 30, 250).getTime()
  const state = createFocusState(900)
  startFocus(state, start, uidA)
  tickFocus(state, start + 900_000)
  assert.equal(state.pending.length, 1)
  assert.equal(state.pending[0].completed, true)
  assert.equal(state.pending[0].endedOn, '2026-10-05')
  assert.deepEqual(state.pending[0].secondsByDate, { '2026-10-04': 29, '2026-10-05': 871 })
  assert.equal(seconds(state.pending[0]), 900)
})

test('a conflicting receipt is retained and exported while an independent receipt still uploads', async (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: noon })
  const cancelledState = createFocusState(900)
  startFocus(cancelledState, noon - 40_000, uidA)
  resetFocus(cancelledState, noon - 10_000)
  const conflict = clone(cancelledState.pending[0])
  const completedState = createFocusState(900)
  startFocus(completedState, noon - 900_000, uidB)
  tickFocus(completedState, noon)
  const normal = clone(completedState.pending[0])
  const active = createFocusState(1500)
  const activeUid = '33333333-3333-4333-8333-333333333333'
  startFocus(active, noon, activeUid)
  active.pending = [conflict, normal]
  const { store, storage } = setupStore(t, [['ink-studio-timer-1', JSON.stringify(active)]])
  const requests = []
  t.mock.method(writingApi, 'focus', async (id, receipt) => {
    requests.push({ id, receipt: clone(receipt) })
    if (receipt.uid === uidA) throw { response: { status: 409, data: { message: '终态不同' } } }
    return stats(2)
  })
  store.load(1)
  await settle()
  assert.deepEqual(requests, [{ id: 1, receipt: conflict }, { id: 1, receipt: normal }])
  assert.deepEqual(store.state.pending, [conflict])
  assert.deepEqual(JSON.parse(storage.get('ink-studio-timer-1')).pending, [conflict])
  assert.equal(store.stats.version, 2)
  assert.equal(store.saving, false)
  assert.match(store.syncError, /冲突原件已保留.*其他时段继续上传/)
  const exported = JSON.parse(store.exportData())
  assert.equal(exported.schemaVersion, 1)
  assert.equal(exported.novelId, 1)
  assert.deepEqual(exported.state.pending, [conflict])
  assert.equal(exported.state.uid, activeUid)
  assert.equal(exported.state.deadline, active.deadline)
  assert.equal(exported.state.remainingMs, active.remainingMs)
  assert.equal(store.state.uid, activeUid, 'exporting must preserve the running period')
})
