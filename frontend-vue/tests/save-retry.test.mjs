import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'
import { parse } from '@vue/compiler-sfc'

// Execute each real view's save handler with controlled API outcomes, without a browser.
const modules = [
  ['Characters', 'Character', 'charactersStore'],
  ['Scenes', 'Scene', 'scenesStore'],
  ['Foreshadows', 'Foreshadow', 'foreshadowsStore'],
  ['Outlines', 'Outline', 'outlinesStore'],
  ['Timeline', 'Event', 'timelineStore'],
  ['Map', 'Location', 'mapStore']
]

function setup(view, entity, storeName) {
  const source = readFileSync(new URL(`../src/views/${view}View.vue`, import.meta.url), 'utf8')
  const script = parse(source).descriptor.scriptSetup.content
  const ast = ts.createSourceFile('view.ts', script, ts.ScriptTarget.Latest, true)
  const handler = ast.statements.find(node => ts.isFunctionDeclaration(node) && node.name.text === `save${entity}`)
  assert.ok(handler, 'save handler exists')
  const calls = { create: 0, update: 0, tags: 0, close: 0, alerts: 0 }
  const editing = { value: null }
  const saving = { value: false }
  const record = { id: 42 }
  const store = {
    [`create${entity}`]: async () => { calls.create++; return record },
    [`update${entity}`]: async id => { assert.equal(id, 42); calls.update++; return record },
    [`set${entity}Tags`]: async id => { assert.equal(id, 42); calls.tags++ }
  }
  const context = vm.createContext({
    saving,
    form: { name: 'test', title: 'test' },
    formTagIds: { value: [7] },
    [`editing${entity}`]: editing,
    [storeName]: store,
    closeModal: () => { calls.close++; editing.value = null },
    alert: () => { calls.alerts++ },
    console: { error() {} }
  })
  const code = ts.transpileModule(handler.getText(ast), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText
  vm.runInContext(code, context)
  return { calls, editing, saving, store, save: context[`save${entity}`], record }
}

for (const [view, entity, storeName] of modules) {
  test(`${view}: repeated confirmation only sends one in-flight create`, async () => {
    const state = setup(view, entity, storeName)
    let resolve
    state.store[`create${entity}`] = () => {
      state.calls.create++
      return new Promise(done => { resolve = done })
    }
    const pending = state.save()
    assert.equal(state.saving.value, true)
    await state.save()
    assert.equal(state.calls.create, 1)
    resolve(state.record)
    await pending
    assert.equal(state.saving.value, false)
    assert.equal(state.calls.close, 1)
  })

  test(`${view}: failed create leaves the form open and releases the submission lock`, async () => {
    const state = setup(view, entity, storeName)
    const create = state.store[`create${entity}`]
    state.store[`create${entity}`] = async () => { throw new Error('offline') }
    await state.save()
    assert.equal(state.saving.value, false)
    assert.equal(state.editing.value, null)
    assert.equal(state.calls.close, 0)
    assert.equal(state.calls.alerts, 1)
    state.store[`create${entity}`] = create
    await state.save()
    assert.equal(state.calls.create, 1)
    assert.equal(state.calls.close, 1)
  })

  if (entity === 'Location') continue
  test(`${view}: tag failure retries against the created ID without duplicating it`, async () => {
    const state = setup(view, entity, storeName)
    const setTags = state.store[`set${entity}Tags`]
    state.store[`set${entity}Tags`] = async () => { throw new Error('tag request failed') }
    await state.save()
    assert.equal(state.calls.create, 1)
    assert.equal(state.editing.value.id, 42)
    assert.equal(state.calls.close, 0)
    assert.equal(state.saving.value, false)
    state.store[`set${entity}Tags`] = setTags
    await state.save()
    assert.equal(state.calls.create, 1)
    assert.equal(state.calls.update, 1)
    assert.equal(state.calls.tags, 1)
    assert.equal(state.calls.close, 1)
  })
}
