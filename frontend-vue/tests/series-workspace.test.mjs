import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources()
const { useSeriesWorkspace } = await load('composables/useSeriesWorkspace')
const { emptySeriesPayload } = await load('utils/series')
const clone = value => JSON.parse(JSON.stringify(value))
const uid = number => `${number.toString(16).padStart(8, '0')}-1111-4111-8111-111111111111`
const ids = { template: uid(1), revision: uid(2), newerRevision: uid(3), worldA: uid(4), worldB: uid(5), copyA: uid(6), copyB: uid(7), epoch: uid(8) }
const time = '2026-10-04T12:00:00Z'
const alive = []
afterEach(() => { for (const workspace of alive.splice(0)) workspace.dispose() })
const settle = () => new Promise(resolve => setImmediate(resolve))
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const httpError = (status, message = `HTTP ${status}`, code = 'test_error') => ({ response: { status, data: { message, code } } })

function records() {
  const payload = { ...emptySeriesPayload('race'), name: '海民', description: '原始中文\n第二行', culture: '旧文化' }
  const revision = { uid: ids.revision, templateUid: ids.template, parentRevisionUid: null, number: 1, kind: 'race', seriesName: '群星系列', payload, authorStatus: 'confirmed', changeNote: '', createdAt: time, hash: 'revision-one' }
  const newer = { ...clone(revision), uid: ids.newerRevision, parentRevisionUid: ids.revision, number: 2, payload: { ...payload, description: '新版描述', notes: '新版备注' }, hash: 'revision-two' }
  const template = { uid: ids.template, kind: 'race', seriesName: revision.seriesName, name: payload.name, headRevisionUid: ids.revision, headRevisionNumber: 1, lockVersion: 3, archived: false, revisionCount: 2, createdAt: time, updatedAt: time }
  const copy = (copyUid, universeUid) => ({
    uid: copyUid, universeUid, kind: 'race', planet: '', content: clone(payload), authorStatus: 'draft',
    baselineRevisionUid: ids.revision, origin: { templateUid: ids.template, revisionUid: ids.revision, copiedAt: time },
    lastReview: { revisionUid: ids.revision, adoptedFields: Object.keys(payload), keptFields: [], reviewedAt: time, mode: 'copy' },
    fieldOrigins: Object.fromEntries(Object.keys(payload).map(key => [key, { kind: 'source', revisionUid: ids.revision }])),
    archived: false, createdAt: time, updatedAt: time, history: [],
  })
  const state = { schemaVersion: 1, version: 7, epoch: ids.epoch,
    worlds: [{ uid: ids.worldA, name: '本作世界', description: '' }, { uid: ids.worldB, name: '平行世界', description: '' }],
    copies: [copy(ids.copyA, ids.worldA), copy(ids.copyB, ids.worldB)], sourceRevisions: [clone(revision)],
  }
  return { state, template, revisions: [revision, newer] }
}

function memoryStorage(entries = new Map()) {
  const faults = { read: false, write: false, remove: false }
  return { entries, faults,
    storage: {
      get length() { if (faults.read) throw Error('Storage unavailable'); return entries.size },
      key(index) { if (faults.read) throw Error('Storage unavailable'); return [...entries.keys()][index] ?? null },
      getItem(key) { if (faults.read) throw Error('Storage unavailable'); return entries.get(key) ?? null },
      setItem(key, value) { if (faults.write) throw Error('Storage quota'); entries.set(key, value) },
      removeItem(key) { if (faults.remove) throw Error('Storage unavailable'); entries.delete(key) },
    },
  }
}

function fixture(overrides = {}, novelId = 21, store = memoryStorage()) {
  const server = records(), calls = []
  const result = () => ({ state: clone(server.state), replayed: false, resultVersion: server.state.version })
  const implementations = {
    state: async () => clone(server.state),
    listTemplates: async () => ({ items: [clone(server.template)] }),
    template: async () => ({ template: clone(server.template), revisions: clone(server.revisions) }),
    updateCopy: async (_novelId, copyUid, body) => {
      const copy = server.state.copies.find(row => row.uid === copyUid)
      copy.content = clone(body.content); copy.authorStatus = body.authorStatus; copy.planet = body.planet
      server.state.version++; return result()
    },
    publishTemplate: async (_templateUid, body) => {
      const revision = { ...clone(server.revisions[0]), uid: uid(30), number: 3, payload: clone(body.payload), seriesName: body.seriesName, authorStatus: body.authorStatus }
      server.revisions.push(revision); server.template.headRevisionUid = revision.uid; server.template.headRevisionNumber = 3; server.template.lockVersion++
      return { template: clone(server.template), revision: clone(revision), replayed: false }
    },
    updateWorld: async (_novelId, worldUid, body) => {
      const world = server.state.worlds.find(row => row.uid === worldUid)
      world.name = body.name; world.description = body.description; server.state.version++; return result()
    },
    adopt: async () => { server.state.version++; return result() },
    review: async () => { server.state.version++; return result() },
    compare: async (_novelId, copyUid, revisionUid) => comparison(server, copyUid, revisionUid),
    archiveTemplate: async (_templateUid, body) => {
      server.template.archived = body.archived; server.template.lockVersion++
      return { template: clone(server.template), replayed: false }
    },
    ...overrides,
  }
  const api = Object.fromEntries(Object.entries(implementations).map(([name, implementation]) => [name, (...args) => {
    calls.push([name, ...clone(args)])
    return implementation(...args)
  }]))
  const workspace = useSeriesWorkspace(novelId, api, store.storage)
  alive.push(workspace)
  return { w: workspace, api, server, calls, store, result, named: name => calls.filter(call => call[0] === name) }
}

function comparison(server, copyUid = ids.copyA, revisionUid = ids.newerRevision) {
  const copy = server.state.copies.find(row => row.uid === copyUid), revision = server.revisions.find(row => row.uid === revisionUid)
  return { copyUid, epoch: server.state.epoch, expectedVersion: server.state.version, copyHash: 'frozen-copy-hash', baselineRevisionUid: copy.baselineRevisionUid, revision: clone(revision),
    fields: Object.keys(copy.content).map(key => ({ key, base: server.revisions[0].payload[key], local: copy.content[key], incoming: revision.payload[key], sourceChanged: revision.payload[key] !== server.revisions[0].payload[key], localChanged: false, conflict: false, different: copy.content[key] !== revision.payload[key] })),
  }
}
async function ready(h) { assert.equal(await h.w.refresh(), true) }
function editCopy(h) { h.w.editCopy(h.w.data.value.copies[0]); h.w.draft.value.payload.description = '本作草稿\n保留空格  '; return h.w.draft.value.uid }
const draftKeys = h => [...h.store.entries.keys()].filter(key => key.startsWith(`series-draft:${h.w.novelId}:`))
const pendingKeys = h => [...h.store.entries.keys()].filter(key => key.startsWith(`series-pending:${h.w.novelId}:`))

test('successful unchanged source, copy and world drafts are closed and removed only after fresh reads', async () => {
  for (const kind of ['source', 'copy', 'world']) {
    const h = fixture(); await ready(h)
    if (kind === 'source') { h.w.editSource(h.server.revisions[0]); h.w.draft.value.payload.notes = '新版备注' }
    if (kind === 'copy') editCopy(h)
    if (kind === 'world') { h.w.editWorld(ids.worldA); h.w.draft.value.name = '改名后的世界' }
    const draftUid = h.w.draft.value.uid
    assert.equal(draftKeys(h).length, 1)
    assert.equal(await h.w.saveDraft(), true)
    assert.equal(h.w.draft.value, null, `${kind}: committed unchanged draft remains active`)
    assert.equal(h.w.editorOpen.value, false)
    assert.equal(h.w.drafts.value.some(draft => draft.uid === draftUid), false)
    assert.deepEqual(draftKeys(h), [])
    assert.deepEqual(pendingKeys(h), [])
    assert.equal(h.w.pending.value, null)
    h.w.dispose()
  }
})

test('editing a draft while its earlier version is submitted retains the newer draft and stored copy', async () => {
  const gate = deferred(), h = fixture({ updateCopy: () => gate.promise }); await ready(h)
  const draftUid = editCopy(h), saving = h.w.saveDraft()
  assert.equal(h.w.busy.value, true)
  const body = h.named('updateCopy')[0][3]
  assert.equal(body.content.description, '本作草稿\n保留空格  ')
  h.w.draft.value.payload.description = '请求途中继续输入的新稿'
  h.server.state.version++
  h.server.state.copies[0].content = clone(body.content)
  gate.resolve(h.result()); assert.equal(await saving, true)
  assert.equal(h.w.draft.value.uid, draftUid)
  assert.equal(h.w.draft.value.payload.description, '请求途中继续输入的新稿')
  assert.equal(JSON.parse(h.store.entries.get(draftKeys(h)[0])).payload.description, '请求途中继续输入的新稿')
  assert.equal(h.w.pending.value, null)
  assert.equal(h.w.draftConflict.value, true)
})

test('500 failures retain the frozen semantic body and UUID across exact retries despite newer draft edits', async () => {
  let fail = true
  const h = fixture({ updateCopy: async () => { if (fail) throw httpError(500, '响应丢失'); return { ...h.result(), replayed: true } } }); await ready(h)
  editCopy(h)
  assert.equal(await h.w.saveDraft(), false)
  const pending = clone(h.w.pending.value), first = clone(h.named('updateCopy')[0])
  assert.equal(pendingKeys(h).length, 1)
  assert.match(h.w.error.value, /原请求|操作编号/)
  h.w.draft.value.payload.notes = '只在草稿里的新内容'
  assert.equal(await h.w.saveDraft(), false)
  assert.equal(h.named('updateCopy').length, 1)
  fail = false
  assert.equal(await h.w.retry(), true)
  assert.deepEqual(h.named('updateCopy')[1], first)
  assert.equal(first[3].mutationId, pending.mutationId)
  assert.equal(first[3].content.notes, '')
  assert.equal(h.w.draft.value.payload.notes, '只在草稿里的新内容')
  assert.equal(h.w.pending.value, null)
})

test('409 rejects the pending mutation without deleting its draft or silently rebasing it', async () => {
  const h = fixture({ updateCopy: async () => { throw httpError(409, '作品已变化', 'stale_version') } }); await ready(h)
  const draftUid = editCopy(h)
  assert.equal(await h.w.saveDraft(), false)
  assert.equal(h.w.pending.value, null)
  assert.deepEqual(pendingKeys(h), [])
  assert.equal(h.w.draft.value.uid, draftUid)
  assert.equal(h.w.draft.value.expectedVersion, 7)
  assert.equal(draftKeys(h).length, 1)
  assert.match(h.w.error.value, /草稿仍保留/)
  assert.equal(h.named('updateCopy').length, 1)
})

test('reloading pending submissions restores their exact identity without automatically resubmitting', async () => {
  const store = memoryStorage(), first = fixture({ updateCopy: async () => { throw httpError(500, '连接中断') } }, 21, store)
  await ready(first); editCopy(first); await first.w.saveDraft()
  const record = clone(first.w.pending.value), submitted = clone(first.named('updateCopy')[0])
  first.w.dispose()
  const restored = fixture({}, 21, store)
  assert.equal(restored.w.pending.value.mutationId, record.mutationId)
  await ready(restored)
  assert.equal(restored.named('updateCopy').length, 0)
  assert.equal(await restored.w.retry(), true)
  assert.deepEqual(restored.named('updateCopy')[0], submitted)
  assert.equal(pendingKeys(restored).length, 0)
  assert.equal(draftKeys(restored).length, 0)
})

test('accepted mutation receipts survive a failed refresh, including a 409 from GET, and retry the same request', async () => {
  for (const status of [500, 409]) {
    const h = fixture(); await ready(h); editCopy(h)
    const read = h.api.state
    h.api.state = async () => { throw httpError(status, '当前状态读取失败') }
    assert.equal(await h.w.saveDraft(), false)
    assert.ok(h.w.pending.value, `accepted mutation receipt was dropped after GET ${status}`)
    const first = clone(h.named('updateCopy')[0])
    assert.equal(draftKeys(h).length, 1)
    assert.equal(pendingKeys(h).length, 1)
    h.api.state = read
    h.api.updateCopy = async (...args) => { h.calls.push(['updateCopy', ...clone(args)]); return { ...h.result(), replayed: true } }
    assert.equal(await h.w.retry(), true)
    assert.deepEqual(h.named('updateCopy')[1], first)
    assert.equal(h.w.pending.value, null)
    h.w.dispose()
  }
})

test('a replay displays a fresh GET state rather than an earlier committed or replay response state', async () => {
  const h = fixture(); await ready(h); editCopy(h)
  const responseState = clone(h.server.state)
  responseState.version = 8; responseState.copies[0].content.description = '原提交内容'
  h.api.updateCopy = async () => ({ state: responseState, resultVersion: 8, replayed: true })
  h.server.state.version = 12; h.server.state.copies[0].content.description = '随后其他设备保存的当前内容'
  assert.equal(await h.w.saveDraft(), true)
  assert.equal(h.w.data.value.version, 12)
  assert.equal(h.w.data.value.copies[0].content.description, '随后其他设备保存的当前内容')
  assert.equal(h.w.lastMutation.value.result.resultVersion, 8)
  assert.match(h.w.message.value, /已核对原提交/)
  assert.equal(h.named('state').length, 2)
})

test('late global catalog and detail responses cannot populate a disposed workspace', async () => {
  const catalog = deferred(), detail = deferred()
  const h = fixture({ listTemplates: () => catalog.promise, template: () => detail.promise })
  const fetchingCatalog = h.w.loadCatalog(), fetchingDetail = h.w.loadTemplate(ids.template)
  h.w.dispose()
  catalog.resolve({ items: [h.server.template] }); detail.resolve({ template: h.server.template, revisions: h.server.revisions })
  await Promise.all([fetchingCatalog, fetchingDetail])
  assert.deepEqual(h.w.templates.value, [])
  assert.equal(h.w.details.value, null)
  assert.equal(h.w.revisionUid.value, '')
  assert.equal(h.w.error.value, '')
})

test('a global write completing after disposal keeps its durable receipt and cannot overwrite workspace state', async () => {
  const gate = deferred(), h = fixture({ publishTemplate: () => gate.promise }); await ready(h)
  h.w.editSource(h.server.revisions[0]); h.w.draft.value.payload.notes = '母本新稿'
  const before = clone(h.w.templates.value), saving = h.w.saveDraft(), pending = clone(h.w.pending.value)
  h.w.dispose()
  gate.resolve({ template: { ...h.server.template, lockVersion: 9 }, revision: h.server.revisions[1], replayed: false })
  assert.equal(await saving, false)
  assert.deepEqual(h.w.templates.value, before)
  assert.equal(h.w.details.value, null)
  assert.equal(h.w.pending.value.mutationId, pending.mutationId)
  assert.equal(pendingKeys(h).length, 1)
  assert.equal(h.w.lastMutation.value, null)
})

test('selecting a newer global template request keeps its details when an earlier response arrives late', async () => {
  const first = deferred(), second = deferred()
  const h = fixture({ template: uid => uid === ids.template ? first.promise : second.promise })
  const firstRequest = h.w.loadTemplate(ids.template), otherTemplate = { ...h.server.template, uid: uid(51), name: '另一母本' }
  const secondRequest = h.w.loadTemplate(otherTemplate.uid)
  second.resolve({ template: otherTemplate, revisions: [] }); assert.equal(await secondRequest, true)
  first.resolve({ template: h.server.template, revisions: h.server.revisions }); assert.equal(await firstRequest, false)
  assert.equal(h.w.details.value.template.uid, otherTemplate.uid)
  assert.equal(h.w.selectedTemplateUid.value, otherTemplate.uid)
})

test('world A to B to A invalidates an in-flight comparison and cannot authorize later adoption', async () => {
  const gate = deferred(), h = fixture({ compare: () => gate.promise }); await ready(h)
  h.w.selectCopy(ids.copyA); await settle()
  const comparing = h.w.compare(ids.newerRevision)
  h.w.selectWorld(ids.worldB); h.w.selectWorld(ids.worldA); h.w.selectCopy(ids.copyA)
  gate.resolve(comparison(h.server)); await comparing; await settle()
  assert.equal(h.w.comparison.value, null)
  assert.deepEqual(h.w.selectedFields.value, [])
  h.w.selectedFields.value = ['description']
  assert.equal(await h.w.adopt(), false)
  assert.equal(h.named('adopt').length, 0)
  assert.equal(h.named('review').length, 0)
})

test('comparison requires explicit field selection; review is a distinct action with the pinned version', async () => {
  const h = fixture(); await ready(h); h.w.selectCopy(ids.copyA); await settle()
  await h.w.compare(ids.newerRevision)
  assert.deepEqual(h.w.selectedFields.value, [])
  assert.equal(await h.w.adopt(), false)
  assert.equal(h.named('adopt').length, 0)
  assert.equal(h.named('review').length, 0)
  h.server.template.headRevisionUid = uid(99)
  assert.equal(await h.w.adopt(true), true)
  const call = h.named('review')[0]
  assert.equal(call[1], 21)
  assert.equal(call[2], ids.copyA)
  assert.equal(call[3].revisionUid, ids.newerRevision)
  assert.equal(call[3].baselineRevisionUid, ids.revision)
  assert.equal(call[3].copyHash, 'frozen-copy-hash')
  assert.equal(Object.hasOwn(call[3], 'selectedFields'), false)
})

test('explicit adoption freezes selected fields and cannot drift to a newer source head', async () => {
  const gate = deferred(), h = fixture({ adopt: () => gate.promise }); await ready(h)
  h.w.selectCopy(ids.copyA); await settle(); await h.w.compare(ids.newerRevision)
  h.w.selectedFields.value = ['notes']
  const saving = h.w.adopt()
  h.w.selectedFields.value.push('culture')
  h.server.template.headRevisionUid = uid(99)
  assert.deepEqual(h.named('adopt')[0][3].selectedFields, ['notes'])
  assert.equal(h.named('adopt')[0][3].revisionUid, ids.newerRevision)
  gate.resolve(h.result()); assert.equal(await saving, true)
})

test('source and local drafts remain isolated from stored revisions and copies while switching editors', async () => {
  const h = fixture(); await ready(h)
  h.w.editSource(h.server.revisions[0]); const sourceUid = h.w.draft.value.uid
  h.w.draft.value.payload.culture = '仅母本草稿中的文化'
  h.w.editCopy(h.w.data.value.copies[0]); const localUid = h.w.draft.value.uid
  h.w.draft.value.payload.culture = '仅本作草稿中的文化'
  assert.notEqual(sourceUid, localUid)
  assert.equal(h.w.drafts.value.find(draft => draft.uid === sourceUid).payload.culture, '仅母本草稿中的文化')
  assert.equal(h.w.drafts.value.find(draft => draft.uid === localUid).payload.culture, '仅本作草稿中的文化')
  assert.equal(h.w.data.value.copies[0].content.culture, '旧文化')
  assert.equal(h.w.data.value.copies[1].content.culture, '旧文化')
  assert.equal(h.server.revisions[0].payload.culture, '旧文化')
  assert.equal(draftKeys(h).length, 2)
  h.w.edit(h.w.drafts.value.find(draft => draft.uid === sourceUid))
  h.w.draft.value.payload.description = '返回母本继续写'
  assert.equal(h.w.drafts.value.find(draft => draft.uid === localUid).payload.description, '原始中文\n第二行')
})

test('browser drafts and pending requests remain scoped to their explicit novel', async () => {
  const store = memoryStorage(), first = fixture({ updateCopy: async () => { throw httpError(500) } }, 21, store)
  await ready(first); editCopy(first); await first.w.saveDraft(); first.w.dispose()
  const other = fixture({}, 22, store); await ready(other)
  assert.deepEqual(other.w.drafts.value, [])
  assert.equal(other.w.pending.value, null)
  assert.equal(other.w.canMutate.value, true)
  editCopy(other); assert.equal(await other.w.saveDraft(), true)
  assert.equal(other.named('updateCopy')[0][1], 22)
  assert.ok([...store.entries.keys()].some(key => key.startsWith('series-pending:21:')))
})

test('a restored document epoch cannot be rebased or overwritten by an old draft', async () => {
  const h = fixture(); await ready(h); editCopy(h)
  const old = clone(h.w.draft.value)
  h.server.state.epoch = uid(100); h.server.state.version = 20
  await h.w.loadState()
  assert.equal(h.w.draftEpochChanged.value, true)
  assert.equal(h.w.draftConflict.value, true)
  assert.equal(h.w.rebaseDraft(), false)
  assert.equal(await h.w.saveDraft(), false)
  assert.deepEqual(clone(h.w.draft.value), old)
  assert.equal(h.named('updateCopy').length, 0)
})

test('same-epoch conflict rebasing is explicit and retains the author draft while updating its comparison baseline', async () => {
  const h = fixture(); await ready(h); editCopy(h)
  h.server.state.version++; h.server.state.copies[0].content.description = '另一设备当前文字'
  await h.w.loadState()
  assert.equal(await h.w.saveDraft(), false)
  assert.equal(h.named('updateCopy').length, 0)
  assert.equal(h.w.rebaseDraft(), true)
  assert.equal(h.w.draft.value.expectedVersion, 8)
  assert.equal(h.w.draft.value.baseline.description, '另一设备当前文字')
  assert.equal(h.w.draft.value.payload.description, '本作草稿\n保留空格  ')
  assert.equal(h.w.draftConflict.value, false)
})

test('storage failure prevents writes before a recoverable mutation receipt can be saved', async () => {
  const h = fixture(); await ready(h); editCopy(h)
  h.store.faults.write = true
  h.w.draft.value.payload.notes = '尚未成功暂存的笔记'
  assert.equal(h.w.unsafelyStored.value, true)
  assert.equal(await h.w.saveDraft(), false)
  assert.equal(h.named('updateCopy').length, 0)
  assert.equal(h.w.pending.value, null)
  assert.match(h.w.storageError.value, /尚未提交/)
  assert.equal(h.w.draft.value.payload.notes, '尚未成功暂存的笔记')
  h.store.faults.write = false; h.w.persistDraft()
  assert.equal(await h.w.saveDraft(), true)
  assert.equal(h.named('updateCopy').length, 1)
})

test('unreadable recovery records block writes and preserve the original bytes for export', async () => {
  const store = memoryStorage(new Map([['series-pending:21:broken', '{invalid JSON']]))
  const h = fixture({}, 21, store); await ready(h)
  assert.notEqual(h.w.recoveryError.value, '')
  assert.equal(h.w.canMutate.value, false)
  assert.equal(await h.w.mutate('archiveTemplate', ids.template, { expectedLockVersion: 3, archived: true }, '已归档'), false)
  assert.equal(h.named('archiveTemplate').length, 0)
  assert.equal(store.entries.get('series-pending:21:broken'), '{invalid JSON')
  assert.equal(JSON.parse(h.w.recoveryText()).original['series-pending:21:broken'], '{invalid JSON')
})

test('a slower earlier state GET cannot overwrite a newer state GET', async () => {
  const older = deferred(), newer = deferred(); let reads = 0
  const h = fixture({ state: () => ++reads === 1 ? older.promise : newer.promise })
  const first = h.w.loadState(), second = h.w.loadState()
  const latest = clone(h.server.state); latest.version = 11; latest.copies[0].content.notes = '较新的状态'
  newer.resolve(latest); assert.equal(await second, true)
  older.resolve(h.server.state); assert.equal(await first, false)
  assert.equal(h.w.data.value.version, 11)
  assert.equal(h.w.data.value.copies[0].content.notes, '较新的状态')
})

test('a read started before a mutation cannot overwrite its refreshed committed state', async () => {
  const h = fixture(); await ready(h); editCopy(h)
  const gate = deferred(), oldState = clone(h.server.state), ordinaryRead = h.api.state
  h.api.state = () => gate.promise
  const oldRead = h.w.loadState()
  h.api.state = ordinaryRead
  assert.equal(await h.w.saveDraft(), true)
  gate.resolve(oldState); assert.equal(await oldRead, false)
  assert.equal(h.w.data.value.version, 8)
  assert.equal(h.w.data.value.copies[0].content.description, '本作草稿\n保留空格  ')
})

test('a global mutation follow-up detail read cannot replace a later explicit template selection', async () => {
  const h = fixture(); await ready(h)
  h.w.editSource(h.server.revisions[0]); h.w.draft.value.payload.notes = '已提交的母本新稿'
  const gate = deferred(), otherTemplate = { ...h.server.template, uid: uid(51), name: '随后选择的母本' }
  h.api.template = templateUid => templateUid === ids.template ? gate.promise : Promise.resolve({ template: otherTemplate, revisions: [] })
  const saving = h.w.saveDraft(); await settle()
  assert.equal(h.w.busy.value, true)
  assert.equal(await h.w.loadTemplate(otherTemplate.uid), true)
  gate.resolve({ template: h.server.template, revisions: h.server.revisions })
  assert.equal(await saving, true)
  assert.equal(h.w.details.value.template.uid, otherTemplate.uid)
  assert.equal(h.w.selectedTemplateUid.value, otherTemplate.uid)
})

test('repeated submits and selection switches during a write cannot duplicate or retarget the mutation', async () => {
  const gate = deferred(), h = fixture({ updateCopy: () => gate.promise }); await ready(h)
  h.w.selectCopy(ids.copyA); await settle(); editCopy(h)
  const saving = h.w.saveDraft()
  assert.equal(await h.w.saveDraft(), false)
  assert.equal(await h.w.retry(), false)
  h.w.selectWorld(ids.worldB); h.w.selectCopy(ids.copyB)
  assert.equal(h.w.worldUid.value, ids.worldA)
  assert.equal(h.w.selectedCopyUid.value, ids.copyA)
  assert.equal(h.named('updateCopy').length, 1)
  assert.equal(h.named('updateCopy')[0][2], ids.copyA)
  gate.resolve(h.result()); assert.equal(await saving, true)
})

test('unfinished or oversized but structurally valid drafts survive refresh and remain editable while server saves reject them', async () => {
  for (const invalid of ['whitespace-name', 'payload-capacity']) {
    const store = memoryStorage(), first = fixture({}, 21, store); await ready(first); editCopy(first)
    if (invalid === 'whitespace-name') first.w.draft.value.payload.name = '  \n\t  '
    else {
      for (const key of Object.keys(first.w.draft.value.payload).filter(key => key !== 'name')) first.w.draft.value.payload[key] = '中'.repeat(20000)
    }
    const draft = clone(first.w.draft.value)
    assert.equal(await first.w.saveDraft(), false)
    assert.equal(first.named('updateCopy').length, 0)
    first.w.dispose()
    const restored = fixture({}, 21, store); await ready(restored)
    assert.equal(restored.w.recoveryError.value, '', invalid)
    assert.equal(restored.w.drafts.value.length, 1)
    restored.w.edit(restored.w.drafts.value[0])
    assert.deepEqual(clone(restored.w.draft.value), draft)
    assert.equal(await restored.w.saveDraft(), false)
    assert.equal(restored.named('updateCopy').length, 0)
    restored.w.draft.value.payload.name = '修正后的名称'
    restored.w.draft.value.payload.description = '继续编辑，未丢弃原文'
    assert.equal(restored.w.draft.value.payload.description, '继续编辑，未丢弃原文')
    restored.w.dispose()
  }
})

test('successful submission cannot delete a newer same-UID browser draft saved in another tab', async () => {
  const gate = deferred(), h = fixture({ updateCopy: () => gate.promise }); await ready(h); editCopy(h)
  const key = draftKeys(h)[0], saving = h.w.saveDraft()
  const otherTabDraft = JSON.parse(h.store.entries.get(key))
  otherTabDraft.payload.notes = '另一页面在请求途中保存的新文字'
  h.store.entries.set(key, JSON.stringify(otherTabDraft))
  gate.resolve(h.result()); assert.equal(await saving, true)
  assert.equal(JSON.parse(h.store.entries.get(key)).payload.notes, '另一页面在请求途中保存的新文字')
  assert.equal(h.w.drafts.value.find(draft => draft.uid === otherTabDraft.uid).payload.notes, '另一页面在请求途中保存的新文字')
  assert.equal(h.w.pending.value, null)
  h.w.dispose()
  assert.equal(JSON.parse(h.store.entries.get(key)).payload.notes, '另一页面在请求途中保存的新文字')
})

test('failed draft persistence blocks every editor replacement and preserves unsaved RAM text until storage recovers', async () => {
  for (const destination of ['new-source', 'resume-existing', 'different-kind-fork']) {
    const h = fixture(); await ready(h)
    h.w.editSource(h.server.revisions[0]); h.w.draft.value.payload.notes = '草稿 B 已落盘的文字'
    const draftB = clone(h.w.draft.value)
    h.w.editSource(h.server.revisions[0]); h.w.draft.value.payload.notes = '草稿 A 已落盘的文字'
    const draftA = clone(h.w.draft.value), keyA = `series-draft:21:${draftA.uid}`
    assert.equal(JSON.parse(h.store.entries.get(keyA)).payload.notes, '草稿 A 已落盘的文字')
    assert.equal(draftKeys(h).length, 2)

    h.store.faults.write = true
    const latest = '  草稿 A 只在内存中的新文字\n第二行 🌙\n末尾空格  '
    h.w.draft.value.payload.notes = latest
    h.w.editorOpen.value = false
    const fork = h.w.newDraft('source', 'character')
    const attempt = () => {
      if (destination === 'new-source') h.w.editSource()
      else if (destination === 'resume-existing') h.w.edit(draftB)
      else h.w.edit(fork)
    }
    attempt()

    assert.equal(h.w.draft.value.uid, draftA.uid, destination)
    assert.equal(h.w.draft.value.kind, draftA.kind, destination)
    assert.equal(h.w.draft.value.payload.notes, latest, destination)
    assert.equal(h.w.editorOpen.value, true, destination)
    assert.match(h.w.storageError.value, /暂存失败/)
    const recovery = JSON.parse(h.w.recoveryText())
    assert.equal(recovery.draft.uid, draftA.uid, destination)
    assert.equal(recovery.draft.payload.notes, latest, destination)
    assert.equal(JSON.parse(recovery.original[keyA]).payload.notes, '草稿 A 已落盘的文字')
    assert.equal(draftKeys(h).length, 2)
    assert.equal(h.named('publishTemplate').length, 0)

    h.store.faults.write = false
    attempt()
    assert.notEqual(h.w.draft.value.uid, draftA.uid, destination)
    assert.equal(h.w.editorOpen.value, true)
    assert.equal(h.w.storageError.value, '')
    assert.equal(JSON.parse(h.store.entries.get(keyA)).payload.notes, latest, destination)
    assert.equal(h.w.drafts.value.find(draft => draft.uid === draftA.uid).payload.notes, latest)
    if (destination === 'resume-existing') {
      assert.equal(h.w.draft.value.uid, draftB.uid)
      assert.equal(h.w.draft.value.payload.notes, '草稿 B 已落盘的文字')
    }
    if (destination === 'different-kind-fork') {
      assert.equal(h.w.draft.value.uid, fork.uid)
      assert.equal(h.w.draft.value.kind, 'character')
    }
    h.w.dispose()
  }
})
