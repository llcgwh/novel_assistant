import test from 'node:test'
import assert from 'node:assert/strict'
import { createPinia, setActivePinia } from 'pinia'
import { compileSources } from './helpers/load-source.mjs'
const load = compileSources()
const { writingDeskApi: api } = await load('api/writingDesk')
const { writingApi } = await load('api/writing')
const { useIdeasStore } = await load('stores/ideas')
const { notebookKey } = await load('utils/studio')
const clone = value => JSON.parse(JSON.stringify(value))
const empty = () => ({ version: 0, ideas: [], nextPen: { opening: '保留下一笔' }, tasks: [{ uid: 'task' }], rounds: [{ uid: 'round' }], bookmarks: [{ uid: 'bookmark' }] })
const idea = body => ({ uid: 'idea-a', title: '灯塔', body, category: 'scene', chapterUids: ['chapter-a', 'chapter-b'], createdAt: '2026-10-04T00:00:00Z', updatedAt: '2026-10-04T00:00:00Z' })
function setup() {
  const storage = new Map(), servers = new Map([[1, empty()], [2, empty()]])
  let storageFails = false, readFails = false
  const writes = []
  globalThis.localStorage = {
    getItem(key) { if (readFails) throw Error('denied'); return storage.get(key) || null },
    setItem(key, value) { if (storageFails) throw Error('quota'); storage.set(key, value) },
    removeItem(key) { if (storageFails) throw Error('quota'); storage.delete(key) },
  }
  api.get = async id => clone(servers.get(id))
  writingApi.workspace = async () => ({ chapters: [{ uid: 'chapter-a', title: '第一章', deleted: false }, { uid: 'chapter-b', title: '第二章', deleted: false }] })
  api.save = async (id, payload) => {
    writes.push(clone(payload))
    const server = servers.get(id)
    if (server.mutationId === payload.mutationId) return clone(server)
    if (server.version !== payload.version) throw { response: { status: 409 } }
    const next = { ...clone(payload), version: server.version + 1 }
    servers.set(id, next)
    return clone(next)
  }
  function fresh() { setActivePinia(createPinia()); return useIdeasStore() }
  return { store: fresh(), fresh, storage, servers, writes, failStorage(value = true) { storageFails = value }, failRead(value = true) { readFails = value } }
}
test('idea drafts save category and multiple chapters without changing existing desk data', async () => {
  const h = setup(), s = h.store
  await s.load(1); s.edit(); s.draft.idea = idea('原始\n\n对白 😀')
  await s.saveDraft()
  assert.equal(s.draft, null)
  assert.deepEqual(s.ideas, [h.writes[0].ideas[0]])
  assert.deepEqual(s.ideas[0].chapterUids, ['chapter-a', 'chapter-b'])
  for (const key of ['nextPen', 'tasks', 'rounds', 'bookmarks']) assert.deepEqual(s.data[key], empty()[key])
})
test('unsaved draft survives refresh with baseline and precise text', async () => {
  const h = setup(), a = h.store
  await a.load(1); a.edit(); a.draft.idea.body = '未提交\r\n\n灵感 🧭  '
  const b = h.fresh(); await b.load(1)
  assert.equal(b.draft.idea.body, a.draft.idea.body)
  assert.equal(b.draft.idea.uid, a.draft.idea.uid)
  assert.equal(b.draft.baseline, '')
  assert.equal(h.servers.get(1).ideas.length, 0)
})
test('a lost accepted response survives refresh and retries the exact mutation once', async () => {
  const h = setup(), a = h.store, realSave = api.save
  await a.load(1); a.edit(); a.draft.idea.body = '响应用尽仍保留'
  api.save = async (id, value) => { await realSave(id, value); throw Error('response lost') }
  await assert.rejects(a.saveDraft(), /response lost/)
  const first = clone(a.pending)
  const b = h.fresh(); await b.load(1)
  assert.deepEqual(b.pending, first)
  api.save = realSave
  await b.retryPending()
  assert.deepEqual(h.writes[0], h.writes[1])
  assert.equal(h.servers.get(1).version, 1)
  assert.equal(h.servers.get(1).ideas.length, 1)
  assert.equal(b.pending, null)
  assert.equal(b.draft, null)
})
test('pending requests cannot be replaced by edits until an explicit latest read', async () => {
  const h = setup(), s = h.store
  await s.load(1); s.edit(); s.draft.idea.body = '第一次'
  api.save = async () => { throw Error('offline') }
  await assert.rejects(s.saveDraft(), /offline/)
  s.draft.idea.body = '第二次'
  await assert.rejects(s.saveDraft(), /上次保存结果/)
  await s.reload()
  assert.equal(s.pending, null)
  assert.equal(s.draft.idea.body, '第二次')
})
test('remote conflicts keep original baseline and require compare then explicit overwrite', async () => {
  const h = setup(), s = h.store
  h.servers.get(1).ideas = [idea('原文')]
  await s.load(1); s.edit(s.ideas[0]); s.draft.idea.body = '本机改写'
  h.servers.set(1, { ...empty(), version: 2, ideas: [idea('远端改写')] })
  await assert.rejects(s.saveDraft())
  assert.match(s.error, /另一处更新/)
  await s.reload()
  assert.equal(s.latestIdea.body, '远端改写')
  assert.equal(s.draft.idea.body, '本机改写')
  assert.equal(s.conflicted, true)
  await assert.rejects(s.saveDraft(), /比较/)
  await s.saveDraft(true)
  assert.equal(s.ideas[0].body, '本机改写')
})
test('legacy preview and cancel make no writes; migration keeps original and does not duplicate', async () => {
  const h = setup(), s = h.store, legacy = '  旧稿😀\r\n\r\n结尾\n'
  h.storage.set(notebookKey(1), legacy)
  await s.load(1)
  assert.equal(s.legacyText, legacy)
  assert.equal(h.writes.length, 0)
  const refreshed = h.fresh(); await refreshed.load(1)
  assert.equal(refreshed.legacyText, legacy)
  await refreshed.migrateLegacy()
  assert.equal(h.storage.get(notebookKey(1)), legacy)
  assert.equal(refreshed.ideas.map(row => row.body).join(''), legacy)
  await refreshed.migrateLegacy()
  assert.equal(h.writes.length, 1)
  assert.equal(refreshed.unmigrated.length, 0)
  h.storage.set(notebookKey(1), legacy + '后来新写')
  await refreshed.reload()
  assert.equal(refreshed.unmigrated.length, 1)
  await refreshed.migrateLegacy()
  assert.equal(refreshed.ideas.length, 2)
  assert.equal(refreshed.ideas[1].body, legacy + '后来新写')
})
test('failed migration retains exact retry and original old notebook over refresh', async () => {
  const h = setup(), s = h.store, save = api.save
  h.storage.set(notebookKey(1), '旧文本\n😀')
  await s.load(1)
  api.save = async () => { throw Error('offline') }
  await assert.rejects(s.migrateLegacy())
  const original = clone(s.pending)
  const reopened = h.fresh(); await reopened.load(1)
  assert.deepEqual(reopened.pending, original)
  assert.equal(h.storage.get(notebookKey(1)), '旧文本\n😀')
  api.save = save; await reopened.retryPending()
  assert.equal(reopened.unmigrated.length, 0)
})
test('late novel reads and saves cannot replace another novel state or saving flag', async () => {
  const h = setup(), s = h.store
  let finishRead
  api.get = id => id === 1 ? new Promise(resolve => finishRead = resolve) : Promise.resolve(empty())
  const oldRead = s.load(1)
  await s.load(2); finishRead({ ...empty(), ideas: [idea('旧本')] }); await oldRead
  assert.equal(s.novelId, 2); assert.equal(s.ideas.length, 0)
  api.get = async () => empty()
  await s.load(1); s.edit(); s.draft.idea.body = '第一本草稿'
  let finishOld, finishNew
  api.save = (id, value) => new Promise(resolve => { const finish = () => resolve({ ...clone(value), version: 1 }); if (id === 1) finishOld = finish; else finishNew = finish })
  const oldSave = s.saveDraft()
  await s.load(2); s.edit(); s.draft.idea.body = '第二本草稿'
  const newSave = s.saveDraft()
  finishOld(); await oldSave
  assert.equal(s.saving, true); assert.equal(s.draft.idea.body, '第二本草稿')
  finishNew(); await newSave
  assert.equal(s.ideas[0].body, '第二本草稿')
})
test('storage quota failures retain per-novel in-memory drafts and unsafe state until saving', async () => {
  const h = setup(), s = h.store
  await s.load(1); h.failStorage(); s.edit(); s.draft.idea.body = '不能丢掉'
  assert.equal(s.unsafe, true)
  await s.load(2); s.edit(); s.draft.idea.body = '第二本'
  await s.load(1)
  assert.equal(s.draft.idea.body, '不能丢掉')
  assert.equal(s.unsafe, true)
  await s.saveDraft()
  assert.equal(s.ideas[0].body, '不能丢掉')
  assert.equal(s.unsafe, false)
})
test('malformed local records are retained for raw export and never overwritten', async () => {
  const h = setup(), s = h.store, raw = '{broken but valuable text'
  h.storage.set('ink-ideas-draft-1', raw)
  await s.load(1)
  assert.equal(s.rawRecovery, raw)
  s.edit(); s.draft.idea.body = '新稿'
  assert.equal(h.storage.get('ink-ideas-draft-1'), raw)
  assert.equal(s.unsafe, true)
  await s.load(2); await s.load(1)
  assert.equal(s.rawRecovery, raw)
  assert.equal(s.draft.idea.body, '新稿')
})
test('unreadable legacy storage reports an explicit error without migration writes', async () => {
  const h = setup(), s = h.store
  h.failRead(); await s.load(1)
  assert.match(s.legacyError, /未能读取/)
  assert.equal(h.writes.length, 0)
  h.failRead(false); h.storage.set(notebookKey(1), '恢复原件')
  await s.reload()
  assert.equal(s.legacyText, '恢复原件')
  assert.equal(s.legacyError, '')
})

test('export releases leave protection only for the exact exported draft', async () => {
  const h = setup(), s = h.store
  await s.load(1); h.failStorage(); s.edit(); s.draft.idea.body = '已导出内容'
  assert.equal(s.unsafe, true)
  s.markExported()
  assert.equal(s.unsafe, false)
  s.draft.idea.body += '新改动'
  assert.equal(s.unsafe, true)
})
test('a read-denied older draft cannot be overwritten by a new draft even when writes work', async () => {
  const h = setup(), s = h.store, original = '{unreadable original}'
  h.storage.set('ink-ideas-draft-1', original)
  h.failRead(); await s.load(1)
  s.edit(); s.draft.idea.body = '这次的新稿'
  assert.equal(h.storage.get('ink-ideas-draft-1'), original)
  assert.equal(s.unsafe, true)
  h.failRead(false); await s.reload()
  assert.equal(s.rawRecovery, original)
  assert.equal(s.draft.idea.body, '这次的新稿')
  await s.saveDraft()
  assert.equal(h.storage.get('ink-ideas-draft-1'), original)
})

test('confirmed snapshots flow between local desk panels without self-conflicts or lost fields', async () => {
  const h = setup(), s = h.store
  const { useWritingDeskStore } = await load('stores/writingDesk')
  const desk = useWritingDeskStore()
  await desk.load(1); await s.load(1)
  s.edit(); s.draft.idea.body = '同步素材'; await s.saveDraft()
  assert.equal(desk.data.version, 1)
  assert.equal(desk.data.ideas[0].body, '同步素材')
  await desk.saveBookmark({ uid: 'second-bookmark', chapterUid: 'chapter-a', blockId: '', excerpt: '', label: '稍后读', createdAt: '' })
  assert.equal(s.data.version, 2)
  s.edit(s.ideas[0]); s.draft.idea.category = 'plot'; await s.saveDraft()
  assert.equal(desk.data.version, 3)
  assert.equal(desk.data.bookmarks.length, 2)
  assert.equal(desk.data.ideas[0].category, 'plot')
})
test('a confirmed inbox snapshot never clears another panel uncertain request', async () => {
  const h = setup(), s = h.store
  const { useWritingDeskStore } = await load('stores/writingDesk')
  const desk = useWritingDeskStore()
  await desk.load(1); await s.load(1)
  const realSave = api.save
  api.save = async () => { throw Error('offline') }
  await assert.rejects(desk.saveNextPen(null), /offline/)
  api.save = realSave
  s.edit(); s.draft.idea.body = '灵感'; await s.saveDraft()
  assert.equal(desk.data.version, 0)
  await assert.rejects(desk.saveNextPen({ opening: 'different' }), /重新载入/)
  assert.equal(s.data.version, 1)
})
