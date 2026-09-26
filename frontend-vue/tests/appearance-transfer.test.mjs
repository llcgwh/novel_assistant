import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { compileSources } from './helpers/load-source.mjs'
const { parseAppearanceBackup } = await compileSources()('utils/appearance')
const source = readFileSync(new URL('../src/components/settings/AppearanceTransfer.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
const code = ts.transpileModule(script, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText
function harness() {
  const storage = new Map([['mapBackground_2','7']])
  const state = { settings: { backgroundImage: '/api/novels/2/images/7/file', backgroundOpacity: .4 } }
  const downloads = [], uploads = []
  let exported
  const runtime = {
    ref: value => ({ value }), useRoute: () => ({ params: { novelId: '2' } }), useAppStore: () => state,
    request: { get: async () => new Blob(['image-bytes'], { type: 'image/png' }) },
    imagesApi: { upload: async (file, type) => { uploads.push([file, type]); return { id: 99 } } },
    getApiBaseUrl: () => '/api', parseAppearanceBackup,
    localStorage: { getItem: key => storage.get(key), setItem: (key,value) => storage.set(key,value), removeItem: key => storage.delete(key) },
    document: { createElement: () => ({ click() { downloads.push(this.download) } }) },
    URL: class extends URL { static createObjectURL(blob) { exported = blob; return 'blob:test' } static revokeObjectURL() {} },
    location: { origin: 'http://localhost:3000' }, confirm: () => true, File,
    FileReader: class { readAsDataURL(blob) { blob.arrayBuffer().then(buffer => { this.result = `data:${blob.type};base64,${Buffer.from(buffer).toString('base64')}`; this.onload() }) } },
    setTimeout: fn => fn()
  }
  const component = new Function(...Object.keys(runtime), code + ';return {exportAppearance,importAppearance,message,busy}')(...Object.values(runtime))
  return { ...component, storage, state, downloads, uploads, exported: () => exported }
}
test('appearance export embeds local backgrounds and produces a portable round-trip file', async () => {
  const h = harness(); await h.exportAppearance()
  assert.deepEqual(h.downloads, ['novel-appearance.json'])
  const parsed = parseAppearanceBackup(JSON.parse(await h.exported().text()))
  assert.match(parsed.settings.backgroundImage, /^data:image\/png;base64,/)
  assert.equal(parsed.mapBackground, parsed.settings.backgroundImage)
  assert.equal(h.busy.value, false)
})
test('appearance import uploads the map for the target novel and restores settings', async () => {
  const h = harness(); await h.exportAppearance()
  await h.importAppearance({ target: { files: [h.exported()], value: 'input' } })
  assert.equal(h.uploads[0][1], 'map_background')
  assert.equal(await h.uploads[0][0].text(), 'image-bytes')
  assert.equal(h.storage.get('mapBackground_2'), '99')
  assert.deepEqual(JSON.parse(h.storage.get('app-settings')), h.state.settings)
})
test('invalid appearance import leaves existing settings and map unchanged', async () => {
  const h = harness(); const original = structuredClone(h.state)
  await h.importAppearance({ target: { files: [new Blob(['{"format":"wrong"}'])], value: '' } })
  assert.deepEqual(h.state, original); assert.equal(h.storage.get('mapBackground_2'), '7')
  assert.equal(h.uploads.length, 0); assert.equal(h.busy.value, false); assert.ok(h.message.value)
})
