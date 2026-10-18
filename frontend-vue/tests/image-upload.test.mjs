import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { effectScope, nextTick, reactive, ref, watch } from 'vue'

const source = readFileSync(new URL('../src/components/common/ImageUpload.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
const parsed = ts.createSourceFile('ImageUpload.ts', script, ts.ScriptTarget.ES2022, true, ts.ScriptKind.TS)
const body = ts.createPrinter().printFile(ts.factory.updateSourceFile(parsed, parsed.statements.filter(statement => !ts.isImportDeclaration(statement))))
const compiled = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText

function harness(t, options = {}) {
  let resolve, reject, reader, pickerClicks = 0
  const pending = new Promise((yes, no) => { resolve = yes; reject = no })
  const props = reactive({ modelValue: '/original-image', imageType: options.imageType ?? 'character_portrait', novelId: 6 })
  const events = [], calls = [], alerts = [], revoked = []
  const runtime = {
    ref, watch, defineProps: () => props,
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
  const component = scope.run(() => new Function(...Object.keys(runtime), compiled + '; return { handleFileChange, removeImage, triggerUpload, previewUrl, uploading, fileInput }')(...Object.values(runtime)))
  component.fileInput.value = { click() { pickerClicks++ } }
  t.after(() => scope.stop())
  const input = { files: [{ name: 'fixture.png', type: 'image/png', size: 128 }], value: 'fixture.png' }
  return { ...component, props, events, calls, alerts, revoked, input, resolve, reject,
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
  assert.equal(h.alerts.length, 1)
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
  assert.deepEqual(h.events, [['uploading', true], ['uploading', false]])
  assert.deepEqual(h.revoked, ['blob:preview'])
})

test('cancelling the file picker does not lock or change the form', async t => {
  const h = harness(t)
  h.input.files = []
  await h.upload()
  assert.equal(h.uploading.value, false)
  assert.deepEqual(h.events, [])
  assert.equal(h.calls.length, 0)
})
