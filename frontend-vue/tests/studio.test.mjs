import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { ref, watch, reactive, computed } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources()
const { filterNovels, recentRecords, notebookKey, remainingSeconds } = await load('utils/studio')
const { mapPoint } = await load('utils/map')
const { useFocusStore } = await load('stores/focus')
const { request: focusRequest } = await load('api/request')

test('map clicks retain the same stored position on mobile, desktop and zoomed canvases', () => {
  for (const scale of [0.4, 1, 1.5]) {
    const rect = { left: 17, top: 210, width: 800 * scale, height: 600 * scale }
    assert.deepEqual(mapPoint(17 + 400 * scale, 210 + 300 * scale, rect, 800, 600), { x: 400, y: 300 })
  }
})

test('bookshelf combines text and status without mutating the source list', () => {
  const books = [
    { title: '乙', author: 'Ink', status: 'writing', updatedAt: '2026-09-01' },
    { title: '甲', description: 'INK world', status: 'planning', updatedAt: '2026-09-02' },
    { title: '丙', description: 'other', status: 'writing' },
  ]
  assert.deepEqual(filterNovels(books, ' ink ', 'writing', 'updated'), [books[0]])
  assert.deepEqual(filterNovels(books, 'INK', 'all', 'updated'), [books[1], books[0]])
  assert.deepEqual(filterNovels(books, '', 'all', 'title').map(b => b.title), ['丙', '甲', '乙'])
  assert.equal(books[0].title, '乙')
})

test('recent records keep the target module and ID, use creation dates, and limit to five', () => {
  const groups = [
    { path: 'characters', label: '人物', items: [{ id: 1, name: '旧', updatedAt: 'bad' }, { id: 2, name: '新', createdAt: '2026-09-25' }] },
    { path: 'outlines', label: '大纲', items: Array.from({ length: 5 }, (_, i) => ({ id: i + 1, title: '章节', updatedAt: `2026-09-0${i + 1}` })) },
  ]
  const recent = recentRecords(groups)
  assert.equal(recent.length, 5)
  assert.equal(recent[0].path, 'characters')
  assert.equal(recent[0].id, 2)
  assert.equal(recent[1].id, 5)
  assert.equal(groups[1].items[0].id, 1)
  assert.ok(recent.every(item => item.name !== '旧'))
})

function component(name, runtime, returned) {
  const source = readFileSync(new URL(`../src/components/studio/${name}.vue`, import.meta.url), 'utf8')
  const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
  const code = ts.transpileModule(script, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText
  return new Function(...Object.keys(runtime), code + `; return {${returned}}`)(...Object.values(runtime))
}

function timerHarness(storage = new Map(), novelId = 1) {
  setActivePinia(createPinia())
  let now = 100000
  const atTime = fn => { const original = Date.now; Date.now = () => now; try { return fn() } finally { Date.now = original } }
  globalThis.localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) }
  focusRequest.defaults.adapter = async config => ({ data: { schemaVersion: 1, version: 1, days: [] }, status: 200, statusText: 'OK', headers: {}, config })
  const focus = useFocusStore()
  const timerState = atTime(() => component('FocusTimer', {
    computed, watch, defineProps: () => ({ novelId }), useFocusStore: () => focus,
  }, 'duration,seconds,running,choose'))
  return { ...timerState, storage, focus, toggle: () => atTime(() => focus.toggle()), choose: value => atTime(() => timerState.choose(value)), elapse(ms) { now += ms; atTime(() => focus.tick(now)) }, dispose: () => focus.$reset() }
}

test('focus timer controls mirror its persistent store through pause, resume and completion', () => {
  const h = timerHarness()
  h.choose(15); h.toggle(); h.elapse(61500)
  assert.equal(h.seconds.value, 839)
  h.toggle(); h.elapse(30000)
  assert.equal(h.seconds.value, 839)
  const restored = timerHarness(h.storage)
  assert.equal(restored.seconds.value, 839)
  restored.toggle(); restored.elapse(900000)
  assert.equal(restored.running.value, false)
  assert.equal(restored.seconds.value, 0)
  restored.toggle()
  assert.equal(restored.seconds.value, 900)
  h.dispose(); restored.dispose()
})

test('focus timer restores a live deadline, isolates works and preserves invalid original state', () => {
  const storage = new Map([['ink-studio-timer-1', JSON.stringify({ duration: 900, seconds: 900, deadline: 400000 })]])
  const h = timerHarness(storage)
  assert.equal(h.running.value, true)
  assert.equal(h.seconds.value, 300)
  h.dispose()
  const foreign = timerHarness(storage, 2)
  assert.equal(foreign.seconds.value, 1500)
  foreign.dispose()
  storage.set('ink-studio-timer-1', '{broken')
  const invalid = timerHarness(storage)
  assert.equal(invalid.focus.blocked, true)
  assert.equal(storage.get('ink-studio-timer-1'), '{broken')
  invalid.dispose()
})

test('inbox exports saved ideas, drafts and untouched legacy originals; drag carries only a scoped reference', async () => {
  const { markdownIdeas, ideaCategories, ideaCategoryLabel, IDEA_DRAG_MIME } = await load('utils/ideas')
  const legacy = '甲作品旧便笺\r\n\n😀  '
  let blob, filename, leave
  const inbox = reactive({
    ideas: [{ uid: 'idea-a', title: '已保存', body: '内容', category: 'scene', chapterUids: [], createdAt: '', updatedAt: '' }],
    chapters: [], draft: { idea: { uid: 'draft', title: '草稿', body: '未提交', category: 'other', chapterUids: [] } },
    legacyText: legacy, loading: false, saving: false, unsafe: false,
    load: async () => {}, persist() {}, markExported() {}, rawRecovery: '{invalid draft',
  })
  const h = component('IdeaNotebook', {
    ref, watch, reactive, computed, defineProps: () => ({ novelId: 1, compact: true }), withDefaults: value => value,
    defineEmits: () => () => {}, useIdeasStore: () => inbox, markdownIdeas, ideaCategories, ideaCategoryLabel, IDEA_DRAG_MIME,
    onBeforeRouteLeave: fn => leave = fn, onMounted: fn => fn(), onBeforeUnmount() {},
    window: { addEventListener() {} }, Blob,
    URL: { createObjectURL: value => { blob = value; return 'blob:test' }, revokeObjectURL() {} },
    document: { createElement: () => ({ click() { filename = this.download } }) }, setTimeout: fn => fn(),
  }, 'download,downloadLegacy,downloadRecovery,dragIdea,migrationOpen,closeEditor,beforeUnload')
  h.downloadLegacy()
  assert.equal(await blob.text(), legacy)
  assert.equal(filename, '灵感便笺-1-原件.md')
  h.migrationOpen.value = true; h.migrationOpen.value = false
  assert.equal(inbox.legacyText, legacy)
  h.download()
  assert.match(await blob.text(), /已保存素材/)
  assert.match(await blob.text(), /本机未提交草稿/)
  assert.ok((await blob.text()).endsWith(legacy))
  const payloads = new Map()
  const transfer = { setData(type, value) { payloads.set(type, value) } }
  h.dragIdea({ dataTransfer: transfer }, inbox.ideas[0])
  assert.deepEqual(JSON.parse(payloads.get(IDEA_DRAG_MIME)), { novelId: 1, uid: 'idea-a' })
  assert.equal(transfer.effectAllowed, 'copy')
  inbox.unsafe = true
  assert.equal(leave(), false)
  let prevented = false
  h.beforeUnload({ preventDefault() { prevented = true } })
  assert.equal(prevented, true)
  h.downloadRecovery(); assert.equal(await blob.text(), '{invalid draft')
})

test('cover uploads on the global bookshelf use the edited novel, without an active context', async () => {
  globalThis.localStorage = { getItem: () => null, removeItem() {}, setItem() {} }
  const api = await load('api/request')
  const { imagesApi } = await load('api/images')
  api.setCurrentNovelId(null)
  let sent
  api.request.defaults.adapter = async config => {
    sent = config
    return { data: { id: 41 }, status: 200, statusText: 'OK', headers: {}, config }
  }
  const file = new File(['cover'], 'cover.png', { type: 'image/png' })
  const result = await imagesApi.upload(file, 'novel_cover', 7)
  assert.equal(sent.url, '/novels/7/images')
  assert.equal(sent.data.get('imageType'), 'novel_cover')
  assert.equal(await sent.data.get('file').text(), 'cover')
  assert.equal(imagesApi.getFileUrl(result.id, 7), '/api/novels/7/images/41/file')
  assert.throws(() => imagesApi.getFileUrl(41), /No novel selected/)
})
