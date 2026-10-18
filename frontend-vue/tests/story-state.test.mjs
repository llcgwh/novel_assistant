import test from 'node:test'
import assert from 'node:assert/strict'
import { createPinia, setActivePinia } from 'pinia'
import { compileSources } from './helpers/load-source.mjs'
const load = compileSources()
const { storyOrder, characterStateAt, orderedHistory, appendOnce, emptyStoryState, recordsAt, recordConflicts, readStoryDraft } = await load('utils/storyState')
const { writingDeskApi: api } = await load('api/writingDesk')
const { useWritingDeskStore } = await load('stores/writingDesk')
const clone = value => JSON.parse(JSON.stringify(value))
const state = (uid, storyOrder, narrativeOrder, overrides = {}) => ({ uid, characterId: 1, characterName: '阿青', field: '所在地', value: uid, storyOrder, narrativeOrder, retracted: false, recordedAt: '2026-10-16T10:00:00Z', source: { chapterUid: '', blockId: '', excerpt: '', eventId: null, eventTitle: '', note: '作者记录' }, ...overrides })

test('story chronology, narration and unknown order remain separate, including ties and retractions', () => {
  const rows = [state('first', 1, 5), state('next', 3, 1), state('tied', 3, 6), state('future', 8, 2), state('unknown', null, 0), state('retracted', 4, 4, { retracted: true })]
  const current = characterStateAt(rows, 4)
  assert.deepEqual(current.current.map(r => r.uid), ['next', 'tied'])
  assert.deepEqual(current.undated.map(r => r.uid), ['unknown'])
  assert.deepEqual(orderedHistory(rows, 'narrativeOrder').map(r => r.uid), ['unknown', 'next', 'future', 'retracted', 'first', 'tied'])
  assert.equal(orderedHistory(rows, 'storyOrder').at(-1).uid, 'unknown')
  assert.equal(storyOrder(''), null)
  for (const bad of [-1, 0.5, 'not-a-number', Number.MAX_SAFE_INTEGER + 1]) assert.throws(() => storyOrder(bad))
})

test('retries append once and cannot replace a historical record', () => {
  const rows = [state('first', 1, 1)]
  appendOnce(rows, clone(rows[0])); assert.equal(rows.length, 1)
  assert.throws(() => appendOnce(rows, { ...rows[0], value: 'different' }), /不同内容/)
  appendOnce(rows, state('second', 2, 2)); assert.equal(rows.length, 2)
})

test('characters and readers are projected on independent axes and contradictory ties remain explicit', () => {
  const knowledge = [state('believes', 2, 8, { state: 'believe', belief: '父亲写的' }), state('knows', 6, 2, { state: 'know', belief: '姐姐写的' }), state('suspects', 6, 7, { state: 'suspect', belief: '可能是朋友写的' }), state('unknown-time', null, 1)]
  assert.deepEqual(recordsAt(knowledge, 'storyOrder', 3, r => r.characterId).current.map(r => r.uid), ['believes'])
  const now = recordsAt(knowledge, 'storyOrder', 6, r => r.characterId)
  assert.deepEqual(now.current.map(r => r.uid), ['knows', 'suspects'])
  assert.deepEqual([...recordConflicts(now.current, 'storyOrder', r => r.characterId, r => `${r.state}:${r.belief}`)], ['knows', 'suspects'])
  const reader = [state('hidden', 1, 1, { state: 'hidden' }), state('revealed', 20, 3, { state: 'revealed' })]
  assert.equal(recordsAt(reader, 'narrativeOrder', 3, () => 'reader').current[0].state, 'revealed')
  assert.equal(recordsAt(knowledge, 'storyOrder', 3, r => r.characterId).current[0].state, 'believe')
  assert.deepEqual(recordConflicts([state('same-a', 1, 1), state('same-b', 1, 2, { value: 'same-a' })], 'storyOrder', r => r.field, r => r.value), new Set())
})

test('local draft recovery rejects another novel and malformed fields without inventing defaults', () => {
  const draft = { version: 1, novelId: 1, kind: 'state', uid: 'draft-a', recordedAt: '2026-10-16T10:00:00Z', secretUid: '', characterId: '1', field: '伤势', value: '作者尚未完成的记录', title: '', truth: '', belief: '', detail: '', knowledgeState: 'unknown', readerState: 'hidden', storyOrder: '', narrativeOrder: '', source: state('a', 1, 1).source }
  assert.deepEqual(readStoryDraft(JSON.stringify(draft), 1), draft)
  assert.throws(() => readStoryDraft(JSON.stringify(draft), 2))
  for (const invalid of [{ ...draft, value: null }, { ...draft, source: {} }, { ...draft, knowledgeState: 'revealed' }, { ...draft, recordedAt: 'broken' }]) assert.throws(() => readStoryDraft(JSON.stringify(invalid), 1))
})

function setup() {
  setActivePinia(createPinia())
  let server = { version: 0, nextPen: null, tasks: [], bookmarks: [], rounds: [], ideas: [{ uid: 'idea', body: '保留的灵感' }] }
  api.get = async () => clone(server)
  api.save = async (_, payload) => { assert.equal(payload.version, server.version); server = { ...clone(payload), version: server.version + 1 }; return clone(server) }
  return { store: useWritingDeskStore(), get server() { return server }, set server(value) { server = value } }
}
test('story mutations preserve all other desk content and use the shared version', async () => {
  const { store } = setup(); await store.load(1)
  await store.mutateStoryState(s => appendOnce(s.states, state('first', 1, 1)))
  assert.equal(store.data.version, 1)
  assert.equal(store.data.ideas[0].body, '保留的灵感')
  await store.saveNextPen(null)
  assert.equal(store.data.storyState.states[0].uid, 'first')
  assert.equal(store.data.version, 2)
})
test('lost story save response retries exact payload; conflict reload retains other authors records', async () => {
  const ctx = setup(); await ctx.store.load(1)
  const requests = []
  api.save = async (_, payload) => { requests.push(clone(payload)); if (requests.length === 1) throw Error('response lost'); return { ...clone(payload), version: 1 } }
  const add = s => appendOnce(s.states, state('first', 1, 1))
  await assert.rejects(ctx.store.mutateStoryState(add), /response lost/)
  await ctx.store.mutateStoryState(add)
  assert.deepEqual(requests[0], requests[1])
  api.save = async () => { throw { response: { status: 409 } } }
  await assert.rejects(ctx.store.mutateStoryState(s => appendOnce(s.states, state('local', 3, 3))))
  ctx.server = { ...ctx.server, version: 4, storyState: { ...emptyStoryState(), states: [state('remote', 2, 2)] } }
  await ctx.store.reload()
  assert.equal(ctx.store.data.storyState.states[0].uid, 'remote')
})
test('late state results cannot cross novel boundaries', async () => {
  const { store } = setup(); await store.load(1)
  let finish
  api.save = (_, payload) => new Promise(resolve => { finish = () => resolve({ ...payload, version: 1 }) })
  const pending = store.mutateStoryState(s => appendOnce(s.states, state('old-novel', 1, 1)))
  const rejected = assert.rejects(pending, /作品已切换/)
  await store.load(2); finish(); await rejected
  assert.equal(store.novelId, 2); assert.equal(store.data.storyState, undefined)
})
