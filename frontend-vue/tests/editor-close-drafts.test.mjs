import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { computed, effectScope, nextTick, reactive, ref, watch } from 'vue'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources()
const nextPen = await load('utils/nextPen'), ideas = await load('utils/ideas')
const revisions = await load('utils/revisionDesk'), rounds = await load('utils/revisionRounds')
const { blockList, documentText } = await load('utils/writing')
const clone = value => JSON.parse(JSON.stringify(value))
function setup(path, runtime, exposed) {
  const source = readFileSync(new URL(path, import.meta.url), 'utf8')
  const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const js = ts.transpileModule(script, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText.replace(/^import .* from .*\n/gm, '')
  const scope = effectScope()
  const result = scope.run(() => new Function(...Object.keys(runtime), `${js}\nreturn { ${exposed} };`)(...Object.values(runtime)))
  return { ...result, stop: () => scope.stop() }
}
function nextPenHarness(saved = null) {
  const values = new Map()
  const storage = { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
  const writer = reactive({ novelId: 1, workspace: { uid: 'book', chapters: [{ uid: 'chapter', title: '第一章' }] }, current: { uid: 'chapter', doc: { type: 'doc', content: [] } }, activeBlock: '' })
  const desk = reactive({ data: { nextPen: saved }, saving: false, loading: false, async saveNextPen(value) { this.data.nextPen = value } })
  const h = setup('../src/components/writing/NextPen.vue', { ...nextPen, computed, nextTick, reactive, ref, watch, blockList, localStorage: storage, defineEmits: () => () => {}, useWritingStore: () => writer, useWritingDeskStore: () => desk }, 'form, formDirty, editing, openEditor, closeEditor, adoptSaved, saveNote, localDraft, discardDraft, discardOpen, confirmDiscardDraft')
  return { ...h, values, desk }
}

test('next-pen empty anchors and saved notes are clean; changed and restored local notes warn', async () => {
  const h = nextPenHarness()
  try {
    h.openEditor(); assert.equal(h.formDirty.value, false)
    h.form.nextScene = '渡河'; assert.equal(h.formDirty.value, true)
    h.closeEditor(); assert.equal(h.editing.value, false); assert.match([...h.values.values()][0], /渡河/)
    h.openEditor(); assert.equal(h.formDirty.value, true); assert.equal(h.form.nextScene, '渡河')
    await h.saveNote(); assert.equal(h.editing.value, false); assert.equal(h.values.size, 0)
    h.openEditor(); assert.equal(h.formDirty.value, false)
    h.form.nextScene = ''; assert.equal(h.formDirty.value, true, 'Clearing a saved note is still an edit')
    h.adoptSaved(); assert.equal(h.formDirty.value, false); assert.equal(h.form.nextScene, '渡河')
    await nextTick()
  } finally { h.stop() }
})

test('idea editor warns for text and association changes, but not untouched or saved content', () => {
  const blank = { uid: 'idea', title: '', body: '', category: 'other', chapterUids: [], createdAt: '2026-10-18', updatedAt: '' }
  const inbox = reactive({ draft: { idea: clone(blank), baseline: '' }, latestIdea: null, loading: false, saving: false, async load() {} })
  const h = setup('../src/components/studio/IdeaNotebook.vue', { ...ideas, computed, ref, watch, onBeforeRouteLeave() {}, onBeforeUnmount() {}, onMounted() {}, defineProps: () => ({ novelId: 1 }), withDefaults: value => value, defineEmits: () => () => {}, useIdeasStore: () => inbox }, 'draftDirty, category')
  try {
    assert.equal(h.draftDirty.value, false)
    h.category.value = 'plot'; assert.equal(h.draftDirty.value, false)
    inbox.draft.idea.chapterUids.push('chapter'); assert.equal(h.draftDirty.value, true)
    inbox.draft.idea.chapterUids = []; assert.equal(h.draftDirty.value, false)
    inbox.draft.idea.body = '正文'; inbox.latestIdea = clone(inbox.draft.idea)
    assert.equal(h.draftDirty.value, false)
    inbox.draft.idea.title = '新标题'; assert.equal(h.draftDirty.value, true)
    inbox.draft.idea = clone(inbox.latestIdea); assert.equal(h.draftDirty.value, false)
    inbox.draft = null; assert.equal(h.draftDirty.value, false)
  } finally { h.stop() }
})

test('discarding a local next-pen draft requires a separate explicit choice', async () => {
  const h = nextPenHarness()
  try {
    h.openEditor(); h.form.nextScene = '不能悄悄丢掉'; await nextTick()
    h.discardDraft(); assert.equal(h.discardOpen.value, true); assert.equal(h.editing.value, true)
    assert.match([...h.values.values()][0], /不能悄悄丢掉/)
    h.discardOpen.value = false; assert.equal(h.form.nextScene, '不能悄悄丢掉')
    h.discardDraft(); await h.confirmDiscardDraft()
    assert.equal(h.editing.value, false); assert.equal(h.values.size, 0)
  } finally { h.stop() }
})

test('revision closing confirms one time for edits and no time for filters, reverted drafts or saved edits', async () => {
  const emitted = []
  const writer = reactive({ workspace: { chapters: [] }, current: null })
  const task = { uid: 'task', chapterUid: 'chapter', blockId: '', excerpt: '', category: 'other', priority: 'normal', body: '旧修订', status: 'open', createdAt: '', updatedAt: '', roundUid: '' }
  const desk = reactive({ data: { tasks: [task], rounds: [] }, async saveTask(value) { this.data.tasks[0] = value } })
  const h = setup('../src/components/writing/RevisionDesk.vue', { ...revisions, ...rounds, documentText, computed, nextTick, ref, defineEmits: () => (...args) => emitted.push(args), useWritingStore: () => writer, useWritingDeskStore: () => desk }, 'beginEdit, draft, categoryFilter, requestClose, discardAction, confirmDiscard, saveDraft')
  try {
    h.categoryFilter.value = 'plot'; h.requestClose(); assert.deepEqual(emitted.splice(0), [['close']])
    h.beginEdit(task); h.requestClose(); assert.equal(h.discardAction.value, null); assert.deepEqual(emitted.splice(0), [['close']])
    h.draft.value.body = '尚未保存'; h.requestClose(); assert.equal(typeof h.discardAction.value, 'function'); assert.deepEqual(emitted, [])
    h.discardAction.value = null; assert.equal(h.draft.value.body, '尚未保存')
    h.requestClose(); await h.confirmDiscard(); assert.deepEqual(emitted.splice(0), [['close']]); assert.equal(h.discardAction.value, null)
    await h.saveDraft(); h.requestClose(); assert.deepEqual(emitted, [['close']])
  } finally { h.stop() }
})
