import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { computed, effectScope, nextTick, reactive, ref, watch } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { compileSources } from './helpers/load-source.mjs'

const { chapterStatuses, documentText, blockList } = await compileSources()('utils/writing')
const { draftFingerprint, draftHasChanges } = await compileSources()('utils/modalDraft')
const clone = value => JSON.parse(JSON.stringify(value))
const scriptFor = path => {
  const source = readFileSync(new URL(path, import.meta.url), 'utf8')
  const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  return ts.transpileModule(script, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText.replace(/^import .* from .*\n/gm, '')
}
const viewJs = scriptFor('../src/views/WritingView.vue')
const modalJs = scriptFor('../src/components/common/BaseModal.vue')

// Evaluate the real view setup and modal submission guard. Rendering and HTTP
// are replaced, while the view's state, actions and route watchers run as-is.
async function harness() {
  const scope = effectScope(), toasts = [], writes = []
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/novel/:novelId/writing', component: {} }] })
  await router.push('/novel/6/writing?chapter=first')
  const volumes = ['a', 'b', 'c'].map(uid => ({ uid, title: `篇卷${uid}` }))
  const chapters = [
    { uid: 'first', title: '第一章', volumeId: 'b', deleted: false },
    { uid: 'deleted', title: '已删除章', volumeId: 'b', deleted: true },
    { uid: 'other', title: '其他章节', volumeId: 'c', deleted: false },
  ]
  const writer = reactive({
    novelId: 6, workspace: { volumes, chapters }, current: chapters[0],
    loading: false, saving: false, recovery: null, conflict: null,
    async select(uid) { this.current = this.workspace.chapters.find(chapter => chapter.uid === uid); return !!this.current },
    async create(title, volumeId, { uid }) {
      const row = { uid, title, volumeId, deleted: false }
      this.workspace.chapters.push(row); this.current = row
      return row
    },
    async structure(nextVolumes, nextChapters = this.workspace.chapters) {
      writes.push(clone({ volumes: nextVolumes, chapters: nextChapters }))
      this.workspace.volumes = clone(nextVolumes); this.workspace.chapters = clone(nextChapters)
    },
  })
  const runtime = {
    ref, computed, watch, nextTick, chapterStatuses, blockList, documentText,
    useWritingStore: () => writer, useWritingDeskStore: () => ({ data: null }),
    useStudioStore: () => ({}), useAppStore: () => ({ showToast: (...args) => toasts.push(args) }), useIdeasStore: () => ({}),
    useRouter: () => router, useRoute: () => new Proxy({}, { get: (_, key) => router.currentRoute.value[key] }),
    onMounted() {}, onBeforeUnmount() {}, onBeforeRouteLeave() {},
  }
  const names = 'openCreate, openVolume, submitDialog, moveVolume, removeVolume, dialog, dialogTitle, dialogVolume, dialogError, volumeUid, volumeNotice, volumeActionBusy, managedVolumeIndex, treeOpen, trash, treeQuery, collapsed, rows, visibleChapterCount'
  const view = scope.run(() => new Function(...Object.keys(runtime), `${viewJs}\nreturn { ${names} };`)(...Object.values(runtime)))
  const modalProps = { get busy() { return view.volumeActionBusy.value }, submit: view.submitDialog }
  const emitted = []
  const modalRuntime = { ref, computed, nextTick, draftFingerprint, draftHasChanges, provideModalDrafts: () => ({ value: false }), withDefaults: value => value, defineProps: () => modalProps, defineEmits: () => (...args) => emitted.push(args), useDialog() {} }
  const modal = new Function(...Object.keys(modalRuntime), `${modalJs}\nreturn { confirm, close, pending };`)(...Object.values(modalRuntime))
  return { ...view, writer, router, writes, toasts, modal, emitted, stop: () => scope.stop() }
}

test('new chapters leave trash/search filters and open their destination volume and directory', async () => {
  for (const destination of [null, 'b']) {
    const h = await harness()
    try {
      h.trash.value = true; h.treeQuery.value = '无法匹配的新章'; h.treeOpen.value = false
      h.collapsed.value[destination || ''] = true
      h.openCreate(destination); h.dialogTitle.value = '新的章节'
      await h.modal.confirm(); await nextTick()
      const chapter = h.writer.current
      assert.equal(chapter.title, '新的章节'); assert.equal(chapter.volumeId, destination)
      assert.equal(h.trash.value, false); assert.equal(h.treeQuery.value, '')
      assert.equal(h.collapsed.value[destination || ''], false)
      assert.equal(h.treeOpen.value, true, 'A successful creation must make its directory visible')
      assert.ok(h.rows(destination || '').some(row => row.uid === chapter.uid))
      assert.equal(h.router.currentRoute.value.query.chapter, chapter.uid)
      assert.equal(h.dialog.value, '')
    } finally { h.stop() }
  }
})

test('failed chapter creation keeps the title, target and directory context for retry', async () => {
  const h = await harness()
  try {
    h.trash.value = true; h.treeQuery.value = '第一章'; h.treeOpen.value = false; h.collapsed.value.b = true
    h.openCreate('b'); h.dialogTitle.value = '保留的新章标题'
    h.writer.create = async () => { throw { response: { data: { message: '保存失败，请稍后重试' } } } }
    await h.modal.confirm()
    assert.equal(h.dialog.value, 'chapter'); assert.equal(h.dialogTitle.value, '保留的新章标题')
    assert.equal(h.dialogVolume.value, 'b'); assert.equal(h.dialogError.value, '保存失败，请稍后重试')
    assert.equal(h.trash.value, true); assert.equal(h.treeQuery.value, '第一章'); assert.equal(h.treeOpen.value, false); assert.equal(h.collapsed.value.b, true)
    assert.equal(h.router.currentRoute.value.query.chapter, 'first'); assert.deepEqual(h.toasts, [])
  } finally { h.stop() }
})

test('volume moves reject both boundaries and remain in management with an unsaved rename intact', async () => {
  const h = await harness()
  try {
    h.openVolume('a'); assert.equal(h.managedVolumeIndex.value, 0)
    await h.moveVolume(-1); assert.equal(h.writes.length, 0)
    h.openVolume('c'); assert.equal(h.managedVolumeIndex.value, 2)
    await h.moveVolume(1); assert.equal(h.writes.length, 0)
    h.openVolume('b'); h.dialogTitle.value = '尚未确认的卷名'
    await h.moveVolume(-1)
    assert.equal(h.dialog.value, 'volume'); assert.equal(h.volumeUid.value, 'b')
    assert.equal(h.dialogTitle.value, '尚未确认的卷名'); assert.equal(h.managedVolumeIndex.value, 0)
    assert.match(h.volumeNotice.value, /第 1 卷/)
    assert.deepEqual(h.writer.workspace.volumes.map(volume => volume.uid), ['b', 'a', 'c'])
    assert.equal(h.writer.workspace.volumes[0].title, '篇卷b', 'Moving must not silently save a pending name')
    await h.modal.confirm()
    assert.equal(h.writer.workspace.volumes[0].title, '尚未确认的卷名')
    assert.deepEqual(h.writer.workspace.volumes.map(volume => volume.uid), ['b', 'a', 'c'])
    assert.equal(h.dialog.value, '')
  } finally { h.stop() }
})

test('an in-flight move blocks repeated moves, remove, rename submission and modal dismissal', async () => {
  const h = await harness()
  try {
    h.openVolume('b'); h.dialogTitle.value = '保留此名称'
    let release, count = 0
    const gate = new Promise(resolve => { release = resolve })
    const structure = h.writer.structure
    h.writer.structure = async (...args) => { count++; await gate; return structure.apply(h.writer, args) }
    const pending = h.moveVolume(-1)
    assert.equal(h.volumeActionBusy.value, true); assert.equal(h.modal.pending.value, true)
    await Promise.all([h.moveVolume(-1), h.moveVolume(1), h.removeVolume(), h.modal.confirm()])
    h.modal.close(); assert.deepEqual(h.emitted, []); assert.equal(count, 1)
    release(); await pending
    assert.equal(h.volumeActionBusy.value, false); assert.equal(h.modal.pending.value, false)
    assert.equal(h.dialog.value, 'volume'); assert.equal(h.dialogTitle.value, '保留此名称')
    assert.equal(h.writes.length, 1)
  } finally { h.stop() }
})

test('the modal prevents duplicate chapter submissions while creation is pending', async () => {
  const h = await harness()
  try {
    h.openCreate('a'); h.dialogTitle.value = '只新增一次'
    let release, count = 0
    const gate = new Promise(resolve => { release = resolve })
    const create = h.writer.create
    h.writer.create = async (...args) => { count++; await gate; return create.apply(h.writer, args) }
    const pending = h.modal.confirm()
    await h.modal.confirm(); assert.equal(count, 1); assert.equal(h.modal.pending.value, true)
    release(); await pending
    assert.equal(h.writer.workspace.chapters.filter(chapter => chapter.title === '只新增一次').length, 1)
    assert.equal(h.modal.pending.value, false)
  } finally { h.stop() }
})

test('volume action errors stay in the modal and a successful retry clears the failure', async () => {
  for (const action of ['moveVolume', 'removeVolume']) {
    const h = await harness()
    try {
      h.openVolume('b'); h.dialogTitle.value = '未保存卷名'
      const structure = h.writer.structure
      h.writer.structure = async () => { throw { response: { data: { message: '篇卷版本冲突，请重试' } } } }
      await h[action](-1)
      assert.equal(h.dialog.value, 'volume'); assert.equal(h.dialogTitle.value, '未保存卷名')
      assert.equal(h.dialogError.value, '篇卷版本冲突，请重试'); assert.equal(h.volumeActionBusy.value, false)
      assert.deepEqual(h.toasts, []); assert.equal(h.writes.length, 0)
      h.writer.structure = structure
      await h[action](-1)
      assert.equal(h.dialogError.value, ''); assert.equal(h.writes.length, 1)
    } finally { h.stop() }
  }
})

test('removing a volume preserves all chapters, including trash, and moves only its members to unassigned', async () => {
  const h = await harness()
  try {
    const before = clone(h.writer.workspace.chapters)
    h.openVolume('b'); await h.removeVolume()
    assert.deepEqual(h.writer.workspace.volumes.map(volume => volume.uid), ['a', 'c'])
    assert.deepEqual(h.writer.workspace.chapters, before.map(chapter => ({ ...chapter, volumeId: chapter.volumeId === 'b' ? null : chapter.volumeId })))
    assert.equal(h.dialog.value, ''); assert.equal(h.volumeActionBusy.value, false)
  } finally { h.stop() }
})
