import test from 'node:test'
import assert from 'node:assert/strict'
import 'fake-indexeddb/auto'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { compileSources } from './helpers/load-source.mjs'
const load = compileSources()
const { request, setCurrentNovelId } = await load('api/request')
const { useWritingStore } = await load('stores/writing')
const { useFocusStore } = await load('stores/focus')
const { writingDate, clearUploadedSession } = await load('utils/writingActivity')
const { plainDocument } = await load('utils/writing')
const clone = (v) => JSON.parse(JSON.stringify(v))
const storage = () => { const map = new Map(); return { getItem: k => map.get(k) ?? null, setItem: (k, v) => map.set(k, String(v)), removeItem: k => map.delete(k) } }
globalThis.localStorage = storage()
globalThis.sessionStorage = storage()
const stats = (version, days = []) => ({ schemaVersion: 1, version, days })
const record = (sequence, net = sequence) => ({ uid: 'meter', date: '2026-10-04', chapterUid: 'chapter', sequence, net, typed: net, pasted: 0, activeSeconds: 2, peak: 0 })
function setup() {
  setActivePinia(createPinia())
  setCurrentNovelId(1)
  const server = { uid: crypto.randomUUID(), title: '章', doc: plainDocument('旧稿'), links: [], notes: '', summary: '', volumeId: null, goal: 0, numbered: true, deleted: false, status: 'draft', position: 0, revision: 0, wordCount: 2, updatedAt: '' }
  const workspace = () => ({ uid: 'book', structureVersion: 0, volumes: [], chapters: [clone(server)], preferences: { dailyGoal: 2000 }, sessions: [], stats: stats(1), changeSequence: 0, syncedSequence: 0 })
  let handler
  const calls = []
  request.defaults.adapter = async config => {
    const body = config.data ? JSON.parse(config.data) : null
    calls.push({ url: config.url, method: config.method, body })
    const result = handler ? await handler(config, body) : undefined
    let data = result
    if (result === undefined) {
      if (config.url.endsWith('/writing')) data = workspace()
      else if (config.url.endsWith('/stats') || config.url.endsWith('/stats/day')) data = stats(1)
      else if (config.url.includes('/chapters/')) {
        if (config.method === 'put') Object.assign(server, body, { revision: server.revision + 1 })
        data = clone(server)
      } else if (config.url.endsWith('/preferences')) data = workspace()
      else data = []
    }
    return { data, status: 200, statusText: 'OK', headers: {}, config }
  }
  return { store: useWritingStore(), focus: useFocusStore(), workspace, server, calls, set handler(value) { handler = value } }
}
const cleanup = h => { h.store.$reset(); h.focus.$reset() }

test('late session responses cannot lower the newest cumulative sequence and outgoing snapshot is immutable', async () => {
  const h = setup()
  try {
    await h.store.load(1)
    let release, entered
    const started = new Promise(resolve => entered = resolve)
    const gate = new Promise(resolve => release = resolve)
    h.handler = async (config, body) => {
      if (config.url.includes('/sessions/') && body.sequence === 2) { entered(); await gate }
    }
    const old = record(2, 20)
    const pending = h.store.saveSession(old)
    await started
    old.sequence = 99
    old.net = 990
    await h.store.saveSession(record(3, 30))
    release()
    await pending
    assert.equal(h.store.workspace.sessions[0].sequence, 3)
    assert.equal(h.store.workspace.sessions[0].net, 30)
    assert.equal(h.calls.find(c => c.url.includes('/sessions/')).body.sequence, 2)
  } finally { cleanup(h) }
})

test('offline acknowledgement removes only the matching uploaded sequence, preserving a newer local edit', () => {
  const store = storage(), key = 'ink-session-1-meter'
  store.setItem(key, JSON.stringify(record(4)))
  clearUploadedSession(store, key, record(3))
  assert.equal(JSON.parse(store.getItem(key)).sequence, 4)
  clearUploadedSession(store, key, record(4))
  assert.equal(store.getItem(key), null)
  store.setItem(key, JSON.stringify({ ...record(1), uid: 'other' }))
  clearUploadedSession(store, key, record(4))
  assert.notEqual(store.getItem(key), null)
})

test('stats and workspace acknowledgements honor version and never carry statistics into another local work', async () => {
  const h = setup()
  try {
    await h.store.load(1)
    await new Promise(resolve => setImmediate(resolve))
    h.store.applyStats(stats(9, [{ date: '2026-10-04', goal: 800 }]))
    h.store.applyStats(stats(5))
    await h.store.refresh()
    assert.equal(h.store.workspace.stats.version, 9)
    await h.store.refreshStats()
    assert.equal(h.store.workspace.stats.version, 9)
    await h.store.load(2)
    assert.equal(h.store.workspace.stats.version, 1)
    h.store.applyStats(stats(100), 1)
    assert.equal(h.store.workspace.stats.version, 1)
  } finally { cleanup(h) }
})

test('focus stats update the active work and leave other work statistics alone', async () => {
  const h = setup()
  try {
    await h.store.load(1)
    await new Promise(resolve => setImmediate(resolve))
    h.focus.novelId = 2
    h.focus.stats = stats(10)
    await nextTick()
    assert.equal(h.store.workspace.stats.version, 1)
    h.focus.novelId = 1
    h.focus.stats = stats(11)
    await nextTick()
    assert.equal(h.store.workspace.stats.version, 11)
  } finally { cleanup(h) }
})

test('preferences retry freezes the original local date and offset across midnight', async () => {
  const h = setup(), RealDate = Date
  try {
    await h.store.load(1)
    let now = new RealDate(2026, 9, 4, 23, 59).getTime()
    globalThis.Date = class extends RealDate { constructor(...args) { super(...(args.length ? args : [now])) } static now() { return now } }
    const sent = []
    h.handler = async (config, body) => {
      if (!config.url.endsWith('/preferences')) return
      sent.push(clone(body))
      if (sent.length === 1) throw Error('lost acknowledgement')
      return { ...h.workspace(), preferences: body.preferences, stats: stats(3) }
    }
    await assert.rejects(h.store.preferences({ dailyGoal: 888 }), /lost acknowledgement/)
    now += 120000
    await h.store.preferences({ dailyGoal: 888 })
    assert.deepEqual(sent[0], sent[1])
    assert.equal(sent[0].date, '2026-10-04')
    assert.equal(sent[0].timezoneOffsetMinutes, new RealDate(now - 120000).getTimezoneOffset())
    assert.equal(writingDate().date, '2026-10-05')
  } finally { globalThis.Date = RealDate; cleanup(h) }
})

test('chapter save retry freezes activity date and mutation while a new save uses the new date', async () => {
  const h = setup(), RealDate = Date
  try {
    await h.store.load(1)
    await h.store.select(h.server.uid)
    let now = new RealDate(2026, 9, 4, 23, 59).getTime()
    globalThis.Date = class extends RealDate { constructor(...args) { super(...(args.length ? args : [now])) } static now() { return now } }
    const sent = []
    h.handler = async (config, body) => {
      if (config.method !== 'put' || !config.url.includes('/chapters/')) return
      sent.push(clone(body))
      if (sent.length === 1) throw Error('offline')
    }
    h.store.current.doc = plainDocument('当天修稿')
    h.store.changed()
    assert.equal(await h.store.flush(), false)
    now += 120000
    assert.equal(await h.store.flush(), true)
    assert.equal(sent[0].date, '2026-10-04')
    assert.equal(sent[1].date, sent[0].date)
    assert.equal(sent[1].mutationId, sent[0].mutationId)
    h.store.current.doc = plainDocument('第二天继续修稿')
    h.store.changed()
    assert.equal(await h.store.flush(), true)
    assert.equal(sent[2].date, '2026-10-05')
    assert.notEqual(sent[2].mutationId, sent[0].mutationId)
  } finally { globalThis.Date = RealDate; cleanup(h) }
})

test('accepted preferences with a lost acknowledgement are confirmed by readback without replaying stale structureVersion', async () => {
  const h = setup()
  try {
    await h.store.load(1)
    let accepted = null, writes = 0
    h.handler = async (config, body) => {
      if (config.url.endsWith('/preferences')) {
        writes++
        assert.equal(body.structureVersion, 0)
        accepted = { ...h.workspace(), structureVersion: 1, preferences: clone(body.preferences), stats: stats(4) }
        throw Error('lost acknowledgement after commit')
      }
      if (accepted && config.url.endsWith('/writing')) return clone(accepted)
    }
    await h.store.preferences({ dailyGoal: 777 })
    assert.equal(writes, 1)
    assert.equal(h.store.workspace.structureVersion, 1)
    assert.equal(h.store.workspace.preferences.dailyGoal, 777)
    assert.equal(h.store.workspace.stats.version, 4)
  } finally { cleanup(h) }
})

test('preferences conflict adopts current settings and requires an explicit new save before changing them', async () => {
  const h = setup()
  try {
    await h.store.load(1)
    let conflict = true, writes = 0
    h.handler = async (config, body) => {
      if (config.url.endsWith('/writing')) return { ...h.workspace(), structureVersion: 2, preferences: { dailyGoal: 333 } }
      if (config.url.endsWith('/preferences')) {
        writes++
        if (conflict) { const error = Error('conflict'); error.response = { status: 409 }; throw error }
        assert.equal(body.structureVersion, 2)
        return { ...h.workspace(), structureVersion: 3, preferences: body.preferences }
      }
    }
    await assert.rejects(h.store.preferences({ dailyGoal: 777 }), /核对后重新保存/)
    assert.equal(writes, 1)
    assert.equal(h.store.workspace.preferences.dailyGoal, 333)
    conflict = false
    await h.store.preferences({ dailyGoal: 777 })
    assert.equal(writes, 2)
    assert.equal(h.store.workspace.preferences.dailyGoal, 777)
  } finally { cleanup(h) }
})

test('a pending preferences request is not reused when loading another work without reset', async () => {
  const h = setup()
  try {
    await h.store.load(1)
    let offline = true
    h.handler = async (config, body) => {
      if (config.url.endsWith('/writing') && config.url.includes('/2/')) return { ...h.workspace(), structureVersion: 7 }
      if (!config.url.endsWith('/preferences')) return
      if (offline) throw Error('offline')
      assert.equal(config.url, '/novels/2/writing/preferences')
      assert.equal(body.structureVersion, 7)
      return { ...h.workspace(), structureVersion: 8, preferences: body.preferences }
    }
    await assert.rejects(h.store.preferences({ dailyGoal: 888 }), /offline/)
    await h.store.load(2)
    offline = false
    await h.store.preferences({ dailyGoal: 888 })
    assert.equal(h.store.workspace.structureVersion, 8)
  } finally { cleanup(h) }
})
