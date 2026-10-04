import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import { renderToString } from '@vue/server-renderer'
import { compileSources } from './helpers/load-source.mjs'

const utils = await compileSources()('utils/series')
let instance = 0

function component(name, inlineTemplate = false, runtime = vue) {
  const source = readFileSync(new URL(`../src/components/series/${name}.vue`, import.meta.url), 'utf8')
  const { descriptor, errors } = parse(source)
  assert.deepEqual(errors, [])
  const compiled = compileScript(descriptor, { id: name, inlineTemplate })
  let code = ts.transpileModule(compiled.content, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText
  code = code.replace(/import\s*\{([^}]+)\}\s*from\s*['"]([^'"]+)['"];?/g, (_, names, module) => {
    assert.ok(module === 'vue' || module === '@/utils/series', `unexpected component dependency ${module}`)
    return `const {${names.replace(/\bas\b/g, ':')}} = ${module === 'vue' ? 'runtime' : 'utils'};`
  }).replace('export default', 'return')
  return new Function('runtime', 'utils', code)(runtime, utils)
}

function harness(name, values) {
  const props = vue.reactive({ disabled: false, idPrefix: '', ...values })
  const emitted = []
  const definition = component(name, false, { ...vue, useId: () => `test-${++instance}` })
  const setup = definition.setup(props, { expose() {}, emit: (...args) => emitted.push(args) })
  return { props, emitted, ...setup }
}

const textEvent = value => ({ target: { value } })
const checkedEvent = checked => ({ target: { checked } })
const payload = kind => ({ ...utils.emptySeriesPayload(kind), name: '星海', description: ' 原有中文\n第二行 ' })
const diff = (key, overrides = {}) => ({
  key, base: '旧来源', local: '本作保留', incoming: '新版内容',
  sourceChanged: true, localChanged: true, conflict: true, different: true, ...overrides,
})

test('actual payload editor scripts emit complete independent payloads for all five kinds without mutating inputs', () => {
  for (const { value: kind } of utils.SERIES_KINDS) {
    const original = Object.freeze(payload(kind))
    const h = harness('SeriesPayloadEditor', { kind, modelValue: original })
    const replacement = '  新的中文\n\n换行 🌙\r\n尾空格  '
    h.updateField('description', textEvent(replacement))
    assert.equal(h.emitted.length, 1)
    assert.equal(h.emitted[0][0], 'update:modelValue')
    assert.deepEqual(h.emitted[0][1], { ...original, description: replacement })
    assert.equal(original.description, ' 原有中文\n第二行 ')
    assert.notEqual(h.emitted[0][1], original)
    h.emitted[0][1].name = '独立修改'
    assert.equal(original.name, '星海')
    assert.equal(h.emitted[0][1].notes, '')
  }
})

test('editor preserves intentional clearing and uses the newest parent draft for the next update', () => {
  const h = harness('SeriesPayloadEditor', { kind: 'race', modelValue: payload('race') })
  h.updateField('description', textEvent(''))
  assert.equal(h.emitted[0][1].description, '')
  h.props.modelValue = { ...h.emitted[0][1], culture: '父组件中的新草稿' }
  h.updateField('notes', textEvent('  备注\n'))
  assert.equal(h.emitted[1][1].culture, '父组件中的新草稿')
  assert.equal(h.emitted[1][1].description, '')
  assert.equal(h.emitted[1][1].notes, '  备注\n')
})

test('editor guards disabled state, unknown fields and malformed input even if invoked programmatically', () => {
  const h = harness('SeriesPayloadEditor', { kind: 'race', modelValue: payload('race'), disabled: true })
  h.updateField('name', textEvent('不应修改'))
  h.props.disabled = false
  h.updateField('geography', textEvent('不属于种族'))
  h.updateField('notes', { target: null })
  h.updateField('notes', { target: { value: undefined } })
  assert.deepEqual(h.emitted, [])
  h.updateField('name', textEvent('明确修改'))
  assert.equal(h.emitted[0][1].name, '明确修改')
})

test('editor field IDs are unique by instance and kind, with optional caller prefix', () => {
  const first = harness('SeriesPayloadEditor', { kind: 'race', modelValue: payload('race') })
  const second = harness('SeriesPayloadEditor', { kind: 'race', modelValue: payload('race') })
  assert.notEqual(first.fieldId('name'), second.fieldId('name'))
  first.props.idPrefix = 'template-draft'
  assert.equal(first.fieldId('name'), 'template-draft-race-name')
  first.props.kind = 'calendar'
  assert.equal(first.fieldId('name'), 'template-draft-calendar-name')
  assert.ok(first.fields.value.some(field => field.key === 'dateFormat'))
})

test('actual comparison script starts with no selections and only emits explicit checkbox decisions', () => {
  const selection = Object.freeze([])
  const h = harness('SeriesFieldComparison', { kind: 'race', fields: [diff('description'), diff('notes', { incoming: '' })], modelValue: selection })
  assert.deepEqual(h.emitted, [])
  assert.deepEqual(h.props.modelValue, [])
  h.selectField('notes', checkedEvent(true))
  assert.deepEqual(h.emitted, [['update:modelValue', ['notes']]])
  assert.deepEqual(selection, [])
  h.props.modelValue = h.emitted[0][1]
  h.selectField('description', checkedEvent(true))
  assert.deepEqual(h.emitted[1][1], ['notes', 'description'])
  h.props.modelValue = h.emitted[1][1]
  h.selectField('notes', checkedEvent(false))
  assert.deepEqual(h.emitted[2][1], ['description'])
  assert.deepEqual(h.emitted[1][1], ['notes', 'description'])
})

test('comparison guards disabled state and foreign fields and never generates an adopt or review event', () => {
  const h = harness('SeriesFieldComparison', { kind: 'race', fields: [diff('notes')], modelValue: ['notes'], disabled: true })
  h.selectField('notes', checkedEvent(false))
  h.props.disabled = false
  h.selectField('missing', checkedEvent(true))
  h.selectField('notes', { target: null })
  h.selectField('notes', { target: { checked: 'yes' } })
  assert.deepEqual(h.emitted, [])
  h.selectField('notes', checkedEvent(true))
  assert.deepEqual(h.emitted[0], ['update:modelValue', ['notes']])
  h.selectField('notes', checkedEvent(false))
  assert.deepEqual(h.emitted[1], ['update:modelValue', []])
  assert.deepEqual(h.props.modelValue, ['notes'])
})

test('rendered readouts show every field and intentional empties, preserving and escaping author text', async () => {
  const definition = component('SeriesPayloadReadout', true)
  for (const { value: kind } of utils.SERIES_KINDS) {
    const content = { ...payload(kind), description: '  中文\n<script>danger()</script>\n  ' }
    const html = await renderToString(vue.createSSRApp(definition, { kind, payload: content }))
    for (const field of utils.seriesFields(kind)) assert.ok(html.includes(field.label))
    assert.ok(html.includes('  中文\n&lt;script&gt;danger()&lt;/script&gt;\n  '))
    assert.ok(!html.includes('<script>'))
    assert.equal((html.match(/（空）/g) || []).length, utils.seriesFields(kind).length - 2)
  }
})

test('rendered editor associates native labels, limits name to 200, and disables all input fields while pending', async () => {
  const html = await renderToString(vue.createSSRApp(component('SeriesPayloadEditor', true), {
    kind: 'calendar', modelValue: payload('calendar'), disabled: true, idPrefix: 'source',
  }))
  assert.match(html, /<fieldset[^>]* disabled/)
  assert.match(html, /<label for="source-calendar-name"/)
  assert.match(html, /<input[^>]*id="source-calendar-name"[^>]*maxlength="200"[^>]*disabled/)
  assert.equal((html.match(/<textarea/g) || []).length, 6)
  assert.equal((html.match(/maxlength="20000"/g) || []).length, 6)
  assert.equal((html.match(/<textarea[^>]*disabled/g) || []).length, 6)
})

test('rendered comparison distinguishes conflicts and clearing and leaves all checkboxes unchecked until selected', async () => {
  const definition = component('SeriesFieldComparison', true)
  const props = { kind: 'race', fields: [diff('description'), diff('notes', { incoming: '' })], modelValue: [] }
  const html = await renderToString(vue.createSSRApp(definition, props))
  for (const label of ['上次比较基线', '本作内容', '选定母本版本', '双方修改且不同', '将清空本作此字段']) assert.ok(html.includes(label))
  assert.equal((html.match(/type="checkbox"/g) || []).length, 2)
  assert.ok(!/<input[^>]* checked/.test(html))
  assert.equal((html.match(/保留本作<\/span>/g) || []).length, 2)
  const selected = await renderToString(vue.createSSRApp(definition, { ...props, modelValue: ['notes'], disabled: true }))
  assert.equal((selected.match(/<input[^>]* checked/g) || []).length, 1)
  assert.equal((selected.match(/<input[^>]* disabled/g) || []).length, 2)
})
