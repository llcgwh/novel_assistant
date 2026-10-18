import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { effectScope, nextTick, reactive, ref, watch } from 'vue'
import { compileSources } from './helpers/load-source.mjs'
const load = compileSources()
const { parseAppearanceBackup } = await load('utils/appearance'), uploadUtils = await load('utils/imageUpload')
const source = readFileSync(new URL('../src/components/settings/AppearanceTransfer.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
const code = ts.transpileModule(script, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText
function harness(t) {
  const storage = new Map([['mapBackground_2','7']])
  const state = { settings: { backgroundImage: '/api/novels/2/images/7/file', backgroundOpacity: .4 } }
  const downloads = [], uploads = []
  const route = reactive({ params: { novelId: '2' } }), scope = effectScope()
  let exported, context = 0, unmount
  const runtime = {
    ...uploadUtils, ref, watch, useRoute: () => route, useAppStore: () => state,
    getNovelContextVersion: () => context, onBeforeUnmount: fn => { unmount = fn },
    request: { get: async () => new Blob(['image-bytes'], { type: 'image/png' }) },
    imagesApi: { upload: async (file, type, novelId) => { uploads.push([file, type, novelId]); return { id: 99 } } },
    getApiBaseUrl: () => '/api', parseAppearanceBackup,
    localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key,value) => storage.set(key,value), removeItem: key => storage.delete(key) },
    document: { createElement: () => ({ click() { downloads.push(this.download) } }) },
    URL: class extends URL { static createObjectURL(blob) { exported = blob; return 'blob:test' } static revokeObjectURL() {} },
    location: { origin: 'http://localhost:3000' }, confirm: () => true, File,
    FileReader: class { readAsDataURL(blob) { blob.arrayBuffer().then(buffer => { this.result = `data:${blob.type};base64,${Buffer.from(buffer).toString('base64')}`; this.onload() }) } },
    setTimeout: fn => fn()
  }
  const component = scope.run(() => new Function(...Object.keys(runtime), code + ';return {exportAppearance,importAppearance,message,error,busy,importBusy}')(...Object.values(runtime)))
  t.after(() => { unmount(); scope.stop() })
  return { ...component, storage, state, downloads, uploads, runtime, route, async switchNovel(id) { context++; route.params.novelId = id; await nextTick() }, exported: () => exported }
}
test('appearance export embeds local backgrounds and produces a portable round-trip file', async t => {
  const h = harness(t); await h.exportAppearance()
  assert.deepEqual(h.downloads, ['novel-appearance.json'])
  const parsed = parseAppearanceBackup(JSON.parse(await h.exported().text()))
  assert.match(parsed.settings.backgroundImage, /^data:image\/png;base64,/)
  assert.equal(parsed.mapBackground, parsed.settings.backgroundImage)
  assert.equal(h.busy.value, false)
})
test('legacy BMP MIME types export with a portable canonical type that can be imported again', async t => {
  const h = harness(t)
  h.runtime.request.get = async () => new Blob(['bmp-bytes'], { type: 'image/x-ms-bmp' })
  await h.exportAppearance()
  const exported = h.exported(), parsed = parseAppearanceBackup(JSON.parse(await exported.text()))
  assert.match(parsed.settings.backgroundImage, /^data:image\/bmp;base64,/)
  assert.match(parsed.mapBackground, /^data:image\/bmp;base64,/)
  await h.importAppearance({ target: { files: [exported], value: '' } })
  assert.equal(h.uploads[0][0].type, 'image/bmp'); assert.equal(h.error.value, '')
})
test('appearance import uploads the map for the target novel and restores settings', async t => {
  const h = harness(t); await h.exportAppearance()
  await h.importAppearance({ target: { files: [h.exported()], value: 'input' } })
  assert.equal(h.uploads[0][1], 'map_background')
  assert.equal(h.uploads[0][2], 2)
  assert.equal(await h.uploads[0][0].text(), 'image-bytes')
  assert.equal(h.storage.get('mapBackground_2'), '99')
  assert.deepEqual(JSON.parse(h.storage.get('app-settings')), h.state.settings)
})
test('invalid appearance import leaves existing settings and map unchanged', async t => {
  const h = harness(t); const original = structuredClone(h.state)
  await h.importAppearance({ target: { files: [new Blob(['{"format":"wrong"}'])], value: '' } })
  assert.deepEqual(h.state, original); assert.equal(h.storage.get('mapBackground_2'), '7')
  assert.equal(h.uploads.length, 0); assert.equal(h.busy.value, false); assert.ok(h.error.value)
})

const dataImage = bytes => `data:image/png;base64,${Buffer.alloc(bytes).toString('base64')}`
const backup = (mapBackground, backgroundImage = '') => new Blob([JSON.stringify({ format: 'novel-appearance-v1', settings: { backgroundImage, backgroundOpacity: .6 }, mapBackground })])
const inputEvent = file => ({ target: { files: [file], value: 'chosen.json' } })
const until = async predicate => { while (!predicate()) await new Promise(resolve => setImmediate(resolve)) }

test('appearance imports allow map images above 2 MiB through 8 MiB while global images keep their 2 MiB limit', async t => {
  const h = harness(t)
  await h.importAppearance(inputEvent(backup(dataImage(uploadUtils.MAX_IMAGE_BYTES), dataImage(uploadUtils.MAX_BACKGROUND_BYTES))))
  assert.equal(h.uploads.length, 1); assert.equal(h.uploads[0][0].size, uploadUtils.MAX_IMAGE_BYTES)
  assert.equal(h.error.value, '')
  const settings = structuredClone(h.state.settings), stored = new Map(h.storage)
  await h.importAppearance(inputEvent(backup('none', dataImage(uploadUtils.MAX_BACKGROUND_BYTES + 1))))
  assert.match(h.error.value, /2 MiB/); assert.equal(h.uploads.length, 1)
  assert.deepEqual(h.state.settings, settings); assert.deepEqual(h.storage, stored)
  await h.importAppearance(inputEvent(backup(dataImage(uploadUtils.MAX_IMAGE_BYTES + 1))))
  assert.match(h.error.value, /8 MiB/); assert.equal(h.uploads.length, 1); assert.deepEqual(h.storage, stored)
})

test('appearance export distinguishes the 8 MiB map limit from the 2 MiB global background limit', async t => {
  const h = harness(t)
  h.state.settings.backgroundImage = ''
  h.runtime.request.get = async () => new Blob([new Uint8Array(3 * 1024 * 1024)], { type: 'image/png' })
  await h.exportAppearance(); assert.equal(h.downloads.length, 1); assert.equal(h.error.value, '')
  h.state.settings.backgroundImage = '/api/novels/2/images/7/file'
  await h.exportAppearance(); assert.equal(h.downloads.length, 1); assert.match(h.error.value, /2 MiB/)
  h.state.settings.backgroundImage = ''
  h.runtime.request.get = async () => new Blob([new Uint8Array(uploadUtils.MAX_IMAGE_BYTES + 1)], { type: 'image/png' })
  await h.exportAppearance(); assert.equal(h.downloads.length, 1); assert.match(h.error.value, /8 MiB/)
})

test('appearance upload failures preserve the active and persisted appearance and allow retrying the same file', async t => {
  const h = harness(t), file = backup(dataImage(20)), before = structuredClone(h.state), stored = new Map(h.storage)
  const upload = h.runtime.imagesApi.upload
  h.runtime.imagesApi.upload = async () => { throw { response: { status: 413 } } }
  const event = inputEvent(file); await h.importAppearance(event)
  assert.equal(event.target.value, ''); assert.match(h.error.value, /8 MiB/)
  assert.deepEqual(h.state, before); assert.deepEqual(h.storage, stored); assert.equal(h.importBusy.value, false)
  h.runtime.imagesApi.upload = upload; await h.importAppearance(event)
  assert.equal(h.uploads.length, 1); assert.equal(h.storage.get('mapBackground_2'), '99'); assert.equal(h.error.value, '')
})

test('switching novels during file reading cannot upload or apply an old appearance to the new novel', async t => {
  const h = harness(t), original = structuredClone(h.state), stored = new Map(h.storage)
  let release
  const file = { size: 1, text: () => new Promise(resolve => { release = resolve }) }
  const pending = h.importAppearance(inputEvent(file)); await until(() => release)
  await h.switchNovel('3'); await h.switchNovel('2')
  release(await backup(dataImage(20)).text()); await pending
  assert.equal(h.uploads.length, 0); assert.deepEqual(h.state, original); assert.deepEqual(h.storage, stored)
  assert.equal(h.busy.value, false); assert.equal(h.message.value, '')
})

test('appearance transfers serialize uploads and ignore old upload results after switching novels', async t => {
  const h = harness(t), original = structuredClone(h.state), stored = new Map(h.storage), file = backup(dataImage(20))
  let release, calls = 0
  h.runtime.imagesApi.upload = async (_file, _type, novelId) => { calls++; assert.equal(novelId, 2); return new Promise(resolve => { release = resolve }) }
  const pending = h.importAppearance(inputEvent(file)); await until(() => release)
  await h.importAppearance(inputEvent(file)); await h.exportAppearance(); assert.equal(calls, 1); assert.equal(h.downloads.length, 0)
  await h.switchNovel('3'); release({ id: 100 }); await pending
  assert.deepEqual(h.state, original); assert.deepEqual(h.storage, stored); assert.equal(h.message.value, '')
})

test('global appearance export still works outside a novel and late local-image reads cannot download a mixed-book file', async t => {
  const h = harness(t)
  await h.switchNovel(undefined); h.state.settings.backgroundImage = ''; await h.exportAppearance()
  assert.equal(h.downloads.length, 1); assert.equal(JSON.parse(await h.exported().text()).mapBackground, undefined)
  await h.switchNovel('2')
  let release
  h.runtime.request.get = () => new Promise(resolve => { release = resolve })
  const pending = h.exportAppearance(); await until(() => release)
  await h.switchNovel('3'); release(new Blob(['image'], { type: 'image/png' })); await pending
  assert.equal(h.downloads.length, 1); assert.equal(h.message.value, '')
})

test('appearance import rolls back the persisted preferences if applying the map choice fails', async t => {
  const h = harness(t), settings = structuredClone(h.state.settings)
  h.storage.set('app-settings', JSON.stringify(settings))
  const previous = new Map(h.storage), setItem = h.runtime.localStorage.setItem
  let failed = false
  h.runtime.localStorage.setItem = (key, value) => {
    if (key === 'mapBackground_2' && !failed) { failed = true; throw new Error('浏览器存储空间不足') }
    setItem(key, value)
  }
  await h.importAppearance(inputEvent(backup('none')))
  assert.match(h.error.value, /存储空间不足/); assert.deepEqual(h.state.settings, settings); assert.deepEqual(h.storage, previous)
})

test('appearance export reports download failures without exposing raw request codes or claiming an upload', async t => {
  const h = harness(t)
  h.runtime.request.get = async () => { throw { code: 'ECONNABORTED', message: 'timeout of 10000ms exceeded' } }
  await h.exportAppearance()
  assert.match(h.error.value, /导出失败.*图片读取超时/)
  assert.doesNotMatch(h.error.value, /ECONNABORTED|10000ms|图片上传/)
  assert.equal(h.downloads.length, 0); assert.equal(h.busy.value, false)
})

test('appearance export reports image decoding failures in Chinese and leaves original preferences unchanged', async t => {
  const h = harness(t), settings = structuredClone(h.state.settings), stored = new Map(h.storage)
  h.runtime.FileReader.prototype.readAsDataURL = function () { this.onerror({ type: 'error' }) }
  await h.exportAppearance()
  assert.match(h.error.value, /导出失败.*无法读取背景图片/)
  assert.equal(h.downloads.length, 0); assert.deepEqual(h.state.settings, settings); assert.deepEqual(h.storage, stored)
})
