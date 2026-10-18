import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { computed, effectScope, nextTick, reactive, ref, watch } from 'vue'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources()
const imagePolicy = await load('utils/imageUpload')

const source = readFileSync(new URL('../src/components/common/ImageUpload.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
const parsed = ts.createSourceFile('ImageUpload.ts', script, ts.ScriptTarget.ES2022, true, ts.ScriptKind.TS)
const body = ts.createPrinter().printFile(ts.factory.updateSourceFile(parsed, parsed.statements.filter(statement => !ts.isImportDeclaration(statement))))
const compiled = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText

function harness(t, options = {}) {
  let resolve, reject, reader, pickerClicks = 0
  let pending
  function resetAttempt() { pending = new Promise((yes, no) => { resolve = yes; reject = no }) }
  resetAttempt()
  const props = reactive({ modelValue: '/original-image', imageType: options.imageType ?? 'character_portrait', novelId: 6 })
  const events = [], calls = [], alerts = [], revoked = []
  const runtime = {
    ...imagePolicy, computed, ref, watch, defineProps: () => props,
    defineEmits: () => (name, value) => { events.push([name, value]); if (name === 'update:modelValue') props.modelValue = value },
    imagesApi: {
      upload(...args) { calls.push(args); return pending },
      getFileUrl(id, novelId) { return `/novels/${novelId}/images/${id}` },
    },
    URL: { createObjectURL: () => 'blob:preview', revokeObjectURL: value => revoked.push(value) },
    FileReader: class {
      constructor() { reader = this }
      readAsDataURL(file) { this.file = file }
    },
    alert: message => alerts.push(message), console: { error() {} },
  }
  const scope = effectScope()
  const component = scope.run(() => new Function(...Object.keys(runtime), compiled + '; return { handleFileChange, removeImage, triggerUpload, previewUrl, uploading, fileInput, errorMessage }')(...Object.values(runtime)))
  component.fileInput.value = { click() { pickerClicks++ } }
  t.after(() => scope.stop())
  const input = { files: [{ name: 'fixture.png', type: 'image/png', size: 128 }], value: 'fixture.png' }
  return { ...component, props, events, calls, alerts, revoked, input,
    resolve(value) { resolve(value) }, reject(error) { reject(error) }, resetAttempt,
    upload() { return component.handleFileChange({ target: input }) },
    get reader() { return reader }, get pickerClicks() { return pickerClicks },
  }
}

test('the upload stays busy until its URL is adopted, while duplicate selection and removal are blocked', async t => {
  const h = harness(t)
  const finished = h.upload()
  assert.equal(h.uploading.value, true)
  assert.deepEqual(h.events, [['uploading', true]])
  assert.equal(h.previewUrl.value, 'blob:preview')
  assert.equal(h.props.modelValue, '/original-image')
  h.triggerUpload(); h.removeImage(); await h.upload()
  assert.equal(h.pickerClicks, 0)
  assert.equal(h.calls.length, 1)
  h.resolve({ id: 91 })
  await finished; await nextTick()
  assert.deepEqual(h.events, [['uploading', true], ['update:modelValue', '/novels/6/images/91'], ['uploading', false]])
  assert.equal(h.props.modelValue, '/novels/6/images/91')
  assert.equal(h.uploading.value, false)
  assert.equal(h.input.value, '')
  assert.deepEqual(h.revoked, ['blob:preview'])
})

test('a failed upload restores the original image and releases busy without dirtying the form', async t => {
  const h = harness(t)
  const finished = h.upload()
  h.reject(Error('fixture upload failure'))
  await finished; await nextTick()
  assert.deepEqual(h.events, [['uploading', true], ['uploading', false]])
  assert.equal(h.props.modelValue, '/original-image')
  assert.equal(h.previewUrl.value, '/original-image')
  assert.equal(h.uploading.value, false)
  assert.equal(h.input.value, '')
  assert.equal(h.alerts.length, 0)
  assert.equal(h.errorMessage.value, 'fixture upload failure')
  assert.deepEqual(h.revoked, ['blob:preview'])
})

test('background images retain local FileReader processing and announce the same busy lifecycle', async t => {
  const h = harness(t, { imageType: 'background' })
  const finished = h.upload()
  assert.equal(h.calls.length, 0)
  assert.equal(h.uploading.value, true)
  assert.deepEqual(h.events, [['uploading', true]])
  h.reader.result = 'data:image/png;base64,fixture'
  h.reader.onload()
  await finished
  assert.deepEqual(h.events, [['uploading', true], ['update:modelValue', 'data:image/png;base64,fixture'], ['uploading', false]])
  assert.equal(h.calls.length, 0)
  assert.equal(h.uploading.value, false)
})

test('invalid background files leave the original image clean and never upload remotely', async t => {
  const h = harness(t, { imageType: 'background' })
  h.input.files[0].size = 2 * 1024 * 1024 + 1
  await h.upload()
  assert.equal(h.calls.length, 0)
  assert.equal(h.reader, undefined)
  assert.equal(h.props.modelValue, '/original-image')
  assert.equal(h.previewUrl.value, '/original-image')
  assert.deepEqual(h.events, [])
  assert.deepEqual(h.revoked, [])
  assert.match(h.errorMessage.value, /2 MiB/)
  assert.equal(h.input.value, '')
})

test('cancelling the file picker does not lock or change the form', async t => {
  const h = harness(t)
  h.input.files = []
  await h.upload()
  assert.equal(h.uploading.value, false)
  assert.deepEqual(h.events, [])
  assert.equal(h.calls.length, 0)
})

test('oversized portraits are stopped before preview or network and the same picker can retry', async t => {
  const h = harness(t)
  h.input.files[0].size = imagePolicy.MAX_IMAGE_BYTES + 1
  await h.upload()
  assert.match(h.errorMessage.value, /8 MiB/)
  assert.equal(h.previewUrl.value, '/original-image')
  assert.equal(h.calls.length, 0)
  assert.deepEqual(h.events, [])
  assert.equal(h.input.value, '')
  h.input.files[0].size = imagePolicy.MAX_IMAGE_BYTES
  const finished = h.upload()
  assert.equal(h.errorMessage.value, '')
  assert.equal(h.calls.length, 1)
  h.resolve({ id: 92 }); await finished
  assert.equal(h.previewUrl.value, '/novels/6/images/92')
})

test('server 413 stays inline and a successful retry clears it without losing the original form', async t => {
  const h = harness(t)
  const finished = h.upload()
  h.reject({ response: { status: 413 }, message: 'Request failed with status code 413' })
  await finished
  assert.match(h.errorMessage.value, /8 MiB/)
  assert.doesNotMatch(h.errorMessage.value, /status code/)
  assert.equal(h.props.modelValue, '/original-image')
  assert.equal(h.alerts.length, 0)
  h.resetAttempt()
  const retry = h.upload()
  h.resolve({ id: 93 }); await retry
  assert.equal(h.errorMessage.value, '')
  assert.equal(h.props.modelValue, '/novels/6/images/93')
})

test('unsupported files never send a request or mark a draft dirty', async t => {
  const h = harness(t)
  h.input.files[0] = { name: 'vector.svg', type: 'image/svg+xml', size: 100 }
  await h.upload()
  assert.match(h.errorMessage.value, /PNG/)
  assert.deepEqual(h.events, [])
  assert.equal(h.calls.length, 0)
  assert.equal(h.props.modelValue, '/original-image')
})

test('a local background read failure is understandable and restores the image', async t => {
  const h = harness(t, { imageType: 'background' })
  const finished = h.upload()
  h.reader.onerror()
  await finished
  assert.match(h.errorMessage.value, /无法读取图片/)
  assert.equal(h.previewUrl.value, '/original-image')
  assert.equal(h.uploading.value, false)
})
