import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse as parseSfc } from '@vue/compiler-sfc'
import { baseParse } from '@vue/compiler-dom'

// Exercise the actual draft expressions against every editable control in each
// template. A forgotten auxiliary array or uploaded image must fail this audit.
const views = ['CharactersView', 'ForeshadowsView', 'OutlinesView', 'TimelineView', 'MapView', 'WorldviewView', 'ScenesView', 'RelationshipsView', 'TagsView', 'NovelSelector']

function elements(node, predicate) {
  return [...(node.type === 1 && predicate(node) ? [node] : []), ...(node.children ?? []).flatMap(child => elements(child, predicate))]
}
function directive(node, name, argument) {
  return node.props.find(prop => prop.type === 7 && prop.name === name && (argument === undefined || prop.arg?.content === argument))?.exp?.content
}
function evaluate(expression, state, event) {
  return new Function(...Object.keys(state), '$event', `return (${expression})`)(...Object.values(state), event)
}
function field(state, path) {
  const parts = path.split('.'), name = parts.pop()
  let object = state
  for (const part of parts) object = object[part] ??= {}
  return { object, name }
}

let editableCount = 0, deleteCount = 0, aiApplyCount = 0, imageCount = 0
for (const view of views) {
  const source = readFileSync(new URL(`../src/views/${view}.vue`, import.meta.url), 'utf8')
  const { descriptor } = parseSfc(source)
  const template = baseParse(descriptor.template.content)
  for (const modal of elements(template, node => node.tag === 'BaseModal')) {
    const submit = directive(modal, 'bind', 'submit')
    const models = elements(modal, node => !!directive(node, 'model')).map(node => ({ node, path: directive(node, 'model') }))
    const draft = directive(modal, 'bind', 'draft')
    if (!models.length) {
      deleteCount++
      test(`${view} ${submit} does not add a discard prompt to a deletion confirmation`, () => {
        assert.equal(draft, undefined)
        assert.match(submit, /^delete/)
      })
      continue
    }
    editableCount++
    test(`${view} ${submit} protects every text, image, selection and association draft`, () => {
      assert.ok(draft, 'editable modal must expose its complete draft')
      const state = {}
      for (const { node, path } of models) {
        const { object, name } = field(state, path)
        object[name] = node.tag === 'TagSelector' || path.endsWith('Ids') ? [21, 8] : ''
      }
      const original = JSON.stringify(evaluate(draft, state))
      assert.notEqual(original, undefined)
      for (const { path } of models) {
        const { object, name } = field(state, path)
        const before = structuredClone(object[name])
        if (Array.isArray(object[name])) object[name].push(73)
        else object[name] = `unsaved-${path}`
        assert.notEqual(JSON.stringify(evaluate(draft, state)), original, `loses unsaved input: ${path}`)
        object[name] = before
        assert.equal(JSON.stringify(evaluate(draft, state)), original, `reverting ${path} must restore a clean draft`)
        if (Array.isArray(object[name])) {
          assert.deepEqual(object[name], [21, 8], 'reading a draft must not sort the original selection')
          object[name].push(object[name].shift())
          assert.equal(JSON.stringify(evaluate(draft, state)), original, `unchecking and rechecking ${path} must remain clean`)
          assert.deepEqual(object[name], [8, 21], 'normalizing a snapshot must preserve current selection order')
          object[name] = before
        }
      }
      for (const panel of elements(modal, node => node.tag === 'AiAssistantPanel')) {
        const apply = directive(panel, 'on', 'apply')
        if (!apply) continue
        const candidate = models.find(({ path }) => /\.(content|description|background)$/.test(path))
        assert.ok(candidate, 'AI adoption must target a tracked text field')
        evaluate(apply, state, { field: candidate.path.split('.').at(-1), value: '已采用、尚未保存的 AI 建议' })
        assert.notEqual(JSON.stringify(evaluate(draft, state)), original, 'adopting an AI suggestion must protect the new text')
      }
    })
    aiApplyCount += elements(modal, node => node.tag === 'AiAssistantPanel' && !!directive(node, 'on', 'apply')).length
    imageCount += models.filter(({ node }) => node.tag === 'ImageUpload').length
    for (const { node } of models.filter(({ node }) => node.tag === 'ImageUpload')) {
      test(`${view} disables modal closure and submission until image upload completes`, () => {
        const busy = directive(modal, 'bind', 'busy')
        const onUploading = directive(node, 'on', 'uploading')
        assert.ok(busy)
        assert.ok(onUploading)
        const state = { saving: false, imageUploading: false }
        const uploadChanged = new Function('state', '$event', `with (state) { ${onUploading} }`)
        assert.equal(evaluate(busy, state), false)
        uploadChanged(state, true)
        assert.equal(evaluate(busy, state), true)
        uploadChanged(state, false)
        assert.equal(evaluate(busy, state), false)
        assert.ok(submit, 'BaseModal must own the asynchronous save promise')
        if (busy.includes('saving')) {
          state.saving = true
          assert.equal(evaluate(busy, state), true, 'upload completion must not unlock an active save')
        }
      })
    }
  }
}

test('draft protection audit covers all business editors, tag dialogs, associations and image uploads', () => {
  assert.equal(editableCount, 19)
  assert.equal(deleteCount, 11)
  assert.equal(aiApplyCount, 4)
  assert.equal(imageCount, 4)
})
