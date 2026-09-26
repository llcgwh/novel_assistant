import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { ref, watch } from 'vue'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources()
const { filterNovels, recentRecords, notebookKey, remainingSeconds } = await load('utils/studio')
const { mapPoint } = await load('utils/map')

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
  let now = 100000, timer, disposed = false, unmount
  const timerState = component('FocusTimer', {
    ref, defineProps: () => ({ novelId }), remainingSeconds,
    localStorage: { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value) },
    Date: { now: () => now },
    onMounted: fn => fn(), onBeforeUnmount: fn => unmount = fn,
    setInterval: fn => { timer = fn; return 1 }, clearInterval: () => disposed = true,
  }, 'duration,seconds,running,toggle,reset,choose')
  return { ...timerState, storage, elapse(ms) { now += ms; timer() }, unmount: () => unmount(), disposed: () => disposed }
}

test('focus timer follows elapsed wall time, pauses, resumes, finishes and cleans up', () => {
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
  restored.unmount()
  assert.equal(restored.disposed(), true)
})

test('focus timer restores a live deadline and ignores invalid or foreign novel state', () => {
  const storage = new Map([['ink-studio-timer-1', JSON.stringify({ duration: 900, seconds: 900, deadline: 400000 })]])
  const h = timerHarness(storage)
  assert.equal(h.running.value, true)
  assert.equal(h.seconds.value, 300)
  assert.equal(timerHarness(storage, 2).seconds.value, 1500)
  storage.set('ink-studio-timer-1', '{broken')
  assert.equal(timerHarness(storage).seconds.value, 1500)
  storage.set('ink-studio-timer-1', JSON.stringify({ duration: 0, seconds: -1, deadline: 0 }))
  assert.equal(timerHarness(storage).duration.value, 1500)
})

test('notebooks autosave per novel and export the exact note even when storage is unavailable', async () => {
  const storage = new Map([[notebookKey(1), '甲作品便笺']])
  let fail = false, blob, filename
  const make = novelId => component('IdeaNotebook', {
    ref, watch, notebookKey, defineProps: () => ({ novelId }), Blob,
    localStorage: { getItem: key => storage.get(key), setItem(key, value) { if (fail) throw new Error('quota'); storage.set(key, value) } },
    URL: { createObjectURL: value => { blob = value; return 'blob:test' }, revokeObjectURL() {} },
    document: { createElement: () => ({ click() { filename = this.download } }) },
    setTimeout: fn => fn(),
  }, 'note,failed,download')
  const a = make(1), b = make(2)
  assert.equal(a.note.value, '甲作品便笺')
  assert.equal(b.note.value, '')
  b.note.value = '另一段灵感\n下一章'
  assert.equal(storage.get(notebookKey(2)), b.note.value)
  assert.equal(storage.get(notebookKey(1)), '甲作品便笺')
  fail = true
  b.note.value = '存储已满，但这段灵感仍可导出。'
  assert.equal(b.failed.value, true)
  b.download()
  assert.equal(filename, '灵感便笺-2.md')
  assert.equal(await blob.text(), b.note.value)
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
