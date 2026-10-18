import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import ts from 'typescript'
import { computed, effectScope, nextTick, reactive, ref, watch } from 'vue'
import { compileSources } from './helpers/load-source.mjs'

const utils = await compileSources()('utils/ai')
const source = readFileSync(new URL('../src/components/ai/AiAssistantPanel.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
const js = ts.transpileModule(script, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText.replace(/^import .* from .*\n/gm, '')
const clone = value => JSON.parse(JSON.stringify(value))
const direction = { title: '抉择', goal: '决定目标', choice: '权衡两种选择', consequence: '分别承担后果', risk: '秘密暴露', question: '是否已有证据', sourceRefs: [], assumptions: ['待作者确认'] }
const emptyDesk = () => ({ version: 0, tasks: [], bookmarks: [], ideas: [], rounds: [], nextPen: null, storyState: { marker: 'original' } })
const connection = { configured: true, revision: 'config-1', baseUrl: 'https://provider.example/v1', model: 'test-model' }
function harness(scope = 'character') {
  const props = reactive({ scope, targetId: scope === 'writing' ? undefined : 7, draft: scope === 'writing' ? { notes: '原稿' } : { name: '原名' }, chapterUid: scope === 'writing' ? 'chapter-a' : undefined, disabled: false, initiallyOpen: false })
  const route = reactive({ params: { novelId: '1' } }), values = new Map(), writes = [], emitted = []
  const desk = { acceptExternal() {} }, effects = effectScope()
  let unmount, closeGuard
  const api = {
    config: async () => connection, cancel: async () => {}, contextCurrent: async () => ({ current: true }),
    referenceChapters: async () => ({ maxChars: 10000, chapters: [{ uid: 'chapter-a', title: '第一章', wordCount: 6000 }, { uid: 'chapter-b', title: '第二章', wordCount: 4000 }, { uid: 'chapter-c', title: '第三章', wordCount: 1 }] }),
    suggest: async (_id, action, _context, requestId) => ({ requestId, action, snapshotId: 'a'.repeat(64), model: 'test-model', coverage: {}, sources: [], result: { candidates: scope === 'writing' ? [] : [{ title: '候选', reason: '依据', fields: { [utils.aiTargetField(action)]: '候选名' }, assumptions: [], conflicts: [], sourceRefs: [] }], directions: scope === 'writing' ? [0, 1, 2].map(index => ({ ...clone(direction), title: `方向${index}` })) : [], issues: [] } }),
  }
  const writing = { get: async () => emptyDesk(), save: async (_id, payload) => { writes.push(clone(payload)); return { ...clone(payload), version: payload.version + 1 } } }
  const runtime = {
    ...utils, computed, ref, watch, crypto: { randomUUID },
    defineProps: () => props, withDefaults: value => value,
    defineEmits: () => (name, patch) => { emitted.push({ name, ...patch }); props.draft[patch.field] = patch.value },
    useRoute: () => route, useWritingDeskStore: () => desk, aiApi: api, writingDeskApi: writing,
    onBeforeUnmount: fn => { unmount = fn },
    useModalDraftGuard: guard => { closeGuard = guard },
    localStorage: { get length() { return values.size }, key: index => [...values.keys()][index] ?? null, getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) },
  }
  const component = effects.run(() => new Function(...Object.keys(runtime), `${js}\nreturn { loadConfig, generate, apply, undo, saveDirection, recoverCollection, readCollections, response, error, config, stale, saved, pending, savingIndex, recoveryRows, fieldLabel, action, request, loadReferences, selectReference, clearReferences, selectedReferenceUids, referenceChapters, referenceWordCount, referenceOverLimit, referenceBlocked, referencesError, referencesLoading, referencesLoaded, unavailableReferenceUids, referenceLocked };`)(...Object.values(runtime)))
  return { ...component, props, route, api, writing, values, writes, emitted, closeGuard: () => closeGuard(), async generateReady() { await component.loadConfig(); await component.generate() }, dispose() { unmount(); effects.stop() } }
}
const until = async predicate => { while (!predicate()) await new Promise(resolve => setImmediate(resolve)) }

test('AI child draft protection tracks changed preferences and actions but ignores untouched or reverted inputs', async t => {
  const h = harness(); t.after(() => h.dispose())
  assert.equal(h.closeGuard(), false)
  h.request.value = '保留既有设定'; assert.equal(h.closeGuard(), true)
  h.request.value = ''; assert.equal(h.closeGuard(), false)
  const first = h.action.value
  h.action.value = utils.aiActions.character[1].action; await nextTick(); assert.equal(h.closeGuard(), true)
  h.action.value = first; await nextTick(); assert.equal(h.closeGuard(), false)
})

test('unadopted candidates and uncollected writing directions remain guarded until explicitly used', async t => {
  const h = harness(); t.after(() => h.dispose()); await h.generateReady()
  assert.equal(h.closeGuard(), true)
  await h.apply(h.response.value.result.candidates[0]); assert.equal(h.closeGuard(), false)
  h.undo(); assert.equal(h.closeGuard(), true)
  const writing = harness('writing'); t.after(() => writing.dispose()); await writing.generateReady()
  assert.equal(writing.closeGuard(), true)
  for (const [index, candidate] of writing.response.value.result.directions.entries()) await writing.saveDirection(candidate, index)
  assert.equal(writing.closeGuard(), false)
})

test('actual candidate adoption waits for freshness and undo preserves later manual editing', async t => {
  const h = harness(); t.after(() => h.dispose()); await h.generateReady()
  const candidate = h.response.value.result.candidates[0]
  assert.equal(h.fieldLabel.value, '姓名')
  await h.apply(candidate); assert.equal(h.props.draft.name, '候选名')
  h.undo(); assert.equal(h.props.draft.name, '原名')
  await h.apply(candidate); h.props.draft.name = '作者改名'; h.undo()
  assert.equal(h.props.draft.name, '作者改名'); assert.match(h.error.value, /不能自动撤销/)
})

test('author changes or disabled state during asynchronous freshness checks prevent adoption', async t => {
  for (const change of ['draft', 'disabled']) {
    const h = harness(); t.after(() => h.dispose()); await h.generateReady()
    let release; h.api.contextCurrent = () => new Promise(resolve => { release = resolve })
    const pending = h.apply(h.response.value.result.candidates[0]); await until(() => release)
    if (change === 'draft') h.props.draft.name = '作者的新输入'
    else h.props.disabled = true
    release({ current: true }); await pending
    assert.equal(h.emitted.length, 0)
  }
})

test('several directions can be collected from one snapshot without another model or freshness request', async t => {
  const h = harness('writing'); t.after(() => h.dispose()); await h.generateReady()
  let server = emptyDesk(), requests = 0
  h.api.contextCurrent = async () => { requests++; throw Error('Collections must not revalidate their own changed desk') }
  h.writing.get = async () => clone(server)
  h.writing.save = async (_id, payload) => { server = { ...clone(payload), version: payload.version + 1 }; return clone(server) }
  await h.saveDirection(direction, 0)
  await h.saveDirection({ ...direction, title: '第二个方向' }, 1)
  assert.equal(requests, 0); assert.equal(server.ideas.length, 2)
  assert.match(server.ideas[0].body, /参考快照：a{64}/); assert.match(server.ideas[0].body, /收藏不代表采用为正式设定/)
  assert.equal(h.saved.value.has(0), true); assert.equal(h.saved.value.has(1), true)
})

test('a draft edit while the desk loads still allows explicitly chosen old-snapshot inspiration with provenance', async t => {
  const h = harness('writing'); t.after(() => h.dispose()); await h.generateReady()
  let release; h.writing.get = () => new Promise(resolve => { release = resolve })
  const pending = h.saveDirection(direction, 0); await until(() => release)
  h.props.draft.notes = '作者已推进正文'; await nextTick(); release(emptyDesk()); await pending
  assert.equal(h.writes.length, 1); assert.equal(h.values.size, 0); assert.equal(h.savingIndex.value, null)
  assert.match(h.writes[0].ideas[0].body, /生成时间：/); assert.match(h.writes[0].ideas[0].body, /此候选依据生成时的资料/)
  assert.equal(h.stale.value, true)
})

test('changing books while collection prerequisites load never writes to either book', async t => {
  const h = harness('writing'); t.after(() => h.dispose()); await h.generateReady()
  let release; h.writing.get = () => new Promise(resolve => { release = resolve })
  const pending = h.saveDirection(direction, 0); await until(() => release)
  h.route.params.novelId = '2'; await nextTick(); release(emptyDesk()); await pending
  assert.equal(h.writes.length, 0); assert.equal(h.values.size, 0)
})

test('failed prerequisite read does not claim a collection was submitted or retained', async t => {
  const h = harness('writing'); t.after(() => h.dispose()); await h.generateReady()
  h.writing.get = async () => { throw Error('读取便签失败') }
  await h.saveDirection(direction, 0)
  assert.match(h.error.value, /收藏尚未发送.*读取便签失败/)
  assert.equal(h.pending.value.size, 0); assert.equal(h.recoveryRows.value.length, 0); assert.equal(h.writes.length, 0)
})

test('unknown collection outcome replays the original payload; after conflict both retry controls use the refreshed merge', async t => {
  const h = harness('writing'); t.after(() => h.dispose()); await h.generateReady()
  const writes = []
  h.writing.save = async (_id, payload) => { writes.push(clone(payload)); throw Error('response lost') }
  await h.saveDirection(direction, 0)
  const original = clone(h.recoveryRows.value[0])
  assert.equal(h.values.size, 1); assert.deepEqual(original.payload, writes[0])
  h.writing.get = async () => ({ ...emptyDesk(), version: 5, ideas: [{ uid: 'author-idea', title: '作者新灵感' }], storyState: { marker: 'new-author-state' } })
  h.writing.save = async (_id, payload) => { writes.push(clone(payload)); throw { response: { status: 409 } } }
  await h.recoverCollection(original)
  assert.deepEqual(writes[1], original.payload)
  const refreshed = clone(h.recoveryRows.value[0].payload)
  assert.equal(refreshed.version, 5); assert.equal(refreshed.storyState.marker, 'new-author-state')
  assert.notEqual(refreshed.mutationId, original.payload.mutationId)
  assert.deepEqual(h.pending.value.get(0), refreshed)
  h.writing.save = async (_id, payload) => { writes.push(clone(payload)); return { ...clone(payload), version: 6 } }
  await h.saveDirection(direction, 0)
  assert.deepEqual(writes[2], refreshed); assert.equal(h.values.size, 0); assert.equal(h.pending.value.size, 0); assert.equal(h.saved.value.has(0), true)
})

test('recovering another generation at the same index does not delete the current pending payload', async t => {
  const h = harness('writing'); t.after(() => h.dispose()); await h.generateReady()
  h.writing.save = async () => { throw Error('response lost') }; await h.saveDirection(direction, 0)
  const current = clone(h.pending.value.get(0)), other = { key: `ink-ai-pending:1:${randomUUID()}:0`, novelId: 1, index: 0, title: '较早收藏', payload: { ...emptyDesk(), mutationId: randomUUID() } }
  h.values.set(other.key, JSON.stringify(other)); h.readCollections()
  h.writing.save = async (_id, payload) => ({ ...payload, version: 1 }); await h.recoverCollection(other)
  assert.deepEqual(h.pending.value.get(0), current); assert.equal(h.saved.value.has(0), false); assert.equal(h.values.size, 1)
})

test('configuration loading can recover after initial session or connection failure', async t => {
  const h = harness(); t.after(() => h.dispose())
  h.api.config = async () => { throw Error('会话失效') }; await h.loadConfig()
  assert.equal(h.config.value, null); assert.match(h.error.value, /会话失效/)
  h.api.config = async () => connection; await h.loadConfig()
  assert.equal(h.config.value.configured, true); assert.equal(h.error.value, '')
})

test('settings generation defaults to no selected manuscript and never includes an unsaved writing draft', async t => {
  const h = harness(); t.after(() => h.dispose())
  let context, reads = 0
  const suggest = h.api.suggest
  h.props.currentDraft = { uid: 'unsaved', doc: { text: '未保存正文' } }
  h.api.referenceChapters = async () => { reads++; throw Error('未展开不应读取') }
  h.api.suggest = async (...args) => { context = clone(args[2]); return suggest(...args) }
  await h.generateReady()
  assert.deepEqual(context.referenceChapterUids, [])
  assert.equal('currentDraft' in context, false); assert.equal(reads, 0)
  assert.deepEqual(h.props.currentDraft.doc, { text: '未保存正文' })
})

test('chapter selections use saved server word counts, allow the exact limit, and never truncate an over-limit request', async t => {
  const h = harness(); t.after(() => h.dispose()); await h.loadConfig(); await h.loadReferences()
  const calls = [], suggest = h.api.suggest
  h.api.suggest = async (...args) => { calls.push(clone(args[2])); return suggest(...args) }
  h.selectReference('chapter-b', true); h.selectReference('chapter-a', true); h.selectReference('chapter-a', true)
  assert.equal(h.referenceWordCount.value, 10000); assert.equal(h.referenceOverLimit.value, false)
  await h.generate()
  assert.deepEqual(calls[0].referenceChapterUids, ['chapter-a', 'chapter-b'])
  h.selectReference('chapter-c', true)
  assert.equal(h.referenceWordCount.value, 10001); assert.equal(h.referenceBlocked.value, true)
  await h.generate()
  assert.equal(calls.length, 1); assert.deepEqual([...h.selectedReferenceUids.value].sort(), ['chapter-a', 'chapter-b', 'chapter-c'])
  h.selectReference('chapter-c', false); await h.generate()
  assert.equal(calls.length, 2); assert.equal(h.referenceBlocked.value, false)
})

test('changing selected chapters marks suggestions stale and the original selected IDs reach freshness validation', async t => {
  const h = harness(); t.after(() => h.dispose()); await h.loadReferences(); h.selectReference('chapter-a', true); await h.generateReady()
  const candidate = h.response.value.result.candidates[0], checks = []
  h.api.contextCurrent = async (...args) => { checks.push(clone(args[2])); return { current: true } }
  h.selectReference('chapter-a', false); h.selectReference('chapter-b', true)
  assert.equal(h.stale.value, true); await h.apply(candidate); assert.equal(checks.length, 0)
  h.clearReferences(); h.selectReference('chapter-a', true)
  assert.equal(h.stale.value, false); await h.apply(candidate)
  assert.deepEqual(checks[0].referenceChapterUids, ['chapter-a'])
  assert.equal(h.props.draft.name, '候选名')
})

test('opening and reading chapter choices stays clean; choosing then clearing restores the child draft baseline', async t => {
  const h = harness(); t.after(() => h.dispose())
  await h.loadReferences(); assert.equal(h.closeGuard(), false)
  h.selectReference('chapter-a', true); assert.equal(h.closeGuard(), true)
  h.clearReferences(); assert.equal(h.closeGuard(), false)
  assert.deepEqual(h.props.draft, { name: '原名' }); assert.equal(h.writes.length, 0)
})

test('selection controls are locked while generating, applying, or awaiting a collection outcome', async t => {
  const h = harness(); t.after(() => h.dispose()); await h.loadReferences(); h.selectReference('chapter-a', true); await h.loadConfig()
  let release
  const suggest = h.api.suggest
  h.api.suggest = async (...args) => { await new Promise(resolve => { release = resolve }); return suggest(...args) }
  const generating = h.generate(); await until(() => release)
  assert.equal(h.referenceLocked.value, true); h.clearReferences(); h.selectReference('chapter-b', true)
  assert.deepEqual(h.selectedReferenceUids.value, ['chapter-a']); release(); await generating
  release = undefined
  h.api.contextCurrent = () => new Promise(resolve => { release = resolve })
  const adopting = h.apply(h.response.value.result.candidates[0]); await until(() => release)
  h.clearReferences(); h.selectReference('chapter-b', true)
  assert.deepEqual(h.selectedReferenceUids.value, ['chapter-a']); release({ current: true }); await adopting
  h.pending.value.set(0, emptyDesk())
  h.clearReferences(); h.selectReference('chapter-b', true)
  assert.deepEqual(h.selectedReferenceUids.value, ['chapter-a'])
  h.pending.value.clear(); h.clearReferences(); assert.deepEqual(h.selectedReferenceUids.value, [])
})

test('metadata refresh failure remains visible, preserves selections, and permits generation only after clearing or successful retry', async t => {
  const h = harness(); t.after(() => h.dispose()); await h.loadReferences(); h.selectReference('chapter-a', true); await h.loadConfig()
  const referenceChapters = h.api.referenceChapters, calls = []
  h.api.suggest = async () => { calls.push('suggest'); throw Error('provider') }
  h.api.referenceChapters = async () => { throw Error('章节列表暂时不可用') }; await h.loadReferences()
  assert.match(h.referencesError.value, /章节列表暂时不可用/)
  assert.deepEqual(h.selectedReferenceUids.value, ['chapter-a']); assert.equal(h.referenceBlocked.value, true)
  await h.generate(); assert.equal(calls.length, 0)
  h.clearReferences(); await h.generate(); assert.equal(calls.length, 1)
  assert.match(h.referencesError.value, /章节列表暂时不可用/)
  h.api.referenceChapters = referenceChapters; await h.loadReferences(); assert.equal(h.referencesError.value, '')
  h.selectReference('chapter-a', true); assert.equal(h.referenceBlocked.value, false)
})

test('server-side manuscript changes can reject generation without losing selected chapters or masking the failure', async t => {
  const h = harness(); t.after(() => h.dispose()); await h.loadReferences(); h.selectReference('chapter-a', true)
  h.api.suggest = async () => { throw Error('已保存正文超过 10000 字，未发送且未截断。') }
  await h.generateReady()
  assert.match(h.error.value, /超过 10000 字.*未截断/)
  assert.deepEqual(h.selectedReferenceUids.value, ['chapter-a']); assert.equal(h.referenceLocked.value, false)
  h.api.referenceChapters = async () => ({ maxChars: 10000, chapters: [{ uid: 'chapter-a', title: '第一章', wordCount: 10001 }] })
  await h.loadReferences(); assert.equal(h.referenceOverLimit.value, true)
})

test('deleted selected chapters remain explicit and block generation until the author clears them', async t => {
  const h = harness(); t.after(() => h.dispose()); await h.loadReferences(); h.selectReference('chapter-a', true)
  h.api.referenceChapters = async () => ({ maxChars: 10000, chapters: [] }); await h.loadReferences()
  assert.deepEqual(h.unavailableReferenceUids.value, ['chapter-a']); assert.equal(h.referenceBlocked.value, true)
  assert.deepEqual(h.selectedReferenceUids.value, ['chapter-a'])
  h.clearReferences(); assert.equal(h.referenceBlocked.value, false); assert.equal(h.closeGuard(), false)
})

test('changing books or editing targets aborts metadata reads and discards late chapter lists without retaining selections', async t => {
  for (const change of ['book', 'target']) {
    const h = harness(); t.after(() => h.dispose()); await h.loadReferences(); h.selectReference('chapter-a', true)
    let release, signal
    h.api.referenceChapters = (_id, abortSignal) => { signal = abortSignal; return new Promise(resolve => { release = resolve }) }
    const reading = h.loadReferences(); await until(() => release)
    if (change === 'book') { h.route.params.novelId = '2'; await nextTick(); h.route.params.novelId = '1'; await nextTick() }
    else { h.props.targetId = 9; await nextTick() }
    assert.equal(signal.aborted, true)
    release({ maxChars: 10000, chapters: [{ uid: 'late', title: '旧作品章节', wordCount: 1 }] }); await reading
    assert.deepEqual(h.referenceChapters.value, []); assert.deepEqual(h.selectedReferenceUids.value, [])
    assert.equal(h.referencesLoading.value, false); assert.equal(h.referencesLoaded.value, false)
  }
})

test('writing inspiration keeps its existing full-manuscript context without reference selections or metadata reads', async t => {
  const h = harness('writing'); t.after(() => h.dispose())
  h.props.currentDraft = { uid: 'chapter-a', doc: { text: '未保存正文' } }
  let context, reads = 0
  const suggest = h.api.suggest
  h.api.referenceChapters = async () => { reads++; throw Error('writing does not load chapter choices') }
  h.api.suggest = async (...args) => { context = clone(args[2]); return suggest(...args) }
  await h.loadReferences(); await h.generateReady()
  assert.equal(reads, 0); assert.equal('referenceChapterUids' in context, false)
  assert.deepEqual(context.currentDraft.doc, { text: '未保存正文' })
})
