import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import 'fake-indexeddb/auto'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources()
const { seriesPackageStore, seriesPackageDigest } = await load('utils/seriesPendingPackages')
const { useSeriesWorkspace } = await load('composables/useSeriesWorkspace')
const { emptySeriesPayload } = await load('utils/series')
const clone = value => JSON.parse(JSON.stringify(value))
const uid = number => `${number.toString(16).padStart(8, '0')}-2222-4222-8222-222222222222`
const size = value => new TextEncoder().encode(typeof value === 'string' ? value : JSON.stringify(value)).byteLength
const templateUid = uid(1), time = '2026-10-04T14:00:00Z'
const alive = [], persisted = new Set(), removals = []
afterEach(async () => {
  for (const workspace of alive.splice(0)) workspace.dispose()
  await Promise.allSettled(removals.splice(0))
  for (const id of persisted) await seriesPackageStore.remove(id)
  persisted.clear()
})

function pack(count = 1) {
  const revisions = Array.from({ length: count }, (_, index) => ({
    uid: uid(100 + index), templateUid, parentRevisionUid: index ? uid(99 + index) : null,
    number: index + 1, kind: 'race', seriesName: '长篇系列',
    payload: { ...emptySeriesPayload('race'), name: `海民第 ${index + 1} 稿`, description: '  中文原文\n\n空行 🌙\r\n尾空格  ', notes: count > 1 ? 'a'.repeat(20000) : '' },
    authorStatus: 'draft', changeNote: '', createdAt: time, hash: `mock-revision-hash-${index}`,
  }))
  return { format: 'novel-assistant-series-v1', schemaVersion: 1, exportedAt: time,
    templates: [{ uid: templateUid, kind: 'race', headRevisionUid: revisions.at(-1).uid, archived: false }], revisions,
  }
}

function memoryStorage() {
  const entries = new Map(), writes = [], faults = { write: false }
  return { entries, writes, faults, storage: {
    get length() { return entries.size }, key: index => [...entries.keys()][index] ?? null,
    getItem: key => entries.get(key) ?? null,
    setItem(key, value) {
      const used = [...entries].filter(([other]) => other !== key).reduce((total, [, text]) => total + size(text), 0)
      if (faults.write || used + size(value) > 5 * 1024 * 1024) throw Error('localStorage quota')
      writes.push({ key, bytes: size(value) }); entries.set(key, value)
    },
    removeItem: key => entries.delete(key),
  } }
}

function trackedPackageStore(overrides = {}) {
  return {
    async put(id, value) { persisted.add(id); return (overrides.put || seriesPackageStore.put)(id, value) },
    get: (id) => (overrides.get || seriesPackageStore.get)(id),
    remove(id) {
      const removing = (overrides.remove || seriesPackageStore.remove)(id)
      removals.push(removing)
      return removing
    },
  }
}

const success = { createdTemplates: 1, addedRevisions: 1, reusedRevisions: 0, preservedHeads: 0, replayed: false }
const interrupted = () => ({ response: { status: 500, data: { message: '导入响应丢失' } } })
function fixture({ store = memoryStorage(), packageStore = trackedPackageStore(), api: overrides = {} } = {}) {
  const calls = []
  const implementations = {
    state: async () => ({ schemaVersion: 1, version: 0, epoch: uid(5), worlds: [{ uid: uid(6), name: '本作世界', description: '' }], copies: [], sourceRevisions: [] }),
    listTemplates: async () => ({ items: [] }),
    importLibrary: async () => ({ ...success }),
    ...overrides,
  }
  const api = Object.fromEntries(Object.entries(implementations).map(([name, implementation]) => [name, (...args) => {
    calls.push([name, ...clone(args)]); return implementation(...args)
  }]))
  const w = useSeriesWorkspace(31, api, store.storage, packageStore)
  alive.push(w)
  return { w, api, store, packageStore, calls, named: name => calls.filter(call => call[0] === name) }
}
const submit = (h, value) => h.w.mutate('importLibrary', '', { planToken: 'frozen-import-plan', package: value }, '母本库已导入。')
const pendingKeys = h => [...h.store.entries.keys()].filter(key => key.startsWith('series-pending:31:'))

test('the real IndexedDB store round-trips a complete package larger than 5 MiB without aliases or truncation', async () => {
  const value = pack(280), id = crypto.randomUUID(); persisted.add(id)
  assert.ok(size(value) > 5 * 1024 * 1024)
  await seriesPackageStore.put(id, value)
  const restored = await seriesPackageStore.get(id)
  assert.deepEqual(restored, value)
  restored.revisions[0].payload.notes = '读取后的独立编辑'
  value.revisions[0].payload.description = '调用者后续编辑'
  const second = await seriesPackageStore.get(id)
  assert.equal(second.revisions[0].payload.notes.length, 20000)
  assert.equal(second.revisions[0].payload.description, '  中文原文\n\n空行 🌙\r\n尾空格  ')
  await seriesPackageStore.remove(id)
  assert.equal(await seriesPackageStore.get(id), undefined)
})

test('package digests preserve exact Chinese, whitespace, empty strings and package identity', async () => {
  const original = pack(), baseline = await seriesPackageDigest(original)
  assert.match(baseline, /^[0-9a-f]{64}$/)
  assert.equal(await seriesPackageDigest(clone(original)), baseline)
  const changed = clone(original); changed.revisions[0].payload.notes = ' '
  assert.notEqual(await seriesPackageDigest(changed), baseline)
  changed.revisions[0].payload.notes = ''; changed.exportedAt = '2026-10-04T14:00:01Z'
  assert.notEqual(await seriesPackageDigest(changed), baseline)
  assert.equal(original.revisions[0].payload.notes, '')
})

test('large imports keep only a small receipt in localStorage and retry the same UUID and complete frozen package after reload', async () => {
  const store = memoryStorage(), original = pack(280), frozen = clone(original)
  const first = fixture({ store, api: { importLibrary: async () => { throw interrupted() } } })
  const importing = submit(first, original)
  original.revisions[0].payload.description = '选择文件对象之后被改动'
  assert.equal(await importing, false)
  const firstCall = first.named('importLibrary')[0], mutationId = firstCall[1].mutationId
  assert.deepEqual(firstCall[1].package, frozen)
  assert.equal(pendingKeys(first).length, 1)
  const receipt = JSON.parse(store.entries.get(pendingKeys(first)[0]))
  assert.equal(receipt.mutationId, mutationId)
  assert.equal(Object.hasOwn(receipt.body, 'package'), false)
  assert.equal(Object.hasOwn(first.w.pending.value.body, 'package'), false)
  assert.match(receipt.packageDigest, /^[0-9a-f]{64}$/)
  assert.ok(store.writes.every(write => write.bytes < 4096))
  assert.deepEqual(await seriesPackageStore.get(mutationId), frozen)
  first.w.dispose()
  const restored = fixture({ store, api: { importLibrary: async () => ({ ...success, replayed: true }) } })
  assert.equal(restored.w.pending.value.mutationId, mutationId)
  assert.equal(await restored.w.refresh(), true)
  assert.equal(restored.named('importLibrary').length, 0)
  assert.equal(await restored.w.retry(), true)
  assert.deepEqual(restored.named('importLibrary')[0], firstCall)
  await Promise.allSettled(removals)
  assert.equal(restored.w.pending.value, null)
  assert.deepEqual(pendingKeys(restored), [])
  assert.equal(await seriesPackageStore.get(mutationId), undefined)
})

test('missing packages block POST; a different file cannot replace the pending import, while the exact original restores it', async () => {
  let fail = true
  const original = pack(), h = fixture({ api: { importLibrary: async () => { if (fail) throw interrupted(); return { ...success, replayed: true } } } })
  assert.equal(await submit(h, original), false)
  const id = h.w.pending.value.mutationId, firstCall = clone(h.named('importLibrary')[0]), receipt = clone(h.w.pending.value)
  await seriesPackageStore.remove(id)
  assert.equal(await h.w.retry(), false)
  assert.equal(h.named('importLibrary').length, 1)
  assert.equal(h.w.pending.value.mutationId, id)
  assert.match(h.w.error.value, /原母本包|原文件/)
  const wrong = clone(original); wrong.revisions[0].payload.notes = '不是原来的文件'
  assert.equal(await h.w.restorePendingPackage(wrong), false)
  assert.match(h.w.error.value, /不是原提交/)
  assert.deepEqual(clone(h.w.pending.value), receipt)
  assert.equal(await seriesPackageStore.get(id), undefined)
  assert.equal(await h.w.restorePendingPackage(original), true)
  assert.equal(h.named('importLibrary').length, 1)
  assert.deepEqual(await seriesPackageStore.get(id), original)
  fail = false
  assert.equal(await h.w.retry(), true)
  assert.deepEqual(h.named('importLibrary')[1], firstCall)
  await Promise.allSettled(removals)
  assert.equal(await seriesPackageStore.get(id), undefined)
})

test('a damaged stored package is never posted and a rejected replacement does not overwrite the retained bytes', async () => {
  const original = pack(), h = fixture({ api: { importLibrary: async () => { throw interrupted() } } })
  await submit(h, original)
  const id = h.w.pending.value.mutationId, damaged = clone(original)
  damaged.revisions[0].payload.description = '被意外修改的暂存数据'
  await seriesPackageStore.put(id, damaged)
  assert.equal(await h.w.retry(), false)
  assert.equal(h.named('importLibrary').length, 1)
  const wrong = clone(original); wrong.templates[0].archived = true
  assert.equal(await h.w.restorePendingPackage(wrong), false)
  assert.deepEqual(await seriesPackageStore.get(id), damaged)
  assert.equal(h.w.pending.value.mutationId, id)
  assert.equal(await h.w.restorePendingPackage(original), true)
  assert.deepEqual(await h.w.pendingPackage(), original)
})

test('an IndexedDB write failure blocks import before any POST or local mutation receipt is created', async () => {
  const packageStore = trackedPackageStore({ put: async () => { throw Error('IndexedDB quota exceeded') } })
  const h = fixture({ packageStore })
  assert.equal(await submit(h, pack()), false)
  assert.equal(h.named('importLibrary').length, 0)
  assert.equal(h.w.pending.value, null)
  assert.deepEqual(pendingKeys(h), [])
  assert.match(h.w.storageError.value, /尚未提交/)
  assert.equal(h.w.busy.value, false)
})

test('missing browser IndexedDB is reported by the real store without sending an import', async () => {
  const existing = globalThis.indexedDB
  try {
    globalThis.indexedDB = undefined
    await assert.rejects(seriesPackageStore.put(crypto.randomUUID(), pack()), /浏览器无法暂存/)
    const h = fixture()
    assert.equal(await submit(h, pack()), false)
    assert.equal(h.named('importLibrary').length, 0)
    assert.equal(h.w.pending.value, null)
  } finally { globalThis.indexedDB = existing }
})

test('failure to persist the small localStorage receipt blocks POST and removes the otherwise orphaned package', async () => {
  const h = fixture(); h.store.faults.write = true
  assert.equal(await submit(h, pack()), false)
  assert.equal(h.named('importLibrary').length, 0)
  assert.equal(h.w.pending.value, null)
  assert.match(h.w.storageError.value, /尚未提交/)
  await Promise.allSettled(removals)
  for (const id of persisted) assert.equal(await seriesPackageStore.get(id), undefined)
})

test('an accepted import whose catalog refresh fails retains both the receipt and original package until exact retry succeeds', async () => {
  let readFails = true
  const original = pack(), h = fixture({ api: {
    listTemplates: async () => { if (readFails) throw { response: { status: 409, data: { message: '目录读取冲突' } } }; return { items: [] } },
    importLibrary: async () => ({ ...success, replayed: true }),
  } })
  assert.equal(await submit(h, original), false)
  const first = clone(h.named('importLibrary')[0]), id = h.w.pending.value.mutationId
  assert.equal(pendingKeys(h).length, 1)
  assert.deepEqual(await seriesPackageStore.get(id), original)
  readFails = false
  assert.equal(await h.w.retry(), true)
  assert.deepEqual(h.named('importLibrary')[1], first)
  await Promise.allSettled(removals)
  assert.equal(h.w.pending.value, null)
  assert.deepEqual(pendingKeys(h), [])
  assert.equal(await seriesPackageStore.get(id), undefined)
})
