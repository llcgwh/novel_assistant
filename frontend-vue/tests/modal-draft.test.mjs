import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources()
const drafts = await load('utils/modalDraft')
const dialogs = await load('composables/useDialog')
const modalDrafts = await load('composables/modalDrafts')
const aiUtils = await load('utils/ai')
const imageUtils = await load('utils/imageUpload')
const cardUtils = await load('utils/editableCard'), worldviewTypes = await load('types/worldview')
function component(name, dependencies = {}) {
  const source = readFileSync(new URL(`../src/components/common/${name}.vue`, import.meta.url), 'utf8')
  const { descriptor } = parse(source)
  const compiled = compileScript(descriptor, { id: name, inlineTemplate: true })
  const code = ts.transpileModule(compiled.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }
  new Function('require', 'module', 'exports', code)(name => {
    if (name === 'vue') return Vue
    if (name === '@/composables/useDialog') return dialogs
    if (name === '@/composables/modalDrafts') return modalDrafts
    if (name === '@/utils/modalDraft') return drafts
    if (dependencies[name]) return dependencies[name]
    throw Error(`Unexpected component dependency: ${name}`)
  }, module, module.exports)
  return module.exports.default
}
const DiscardChangesDialog = component('DiscardChangesDialog')
const BaseModal = component('BaseModal', { './DiscardChangesDialog.vue': { default: DiscardChangesDialog } })
const NativeModal = component('../settings/BackupCleanupDialog', { '@/components/common/DiscardChangesDialog.vue': { default: DiscardChangesDialog } })
let uploadImage = async () => ({ id: 99 })
const ImageUpload = component('ImageUpload', {
  '@/api/images': { imagesApi: { upload: (...args) => uploadImage(...args), getFileUrl: id => `/api/novels/1/images/${id}/file` } },
  '@/components/common/StudioIcon.vue': { default: Vue.defineComponent({ render: () => Vue.h('span', '图片') }) },
  '@/utils/imageUpload': imageUtils,
})
let suggestionMeta = {}
const AiPanel = component('../ai/AiAssistantPanel', {
  'vue-router': { useRoute: () => ({ params: { novelId: 1 } }) },
  '@/components/common/BaseSelect.vue': { default: Vue.defineComponent({ props: ['modelValue', 'options'], render() { return Vue.h('button', { type: 'button' }, this.options.find(option => option.value === this.modelValue)?.label) } }) },
  '@/api/ai': { aiApi: {
    config: async () => ({ configured: true, revision: 'fixture', baseUrl: 'https://fixture.invalid', model: 'fixture' }),
    cancel: async () => {},
    referenceChapters: async () => ({ maxChars: 10000, chapters: [{ uid: 'chapter-a', title: '序章', wordCount: 6000 }, { uid: 'chapter-b', title: '归途', wordCount: 4001 }] }),
    suggest: async (_novel, _action, _context, requestId) => ({ requestId, snapshotId: 'fixture', model: 'fixture', sources: [], coverage: { mode: 'settings', settingsCount: 1 }, result: { candidates: [{ title: '候选', reason: '说明', fields: { name: '候选人物' }, sourceRefs: [] }], directions: [], issues: [] }, ...suggestionMeta }),
  } },
  '@/api/writingDesk': { writingDeskApi: {} },
  '@/stores/writingDesk': { useWritingDeskStore: () => ({}) },
  '@/utils/ai': aiUtils,
})
const settle = async () => { await Vue.nextTick(); await Vue.nextTick(); await Vue.nextTick() }
const textOf = node => node.text + node.children.map(textOf).join('')
const all = (node, predicate) => [...(predicate(node) ? [node] : []), ...node.children.flatMap(child => all(child, predicate))]

// Mount the actual compiled SFCs, their Teleports and the shared focus/keyboard
// lifecycle. Only browser DOM primitives are represented by renderer nodes.
async function mount(t, options = {}) {
  const listeners = new Set(), events = []
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'document')
  const previousStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { length: 0, key: () => null, getItem: () => null } })
  let body, doc
  const node = (type, text = '') => Vue.markRaw({
    type, text, children: [], props: {}, parent: null, style: {}, inert: false, value: '', tagName: type.toUpperCase(), nodeType: type.startsWith('#') ? 3 : 1,
    get ownerDocument() { return doc }, get parentElement() { return this.parent },
    addEventListener() {}, removeEventListener() {}, showModal() { this.open = true }, close() { this.open = false },
    getBoundingClientRect: () => ({ left: 20, top: 20, right: 700, bottom: 700 }),
    get isConnected() { return this === body || !!this.parent?.isConnected },
    contains(other) { return this === other || this.children.some(child => child.contains(other)) },
    closest(selector) {
      for (let item = this; item; item = item.parent) {
        if (selector.includes('[inert]') && (item.inert || item.props.inert)) return item
        if (selector.includes('[disabled]') && item.props.disabled) return item
        if (selector.includes('[role="combobox"]') && item.props.role === 'combobox' && item.props['aria-expanded'] === 'true') return item
        if (selector.includes('button') && ['button', 'input', 'select', 'textarea', 'label', 'summary'].includes(item.type)) return item
        if (selector.includes('a[href]') && item.type === 'a' && item.props.href) return item
        if (selector.includes('[tabindex]') && item.props.tabindex !== undefined) return item
      }
      return null
    },
    getClientRects() { return this.isConnected ? [{}] : [] },
    querySelectorAll() { return this.children.flatMap(child => all(child, item => ['button', 'input', 'textarea', 'select'].includes(item.type) && !item.props.disabled || item.type === 'a' && item.props.href || item.props.tabindex !== undefined && item.props.tabindex !== '-1')) },
    focus(options) {
      if (!this.isConnected || this.closest('[inert]')) return
      this.lastFocusOptions = options
      doc.activeElement = this
      for (let item = this; item; item = item.parent) item.props.onFocusin?.({ target: this })
    },
  })
  body = node('body'); body.style.overflow = 'auto'
  const appElement = node('app'), root = node('root'), opener = node('button', '打开编辑')
  body.children.push(appElement, opener); appElement.parent = body; opener.parent = body
  appElement.children.push(root); root.parent = appElement
  doc = { body, activeElement: opener, getSelection: () => null, getElementById: () => appElement, addEventListener: (type, fn) => { if (type === 'keydown') listeners.add(fn) }, removeEventListener: (_type, fn) => listeners.delete(fn) }
  Object.defineProperty(globalThis, 'document', { configurable: true, value: doc })
  const renderer = Vue.createRenderer({
    createElement: node, createText: text => node('#text', text), createComment: text => node('#comment', text),
    setText: (element, text) => { element.text = text }, setElementText: (element, text) => { element.text = text; element.children = [] },
    patchProp: (element, key, _previous, value) => { element.props[key] = value },
    parentNode: element => element.parent, nextSibling: element => element.parent?.children[element.parent.children.indexOf(element) + 1] ?? null,
    querySelector: selector => selector === 'body' ? body : null,
    insert(element, parent, anchor = null) {
      if (element.parent) element.parent.children.splice(element.parent.children.indexOf(element), 1)
      element.parent = parent
      const index = anchor ? parent.children.indexOf(anchor) : -1
      if (index < 0) parent.children.push(element); else parent.children.splice(index, 0, element)
    },
    remove(element) { if (element.parent) element.parent.children.splice(element.parent.children.indexOf(element), 1); element.parent = null },
  })
  const state = Vue.reactive({ open: true, childShown: true, title: '人物档案', draft: { name: '初名', tags: [1] }, ...options })
  const app = renderer.createApp({ render: () => options.root ? Vue.h(options.root) : state.open ? Vue.h(options.native ? NativeModal : BaseModal, {
    ...Object.fromEntries(Object.entries(state).filter(([key]) => !['open', 'customActions', 'slot', 'media', 'childShown', 'native'].includes(key))),
    onClose: () => { events.push('close'); state.open = false },
    ...(!options.native ? { onConfirm: () => events.push('confirm') } : {}),
  }, {
    default: () => [Vue.h('input', { value: state.draft?.name, 'data-test': 'name' }), Vue.h('textarea', { 'data-test': 'notes' }), ...(options.slot ? options.slot(state) : [])],
    ...(options.media ? { media: () => options.media(state) } : {}),
    ...(options.customActions ? { actions: ({ close }) => Vue.h('button', { onClick: close }, '自定义关闭') } : {}),
  }) : null })
  app.component('RouterLink', { render() { return Vue.h('a', {}, this.$slots.default?.()) } })
  app.component('StudioIcon', { render() { return Vue.h('span') } })
  app.mount(root); await settle()
  t.after(() => {
    app.unmount(); assert.equal(listeners.size, 0); assert.equal(body.style.overflow, 'auto'); assert.equal(appElement.inert, false)
    if (previous) Object.defineProperty(globalThis, 'document', previous); else delete globalThis.document
    if (previousStorage) Object.defineProperty(globalThis, 'localStorage', previousStorage); else delete globalThis.localStorage
  })
  const find = predicate => all(body, predicate)[0]
  const click = async element => { element.props.onClick?.({ target: element, currentTarget: element, preventDefault() {}, stopPropagation() {} }); await settle() }
  const button = text => find(element => element.type === 'button' && textOf(element) === text)
  const key = async (key, shiftKey = false) => { const event = { key, shiftKey, target: doc.activeElement, preventDefault() {}, stopPropagation() {} }; for (const listener of [...listeners]) listener(event); await settle() }
  return { state, events, find, click, button, key, doc, body, opener, renderer, node, appElement, app }
}

test('draft comparison includes nested content, ignores key order, and treats reverted values as clean', () => {
  const original = { name: '初名', tags: [1], nested: { x: 2, y: 3 } }
  const baseline = drafts.draftFingerprint(original)
  assert.equal(drafts.draftHasChanges(baseline, { nested: { y: 3, x: 2 }, tags: [1], name: '初名' }), false)
  original.tags.push(2); assert.equal(drafts.draftHasChanges(baseline, original), true)
  original.tags.pop(); assert.equal(drafts.draftHasChanges(baseline, original), false)
  const cyclic = {}; cyclic.self = cyclic
  assert.equal(drafts.draftHasChanges(drafts.draftFingerprint(cyclic), cyclic), true)
})

test('the image pane belongs to the editor dialog and image-only edits share its outside-click and discard guard', async t => {
  const h = await mount(t, { media: state => Vue.h('button', { onClick: () => { state.draft.image = '/replacement.png' } }, '更换预览图片') })
  const panel = h.find(node => node.props.role === 'dialog'), pane = h.find(node => node.props.class === 'modal-media modal-fields')
  assert.ok(panel.contains(pane)); assert.match(panel.props.class, /modal-with-media/)
  const backdrop = h.find(node => node.props.class === 'modal')
  backdrop.props.onClick({ target: pane, currentTarget: backdrop }); await settle()
  assert.deepEqual(h.events, [], 'clicks on the image pane never dismiss the editor')
  const imageAction = h.button('更换预览图片'); imageAction.focus(); await h.click(imageAction)
  await h.key('Escape'); assert.ok(h.button('继续编辑')); assert.equal(panel.props.inert, true)
  await h.click(h.button('继续编辑')); assert.equal(h.state.draft.image, '/replacement.png'); assert.equal(h.doc.activeElement, imageAction)
  await h.key('Escape'); await h.click(h.button('放弃修改'))
  assert.deepEqual(h.events, ['close']); assert.equal(h.doc.activeElement, h.opener)
})

test('uploading from the image pane locks both media and main fields, then keeps the adopted image guarded', async t => {
  let resolve
  uploadImage = () => new Promise(yes => { resolve = yes })
  t.after(() => { uploadImage = async () => ({ id: 99 }) })
  const h = await mount(t, { draft: { name: '初名', picture: '/original.png' }, media: state => Vue.h(ImageUpload, {
    large: true, previewAlt: '人物肖像', modelValue: state.draft.picture, imageType: 'character_portrait',
    'onUpdate:modelValue': value => { state.draft.picture = value }, onUploading: value => { state.busy = value },
  }) })
  const original = h.find(node => node.type === 'a' && textOf(node).includes('查看原图'))
  assert.equal(original.props.href, '/original.png'); assert.equal(original.props.target, '_blank'); assert.match(original.props.rel, /noopener/)
  const input = h.find(node => node.type === 'input' && node.props.type === 'file')
  const pending = input.props.onChange({ target: { files: [new File(['png'], 'portrait.png', { type: 'image/png' })], value: 'portrait.png' } })
  await settle()
  assert.equal(h.state.busy, true)
  for (const field of all(h.body, node => node.type === 'fieldset')) assert.equal(field.props.disabled, true)
  await h.key('Escape'); await h.click(h.button('取消')); assert.deepEqual(h.events, [])
  assert.equal(h.find(node => node.type === 'a' && textOf(node).includes('查看原图')), undefined)
  resolve({ id: 99 }); await pending; await settle()
  assert.equal(h.state.busy, false); assert.equal(h.state.draft.picture, '/api/novels/1/images/99/file')
  const preview = h.find(node => node.type === 'img'); assert.equal(preview.props.alt, '人物肖像'); assert.equal(preview.props.src, h.state.draft.picture)
  await h.key('Escape'); assert.ok(h.button('继续编辑')); assert.deepEqual(h.events, [])
})

test('the original-image link remains in the same modal keyboard loop', async t => {
  const h = await mount(t, { media: () => Vue.h('a', { href: '/original.png', target: '_blank', rel: 'noopener' }, '查看原图') })
  const link = h.find(node => node.type === 'a'), last = h.button('确定')
  link.focus(); await h.key('Tab', true); assert.equal(h.doc.activeElement, last)
  await h.key('Tab'); assert.equal(h.doc.activeElement, link)
})

test('media editors initially focus the dialog without scrolling or activating image removal; ordinary dialogs retain their first-field focus', async t => {
  await t.test('media', async t => {
    const h = await mount(t, { media: () => Vue.h(ImageUpload, { large: true, modelValue: '/original.png', imageType: 'character_portrait' }) })
    const panel = h.find(node => node.props.role === 'dialog'), remove = h.find(node => node.props.title === '移除图片')
    assert.ok(remove); assert.equal(h.doc.activeElement, panel)
    assert.deepEqual(panel.lastFocusOptions, { preventScroll: true })
    assert.notEqual(h.doc.activeElement, remove)
    await h.key('Tab'); assert.equal(h.doc.activeElement, remove, 'explicit Tab keeps the normal media control order')
    await h.key('Tab', true); assert.equal(h.doc.activeElement, h.button('确定'))
    await h.key('Escape'); assert.deepEqual(h.events, ['close']); assert.equal(h.doc.activeElement, h.opener)
  })
  await t.test('ordinary', async t => {
    const h = await mount(t)
    const first = h.find(node => node.props['data-test'] === 'name')
    assert.equal(h.doc.activeElement, first); assert.equal(first.lastFocusOptions, undefined)
  })
})

for (const [view, kind, title, imageField] of [['CharactersView', 'character', '编辑人物', 'portraitImage'], ['ScenesView', 'scene', '编辑场景', 'sceneImage'], ['WorldviewView', 'worldview', '编辑世界观条目', 'entryImage']]) {
  test(`${view} card image clicks and Enter open the actual populated editor while controls and text selection stay separate`, async t => {
    const record = { id: 5, name: '回归测试资料', category: 'geography', [imageField]: '/original.png', tags: [], characters: [], scenes: [], mapLocations: [] }
    const records = [record], noop = async () => {}, Empty = Vue.defineComponent({ render: () => null })
    const stores = {
      characters: { characters: records, filteredCharacters: records, fetchCharacters: noop },
      scenes: { scenes: records, filteredScenes: records, fetchScenes: noop },
      worldview: { entries: records, filteredEntries: records, fetchEntries: noop },
      tags: { tags: [], fetchTags: noop }, map: { locations: [], fetchLocations: noop },
    }
    const dependencies = {
      'vue-router': { useRoute: () => ({ params: { novelId: '1' }, query: {} }) },
      '@/utils/editableCard': cardUtils, '@/types/worldview': worldviewTypes,
      '@/composables/useFormSave': { useFormSave: () => ({ reset() {}, save: noop }) },
      '@/composables/useEditQuery': { useEditQuery() {} },
      '@/components/common/BaseModal.vue': { default: BaseModal }, '@/components/common/ImageUpload.vue': { default: ImageUpload },
    }
    for (const [name, store] of Object.entries(stores)) dependencies[`@/stores/${name}`] = { [`use${name[0].toUpperCase() + name.slice(1)}Store`]: () => store }
    for (const name of ['ai/AiAssistantPanel', 'common/StudioIcon', 'common/BaseSelect', 'common/LoadingState', 'common/EmptyState', 'writing/WritingBacklinks', 'tags/TagSelector', 'tags/TagList', 'story/StoryStatePanel', 'series/SeriesWorkspace']) dependencies[`@/components/${name}.vue`] = { default: Empty }
    const View = component(`../../views/${view}`, dependencies), h = await mount(t, { root: View })
    const card = h.find(node => node.props['data-editable-card'] !== undefined), picture = all(card, node => node.type === 'img')[0]
    assert.equal(card.props.role, 'group'); assert.equal(card.props.tabindex, '0'); assert.match(card.props['aria-label'], /按 Enter 编辑/)
    const activate = async (target, type = 'click', key = undefined) => {
      const event = { type, key, button: 0, target, currentTarget: card, preventDefault() {}, stopPropagation() {} }
      if (type === 'click') card.props.onClick(event); else card.props.onKeydown(event)
      await settle()
    }
    await activate(picture)
    assert.ok(h.find(node => node.props.role === 'dialog' && node.props['aria-label'] === title))
    assert.equal(h.find(node => node.type === 'input' && node.props.placeholder?.includes(kind === 'character' ? '人物姓名' : kind === 'scene' ? '场景名称' : '条目名称')).value, record.name)
    assert.ok(h.find(node => typeof node.props.class === 'string' && node.props.class.includes('image-upload-large')))
    const pane = h.find(node => node.props.class === 'modal-media modal-fields')
    assert.ok(pane); assert.equal(all(pane, node => node.type === 'img')[0].props.src, '/original.png')
    await h.click(h.button('取消')); assert.equal(h.find(node => node.props.role === 'dialog'), undefined)
    await activate(card, 'keydown', 'Enter'); assert.ok(h.find(node => node.props['aria-label'] === title)); await h.click(h.button('取消'))
    const text = all(card, node => node.type === 'h3')[0]
    h.doc.getSelection = () => ({ isCollapsed: false, anchorNode: text, focusNode: text, rangeCount: 0 })
    await activate(text); assert.equal(h.find(node => node.props.role === 'dialog'), undefined)
    h.doc.getSelection = () => null
    for (const [label, dialogTitle] of [['标签', '管理标签'], ['删除', '确认删除']]) {
      const control = all(card, node => node.type === 'button' && textOf(node) === label)[0]
      await h.click(control); await activate(control)
      assert.ok(h.find(node => node.props.role === 'dialog' && node.props['aria-label'] === dialogTitle))
      assert.equal(h.find(node => node.props['aria-label'] === title), undefined)
      await h.click(h.button('取消'))
    }
    assert.equal(record[imageField], '/original.png'); assert.equal(record.name, '回归测试资料')
  })
}

const aiSlot = state => state.childShown ? [Vue.h(AiPanel, { scope: 'character', draft: { name: '原名' }, initiallyOpen: true })] : []
const aiRequest = h => h.find(node => node.type === 'textarea' && node.props.maxlength === '1500')
const typeInto = async (input, text) => {
  input.value = text
  input.props['onUpdate:modelValue'](text)
  await settle()
}

test('the rendered AI request disclosure follows the selected scope and its actual review and request limits', async t => {
  for (const scope of ['writing', 'character']) {
    await t.test(scope, async t => {
      const h = await mount(t, { slot: () => [Vue.h(AiPanel, { scope, draft: {}, initiallyOpen: true })] })
      const disclosure = textOf(h.find(node => node.props.class === 'ai-caption ai-destination'))
      assert.match(disclosure, /https:\/\/fixture\.invalid.*fixture/)
      assert.match(disclosure, /本次对话.*修正一轮/)
      if (scope === 'writing') {
        assert.match(disclosure, /全部文字设定与有效正文.*当前编辑稿/)
        assert.match(disclosure, /正文与对白边界复核/)
        assert.match(disclosure, /最多发送四次模型请求/)
        assert.doesNotMatch(disclosure, /仅检查格式和资料引用|最多发送两次/)
        assert.equal(h.find(node => node.props.class === 'ai-reference-chapters'), undefined)
      } else {
        assert.match(disclosure, /当前表单.*未选择正文/)
        assert.match(disclosure, /仅检查格式和资料引用/)
        assert.match(disclosure, /最多发送两次模型请求/)
        assert.doesNotMatch(disclosure, /正文与对白边界复核|最多发送四次/)
      }
    })
  }
})

test('rendered chapter choices show saved counts, disable over-limit generation, and guard selection-only edits until cleared', async t => {
  const h = await mount(t, { slot: aiSlot })
  const section = h.find(node => node.props.class === 'ai-reference-chapters')
  assert.ok(section); assert.equal(section.props.open, false)
  assert.match(textOf(section), /默认不选正文/)
  section.props.onToggle({ target: { open: true }, stopPropagation() {} }); await settle()
  const checkbox = title => all(h.body, node => node.type === 'label' && node.props.class === 'ai-reference-row').find(node => textOf(node).includes(title)).children.find(node => node.type === 'input')
  assert.match(textOf(section), /序章.*6,000 字/); assert.match(textOf(section), /归途.*4,001 字/)
  assert.equal(checkbox('序章').props.checked, false)
  checkbox('序章').props.onChange({ target: { checked: true } }); await settle()
  assert.match(textOf(h.find(node => node.props.class === 'ai-caption ai-destination')), /你勾选的正文章节/)
  assert.equal(h.button('生成建议').props.disabled, false)
  checkbox('归途').props.onChange({ target: { checked: true } }); await settle()
  assert.equal(h.button('生成建议').props.disabled, true)
  assert.match(textOf(h.body), /10,001 \/ 10,000 字.*减少勾选后再生成.*不会截断正文/)
  await h.key('Escape'); assert.deepEqual(h.events, []); assert.ok(h.button('继续编辑'))
  await h.click(h.button('继续编辑')); assert.equal(checkbox('序章').props.checked, true); assert.equal(checkbox('归途').props.checked, true)
  await h.click(h.button('清空选择')); assert.equal(h.button('生成建议').props.disabled, false)
  assert.match(textOf(h.find(node => node.props.class === 'ai-caption ai-destination')), /未选择正文/)
  await h.key('Escape'); assert.deepEqual(h.events, ['close'])
})

test('selected manuscript coverage is shown from the generated snapshot', async t => {
  t.after(() => { suggestionMeta = {} })
  const h = await mount(t, { slot: aiSlot })
  suggestionMeta = { coverage: { mode: 'settings', settingsCount: 8, selectedChapterCount: 2, selectedManuscriptChars: 9999 } }
  await h.click(h.button('生成建议')); await settle()
  assert.match(textOf(h.body), /本次覆盖 8 条设定；所选正文 2 章，共 9,999 字/)
})

test('a corrected response shows its reason and a later uncorrected result removes the previous success notice', async t => {
  t.after(() => { suggestionMeta = {} })
  const h = await mount(t, { slot: aiSlot })
  assert.equal(h.find(node => node.props.role === 'status' && textOf(node).includes('已根据检查反馈修正')), undefined)
  suggestionMeta = { correctionApplied: true, correctionReason: '首轮格式不完整，已补齐候选字段。' }
  await h.click(h.button('生成建议')); await settle()
  const notice = h.find(node => node.props.role === 'status' && textOf(node).includes('已根据检查反馈修正'))
  assert.ok(notice)
  assert.match(textOf(notice), /修正 1 轮.*首轮格式不完整，已补齐候选字段。/)
  suggestionMeta = { correctionApplied: false, correctionReason: '这段不应显示为修正成功' }
  await h.click(h.button('生成建议')); await settle()
  assert.equal(h.find(node => node.props.role === 'status' && textOf(node).includes('已根据检查反馈修正')), undefined)
  assert.doesNotMatch(textOf(h.body), /这段不应显示为修正成功/)
  assert.ok(h.button('采用到姓名草稿'), 'the uncorrected result still renders normally')
})

for (const native of [false, true]) {
  test(`AI preference-only edits participate in the ${native ? 'native' : 'regular'} dialog guard and preserve text when continued`, async t => {
    const h = await mount(t, { native, slot: aiSlot })
    const input = aiRequest(h)
    assert.ok(input)
    input.focus(); await typeInto(input, '保留角色设定')
    await h.key('Escape')
    assert.deepEqual(h.events, [])
    const warning = h.find(node => node.props.role === 'alertdialog')
    assert.ok(warning)
    if (native) assert.equal(warning.parent.parent.type, 'dialog')
    await h.click(h.button('继续编辑'))
    assert.equal(aiRequest(h).value, '保留角色设定')
    assert.equal(h.doc.activeElement, input)
    await typeInto(input, '')
    await h.key('Escape')
    assert.deepEqual(h.events, ['close'], 'restoring the initial AI request removes its dirty state')
  })
}

test('an untouched AI panel does not warn, while a generated unused candidate warns without parent form changes', async t => {
  await t.test('untouched', async t => {
    const h = await mount(t, { slot: aiSlot }); await h.key('Escape')
    assert.deepEqual(h.events, ['close'])
  })
  await t.test('candidate', async t => {
    const h = await mount(t, { slot: aiSlot }); await h.click(h.button('生成建议')); await settle()
    assert.ok(h.button('采用到姓名草稿'))
    await h.key('Escape')
    assert.deepEqual(h.events, []); assert.ok(h.find(node => node.props.role === 'alertdialog'))
    await h.click(h.button('继续编辑'))
    assert.ok(h.button('采用到姓名草稿'), 'continuing must retain the generated result')
    h.state.childShown = false; await settle()
    await h.key('Escape')
    assert.deepEqual(h.events, ['close'], 'unmount removes the child registration rather than leaving a stale warning')
  })
})

test('remounting an AI child starts clean and a parent successful save can close with child edits', async t => {
  const h = await mount(t, { slot: aiSlot })
  await typeInto(aiRequest(h), '旧子草稿')
  h.state.childShown = false; await settle(); h.state.childShown = true; await settle()
  assert.equal(aiRequest(h).value, '')
  await typeInto(aiRequest(h), '本次偏好')
  h.state.open = false; await settle()
  assert.equal(h.find(node => node.props.role === 'alertdialog'), undefined)
  assert.equal(h.doc.activeElement, h.opener)
})

test('standalone writing inspiration is guarded without a parent form draft, including a custom close action', async t => {
  const h = await mount(t, {
    draft: undefined,
    customActions: true,
    slot: () => [Vue.h(AiPanel, { scope: 'writing', draft: {}, initiallyOpen: true })],
  })
  await typeInto(aiRequest(h), '下一段先揭示线索')
  await h.click(h.button('自定义关闭'))
  assert.deepEqual(h.events, [])
  assert.ok(h.find(node => node.props.role === 'alertdialog'))
  await h.click(h.button('放弃修改'))
  assert.deepEqual(h.events, ['close'])
})

test('a nested editor owns its AI registration and discarding it leaves the outer dialog clean', async t => {
  const h = await mount(t, {
    childShown: false,
    slot: state => state.childShown ? [Vue.h(BaseModal, {
      title: '内层编辑', onClose: () => { state.childShown = false },
    }, { default: () => [Vue.h(AiPanel, { scope: 'character', draft: {}, initiallyOpen: true })] })] : [],
  })
  h.state.childShown = true; await settle()
  await typeInto(aiRequest(h), '只属于内层的偏好')
  await h.key('Escape')
  assert.deepEqual(h.events, [])
  await h.click(h.button('放弃修改'))
  assert.equal(h.state.childShown, false)
  assert.equal(h.state.open, true)
  await h.key('Escape')
  assert.deepEqual(h.events, ['close'], 'the inner discarded draft must not contaminate the outer scope')
})

test('untouched or reverted forms close directly through outside click, Escape and default cancel', async t => {
  for (const gesture of ['outside', 'escape', 'cancel']) {
    await t.test(gesture, async t => {
      const h = await mount(t); h.state.draft.name = '曾修改'; h.state.draft.name = '初名'; await settle()
      if (gesture === 'outside') await h.click(h.find(element => element.props.class === 'modal'))
      else if (gesture === 'escape') await h.key('Escape')
      else await h.click(h.button('取消'))
      assert.deepEqual(h.events, ['close']); assert.equal(h.find(element => element.props.role === 'alertdialog'), undefined)
      assert.equal(h.doc.activeElement, h.opener)
    })
  }
})

test('outside click, Escape and cancel guard actual changes; continuing preserves values and nested focus', async t => {
  for (const gesture of ['outside', 'escape', 'cancel', 'custom']) {
    await t.test(gesture, async t => {
      const h = await mount(t, { customActions: gesture === 'custom' })
      const input = h.find(element => element.props['data-test'] === 'notes'); input.focus()
      h.state.draft.name = '未保存的人名'; h.state.draft.tags.push(2); await settle()
      if (gesture === 'outside') {
        h.doc.activeElement = h.body // Outside pointer press can blur the input before click.
        await h.click(h.find(element => element.props.class === 'modal'))
      } else if (gesture === 'escape') await h.key('Escape')
      else await h.click(h.button(gesture === 'custom' ? '自定义关闭' : '取消'))
      assert.deepEqual(h.events, []); assert.ok(h.find(element => element.props.role === 'alertdialog'))
      assert.equal(h.doc.activeElement, h.button('继续编辑'))
      await h.key('Tab', true); assert.equal(h.doc.activeElement, h.button('放弃修改'))
      await h.key('Tab'); assert.equal(h.doc.activeElement, h.button('继续编辑'))
      await h.key('Escape')
      assert.equal(h.doc.activeElement, input); assert.deepEqual(h.events, [])
      assert.equal(h.state.draft.name, '未保存的人名'); assert.deepEqual([...h.state.draft.tags], [1, 2])
      await h.key('Escape'); await h.click(h.button('放弃修改'))
      assert.deepEqual(h.events, ['close']); assert.equal(h.doc.activeElement, h.opener)
    })
  }
})

test('confirmation backdrop continues editing, while a subsequent explicit discard closes only once', async t => {
  const h = await mount(t); h.state.draft.name = '改名'; await settle(); await h.key('Escape')
  await h.click(h.find(element => element.props.class === 'modal discard-changes-overlay'))
  assert.equal(h.state.open, true); assert.equal(h.find(element => element.props.role === 'alertdialog'), undefined)
  await h.key('Escape'); await h.click(h.button('放弃修改')); assert.deepEqual(h.events, ['close'])
})

test('saving blocks dismissal and duplicate submission; a failed save keeps the draft guarded', async t => {
  let reject, calls = 0
  const h = await mount(t, { submit: () => { calls++; return new Promise((_resolve, no) => { reject = no }) } })
  h.state.draft.name = '保存失败也保留'; await settle()
  await h.click(h.button('确定')); await h.key('Escape'); await h.click(h.find(element => element.props.class === 'modal'))
  assert.deepEqual(h.events, []); assert.equal(h.find(element => element.props.role === 'alertdialog'), undefined)
  await h.click(h.button('处理中…')); assert.equal(calls, 1)
  reject(Error('offline')); await settle(); assert.ok(h.find(element => element.props.role === 'alert'))
  await h.key('Escape'); assert.ok(h.find(element => element.props.role === 'alertdialog'))
  await h.click(h.button('继续编辑')); assert.equal(h.state.draft.name, '保存失败也保留')
})

test('successful parent closure needs no discard confirmation, and explicit dirty overrides remain optional', async t => {
  await t.test('saved', async t => {
    const h = await mount(t); h.state.draft.name = '已保存'; h.state.submit = async () => { h.state.open = false }; await settle()
    await h.click(h.button('确定')); assert.equal(h.state.open, false); assert.deepEqual(h.events, [])
    assert.equal(h.find(element => element.props.role === 'alertdialog'), undefined)
  })
  for (const dirty of [false, true]) await t.test(String(dirty), async t => {
    const h = await mount(t, { dirty }); h.state.draft.name = dirty ? '初名' : '修改'; await settle(); await h.key('Escape')
    assert.equal(!!h.find(element => element.props.role === 'alertdialog'), dirty)
    assert.deepEqual(h.events, dirty ? [] : ['close'])
  })
})

test('custom warning is accurate for retained local drafts', async t => {
  const h = await mount(t, { closeWarning: '尚未保存到作品，离开会保留本机草稿。', discardLabel: '保留草稿并离开' })
  h.state.draft.name = '本机稿'; await settle(); await h.key('Escape')
  assert.match(textOf(h.find(element => element.props.role === 'alertdialog')), /离开会保留本机草稿/)
  assert.ok(h.button('保留草稿并离开')); assert.equal(h.button('放弃修改'), undefined)
})

test('confirmation can render inside a native top-layer dialog without moving that dialog', async t => {
  const h = await mount(t); const nativePanel = h.node('dialog'); nativePanel.parent = h.body; h.body.children.push(nativePanel)
  const root = h.node('nested-root'); root.parent = nativePanel; nativePanel.children.push(root)
  let continued = 0
  const child = h.renderer.createApp({ render: () => Vue.h(DiscardChangesDialog, { teleportTo: nativePanel, onContinue: () => continued++ }) })
  child.mount(root); await settle()
  const alert = h.find(element => element.props.role === 'alertdialog')
  assert.ok(nativePanel.contains(alert)); assert.equal(nativePanel.parent, h.body)
  await h.key('Escape'); assert.equal(continued, 1); assert.deepEqual(h.events, [])
  child.unmount(); await settle()
})
