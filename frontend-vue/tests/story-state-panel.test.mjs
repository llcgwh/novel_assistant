import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import 'fake-indexeddb/auto'
import { createPinia, setActivePinia } from 'pinia'
import { computed, effectScope, nextTick, reactive, ref, watch } from 'vue'
import { createMemoryHistory, createRouter, isNavigationFailure, NavigationFailureType } from 'vue-router'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources()
const utils = await load('utils/storyState')
const { blockList, chapterStatuses, plainDocument, documentText } = await load('utils/writing')
const { request, setCurrentNovelId } = await load('api/request')
const { useWritingStore } = await load('stores/writing')
const { saveDraft, listDrafts } = await load('utils/writingDrafts')
const source = readFileSync(new URL('../src/components/story/StoryStatePanel.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
const js = ts.transpileModule(script, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText.replace(/^import .* from .*\n/gm, '')
const clone = value => JSON.parse(JSON.stringify(value))

function harness(rawDraft, options = {}) {
  let mount, unmount, writes = 0
  const emitted = []
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/novel/:novelId/writing', component: {} }, { path: '/novel/:novelId/timeline', component: {} }, { path: '/novel/:novelId/characters', component: {} },
  ] })
  const writer = options.writer || reactive({ novelId: 1, saving: false, recovery: null, conflict: null, async select() { return true } })
  const props = { novelId: 1, initialCharacterId: 7 }, values = new Map(rawDraft ? [['ink:story-state-draft:1', rawDraft]] : [])
  props.navigateChapter = async target => {
    if (!(await writer.select(target.chapterUid))) return false
    const location = { path: '/novel/1/writing', query: { chapter: target.chapterUid, block: target.blockId } }
    await router.push(location)
    return router.currentRoute.value.fullPath === router.resolve(location).fullPath
  }
  const storage = { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
  const desk = reactive({ novelId: 1, data: { storyState: utils.emptyStoryState() }, loading: false, saving: false, error: '', async mutateStoryState(update) { const value = clone(this.data.storyState); update(value); this.data.storyState = value; writes++ }, async reload() {}, async load() {} })
  const runtime = {
    ...utils, blockList, computed, ref, watch, isNavigationFailure, NavigationFailureType,
    defineProps: () => props, defineEmits: () => (...args) => emitted.push(args),
    useWritingDeskStore: () => desk,
    useWritingStore: () => writer, useRouter: () => router,
    onMounted: fn => { mount = fn }, onBeforeUnmount: fn => { unmount = fn },
    request: { get: async path => path.endsWith('/characters') ? [{ id: 7, name: '阿青' }] : [{ id: 9, title: '渡河' }] },
    writingApi: { workspace: async () => ({ chapters: [{ uid: 'chapter-a', title: '第一章', deleted: false }] }), chapter: async () => ({ doc: { type: 'doc', content: [{ type: 'paragraph', attrs: { id: 'p1' }, content: [{ type: 'text', text: '渡河原文' }] }] } }) },
    localStorage: storage,
    window: { addEventListener() {}, removeEventListener() {} }, confirm: () => true,
  }
  const actions = new Function(...Object.keys(runtime), `${js}\nreturn { begin, save, reload, draft, draftDirty, personFilter, axis, at, knowledgePoint, readerPoint, error, uncertain, rawRecovery, persist, chooseEvent, chooseChapter, chooseBlock, knowledgeView, readerView, navigateSource };`)(...Object.values(runtime))
  return { ...actions, props, desk, writer, router, emitted, storage, values, mount: async () => { await router.push(`/novel/1/writing?chapter=${options.initialChapter || 'chapter-a'}`); await mount() }, unmount: () => unmount(), writes: () => writes }
}

test('actual panel saves author state with stable identity and selected chapter paragraph evidence', async () => {
  const h = harness(); await h.mount(); h.begin('state')
  h.draft.value.field = '伤势'; h.draft.value.value = '左臂受伤'; h.draft.value.storyOrder = '4'; h.draft.value.narrativeOrder = '2'
  h.draft.value.source.chapterUid = 'chapter-a'; h.chooseChapter()
  await new Promise(resolve => setImmediate(resolve))
  h.draft.value.source.blockId = 'p1'; h.chooseBlock()
  const uid = h.draft.value.uid
  await h.save()
  const row = h.desk.data.storyState.states[0]
  assert.equal(row.uid, uid); assert.equal(row.characterId, 7); assert.equal(row.characterName, '阿青')
  assert.equal(row.storyOrder, 4); assert.equal(row.narrativeOrder, 2)
  assert.equal(row.source.excerpt, '渡河原文'); assert.equal(row.source.blockId, 'p1')
  assert.equal(h.draft.value, null); assert.equal(h.writes(), 1); h.unmount()
})

test('the shared event select keeps numeric source identity and supports clearing the selection', async () => {
  const h = harness(); await h.mount(); h.begin('state')
  h.chooseEvent(9)
  assert.equal(h.draft.value.source.eventId, 9); assert.equal(h.draft.value.source.eventTitle, '渡河')
  h.chooseEvent('')
  assert.equal(h.draft.value.source.eventId, null); assert.equal(h.draft.value.source.eventTitle, '')
  h.unmount()
})

test('actual panel keeps character beliefs distinct from author truth and reader disclosure', async () => {
  const h = harness(); await h.mount(); h.begin('secret')
  h.draft.value.title = '信件作者'; h.draft.value.truth = '姐姐写的'; await h.save()
  const secret = h.desk.data.storyState.secrets[0]
  h.begin('knowledge', secret.uid); h.draft.value.belief = '父亲写的'; h.draft.value.knowledgeState = 'believe'; h.draft.value.source.note = '作者确认'; await h.save()
  h.begin('reader', secret.uid); h.draft.value.detail = '读者看到了姐姐写信'; h.draft.value.readerState = 'revealed'; h.draft.value.source.note = '作者确认'; await h.save()
  const result = h.desk.data.storyState.secrets[0]
  assert.equal(result.truth, '姐姐写的'); assert.equal(result.knowledge[0].state, 'believe'); assert.equal(result.knowledge[0].belief, '父亲写的')
  assert.equal(result.reader[0].state, 'revealed'); assert.equal(result.knowledge.length, 1); h.unmount()
})

test('story panel closing warning follows edits, not filters, empty forms or saved records', async () => {
  const h = harness(); await h.mount()
  h.personFilter.value = '7'; h.axis.value = 'narrativeOrder'; h.at.value = 3; h.knowledgePoint.value = 2; h.readerPoint.value = 1
  assert.equal(h.draftDirty.value, false)
  h.begin('secret'); assert.equal(h.draftDirty.value, false)
  h.draft.value.title = '未保存秘密'; assert.equal(h.draftDirty.value, true)
  h.draft.value.title = ''; assert.equal(h.draftDirty.value, false)
  h.draft.value.title = '信件作者'; h.draft.value.truth = '姐姐写的'; await h.save()
  assert.equal(h.draftDirty.value, false); h.unmount()
})

test('restored local author records still warn that they have not been saved to the work', async () => {
  const first = harness(); await first.mount(); first.begin('state'); first.draft.value.value = '待确认的作者记录'
  const raw = JSON.stringify(first.draft.value); first.unmount()
  const restored = harness(raw); await restored.mount()
  assert.equal(restored.draftDirty.value, true); assert.equal(restored.draft.value.value, '待确认的作者记录'); restored.unmount()
})

test('actual panel retains a rejected draft, then reload allows comparison before retry', async () => {
  const h = harness(); await h.mount(); h.begin('state'); h.draft.value.field = '目标'; h.draft.value.value = '渡河'; h.draft.value.source.note = '作者记录'
  h.desk.mutateStoryState = async () => { h.desk.error = '另一处更新'; throw Error('版本冲突') }
  const original = clone(h.draft.value)
  await h.save(); assert.deepEqual(h.draft.value, original); assert.equal(h.uncertain.value, true)
  await h.reload(); assert.deepEqual(h.draft.value, original); assert.equal(h.uncertain.value, false); h.unmount()
})

test('malformed local drafts remain byte-for-byte available instead of being cleared on close', async () => {
  const original = '{broken but recoverable author text'
  const h = harness(original); await h.mount(); assert.equal(h.rawRecovery.value, original)
  h.persist(); h.begin('state'); assert.equal(h.draft.value, null)
  assert.equal(h.values.get('ink:story-state-draft:1'), original); h.unmount()
})

test('source navigation in the writing route closes only after reaching the chapter and preserves a safe local draft', async () => {
  const h = harness(); await h.mount(); h.begin('state'); h.draft.value.value = '尚未保存的作者记录'
  let release
  const gate = new Promise(resolve => { release = resolve })
  h.router.beforeEach(to => to.query.chapter === 'chapter-b' ? gate : true)
  const target = { path: '/novel/1/writing', query: { chapter: 'chapter-b', block: 'p2' } }
  const navigation = h.navigateSource(target)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(h.emitted.length, 0, 'The modal must remain while a navigation guard is pending')
  assert.match(h.values.get('ink:story-state-draft:1'), /尚未保存的作者记录/)
  release(true); await navigation
  assert.equal(h.router.currentRoute.value.fullPath, h.router.resolve(target).fullPath)
  assert.deepEqual(h.emitted, [['close']]); h.unmount()
})

test('blocked or redirected source navigation keeps the panel open', async () => {
  for (const decision of [false, { path: '/novel/1/characters' }]) {
    const h = harness(); await h.mount()
    h.router.beforeEach(to => to.path.endsWith('/timeline') ? decision : true)
    await h.navigateSource({ path: '/novel/1/timeline', query: { edit: '9' } })
    assert.equal(h.emitted.length, 0); assert.match(h.error.value, /尚未跳转/); h.unmount()
  }
})

test('saving, uncertain outcomes and unsafe local drafts prevent source navigation', async () => {
  const h = harness(); await h.mount()
  const target = { path: '/novel/1/writing', query: { chapter: 'chapter-b' } }
  h.desk.saving = true; await h.navigateSource(target); h.desk.saving = false
  h.uncertain.value = true; await h.navigateSource(target); h.uncertain.value = false
  h.begin('state'); h.draft.value.value = '必须保留的记录'
  h.storage.setItem = () => { throw Error('storage unavailable') }
  await h.navigateSource(target)
  assert.equal(h.router.currentRoute.value.query.chapter, 'chapter-a')
  assert.equal(h.emitted.length, 0); assert.match(h.error.value, /尚未安全暂存/)
  assert.equal(h.draft.value.value, '必须保留的记录'); h.unmount()
})

test('an unsafe manuscript or failed chapter selection does not close the ledger or change its source route', async () => {
  const h = harness(); await h.mount()
  const target = { path: '/novel/1/writing', query: { chapter: 'chapter-b' } }
  let selections = 0
  h.writer.select = async () => { selections++; return false }
  h.writer.conflict = {}; await h.navigateSource(target)
  assert.equal(selections, 0); assert.match(h.error.value, /稿件冲突/)
  h.writer.conflict = null; await h.navigateSource(target)
  assert.equal(selections, 1); assert.equal(h.router.currentRoute.value.query.chapter, 'chapter-a')
  assert.equal(h.emitted.length, 0); assert.match(h.error.value, /尚未安全打开/); h.unmount()
})

test('an already reached source closes the overlay without treating another navigation failure as success', async () => {
  const h = harness(); await h.mount()
  await h.navigateSource({ path: '/novel/1/writing', query: { chapter: 'chapter-a' } })
  assert.deepEqual(h.emitted, [['close']]); h.unmount()
})

function memoryStorage() {
  const values = new Map()
  return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, String(value)), removeItem: key => values.delete(key) }
}

// Run the real WritingView setup, including its current/route watchers and
// editor-ready handler. Only browser rendering and HTTP transport are replaced.
function attachWritingView(h) {
  const viewSource = readFileSync(new URL('../src/views/WritingView.vue', import.meta.url), 'utf8')
  assert.match(viewSource, /:navigate-chapter="jumpToAnchor"/)
  const script = viewSource.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const js = ts.transpileModule(script, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText.replace(/^import .* from .*\n/gm, '')
  const scope = effectScope(), focused = []
  const runtime = {
    ref, computed, watch, nextTick, chapterStatuses, blockList, documentText,
    useWritingStore: () => h.writer, useWritingDeskStore: () => h.desk,
    useStudioStore: () => ({}), useAppStore: () => ({ showToast() {} }), useIdeasStore: () => ({}),
    useRouter: () => h.router, useRoute: () => new Proxy({}, { get: (_, key) => h.router.currentRoute.value[key] }),
    onMounted() {}, onBeforeUnmount() {}, onBeforeRouteLeave() {},
  }
  const view = scope.run(() => new Function(...Object.keys(runtime), `${js}\nreturn { jumpToAnchor, followRoute, editorReady, editorPanel };`)(...Object.values(runtime)))
  const chain = { focus() { return this }, setTextSelection(pos) { focused.push(pos); return this }, scrollIntoView() { return this }, run() {} }
  view.editorPanel.value = { editor: { state: { doc: { content: { size: 30 }, descendants(fn) { fn({ attrs: { id: 'p1' } }, 3) } } }, chain: () => chain } }
  // Mounting the new chapter's editor fires this while select() still waits
  // for IndexedDB's local-draft scan, as in the browser.
  scope.run(() => watch(() => h.writer.current?.uid, () => view.editorReady()))
  h.props.navigateChapter = view.jumpToAnchor
  return { stop: () => scope.stop(), focused }
}

async function realWritingHarness({ recovery = false } = {}) {
  globalThis.localStorage = memoryStorage(); globalThis.sessionStorage = memoryStorage()
  setActivePinia(createPinia()); setCurrentNovelId(1)
  const bookUid = crypto.randomUUID()
  const chapters = [0, 1].map(index => ({ uid: crypto.randomUUID(), title: `第${index + 1}章`, doc: plainDocument(`已保存正文${index}`), links: [], notes: '', summary: '', volumeId: null, goal: 2000, numbered: true, deleted: false, status: 'draft', position: index, revision: 0, wordCount: 5, updatedAt: '' }))
  const workspace = { uid: bookUid, structureVersion: 0, volumes: [], chapters, preferences: {}, sessions: [], changeSequence: 0, syncedSequence: 0 }
  let release, started, gated = false
  const gate = new Promise(resolve => { release = resolve }), begin = new Promise(resolve => { started = resolve })
  request.defaults.adapter = async config => {
    assert.equal(config.method, 'get', 'Source navigation must not write an unchanged manuscript')
    let data = []
    if (config.url.endsWith('/writing')) data = workspace
    else if (config.url.includes('/chapters/')) {
      data = chapters.find(chapter => config.url.endsWith(`/${chapter.uid}`))
      if (gated && data?.uid === chapters[0].uid) { started(); await gate }
    }
    return { data: clone(data), status: 200, statusText: 'OK', headers: {}, config }
  }
  if (recovery) await saveDraft({ key: `${bookUid}:pending`, bookUid, novelId: 1, chapter: { ...clone(chapters[0]), doc: plainDocument('必须保留的待恢复正文') }, updatedAt: Date.now(), tabId: 'other-tab' })
  const writer = useWritingStore()
  await writer.load(1); assert.equal(await writer.select(chapters[1].uid), true)
  const h = harness(undefined, { writer, initialChapter: chapters[1].uid }); await h.mount()
  const view = attachWritingView(h)
  const selections = []
  writer.$onAction(({ name, args, after }) => { if (name === 'select') after(result => selections.push({ uid: args[0], result })) })
  gated = true
  return { ...h, chapters, bookUid, view, selections, selectionStarted: begin, release, cleanup() { view.stop(); h.unmount(); writer.$reset() } }
}

test('real writing store and view route watchers complete one cross-chapter selection, retain the block and close the ledger', async () => {
  const h = await realWritingHarness()
  try {
    h.begin('state'); h.draft.value.value = '尚未保存的知情账草稿'
    const target = { path: '/novel/1/writing', query: { chapter: h.chapters[0].uid, block: 'p1' } }
    const navigating = h.navigateSource(target)
    await h.selectionStarted
    assert.equal(h.emitted.length, 0); assert.equal(h.writer.current.uid, h.chapters[1].uid)
    assert.equal(h.router.currentRoute.value.query.chapter, h.chapters[1].uid)
    h.release(); await navigating
    assert.equal(h.writer.current.uid, h.chapters[0].uid); assert.equal(h.writer.loading, false)
    assert.equal(h.router.currentRoute.value.fullPath, h.router.resolve(target).fullPath)
    assert.deepEqual(h.selections, [{ uid: h.chapters[0].uid, result: true }])
    assert.deepEqual(h.view.focused, [4]); assert.deepEqual(h.emitted, [['close']])
    assert.match(h.values.get('ink:story-state-draft:1'), /尚未保存的知情账草稿/)
  } finally { h.cleanup() }
})

test('real source selection finishes the local recovery scan before deciding to keep the ledger open', async () => {
  const h = await realWritingHarness({ recovery: true })
  try {
    const navigating = h.navigateSource({ path: '/novel/1/writing', query: { chapter: h.chapters[0].uid, block: 'p1' } })
    await h.selectionStarted; h.release(); await navigating
    assert.deepEqual(h.selections, [{ uid: h.chapters[0].uid, result: true }])
    assert.equal(h.writer.recovery.chapter.uid, h.chapters[0].uid)
    assert.match(documentText(h.writer.recovery.chapter.doc), /必须保留的待恢复正文/)
    assert.match(documentText((await listDrafts(h.bookUid, 1))[0].chapter.doc), /必须保留的待恢复正文/)
    assert.equal(h.emitted.length, 0); assert.match(h.error.value, /尚未安全打开/)
  } finally { h.cleanup() }
})

test('record links route plain clicks through the panel and retain modifier new-tab behavior', () => {
  const card = readFileSync(new URL('../src/components/story/StoryRecordCard.vue', import.meta.url), 'utf8')
  const script = card.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const js = ts.transpileModule(script, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText.replace(/^import .* from .*\n/gm, '')
  const props = { novelId: 1, busy: false, record: { source: { chapterUid: 'chapter-a', blockId: 'p1', eventId: 9 } } }, emitted = []
  const run = new Function('computed', 'defineProps', 'defineEmits', `${js}\nreturn { followSource, chapterTarget, eventTarget };`)(computed, () => props, () => (...args) => emitted.push(args))
  let prevented = 0
  const plain = () => ({ button: 0, defaultPrevented: false, preventDefault() { prevented++ } })
  run.followSource(plain(), run.chapterTarget.value)
  assert.deepEqual(emitted, [['navigate', { path: '/novel/1/writing', query: { chapter: 'chapter-a', block: 'p1' } }]])
  run.followSource({ ...plain(), ctrlKey: true }, run.eventTarget.value)
  assert.equal(prevented, 1); assert.equal(emitted.length, 1)
  props.busy = true; run.followSource(plain(), run.chapterTarget.value)
  assert.equal(prevented, 2); assert.equal(emitted.length, 1)
})
