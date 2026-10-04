import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse } from '@vue/compiler-sfc'
import ts from 'typescript'
import { computed, effectScope, reactive, ref, watch } from 'vue'
import { compileSources } from './helpers/load-source.mjs'

const { documentText, plainDocument } = await compileSources()('utils/writing')
const source = readFileSync(new URL('../src/components/writing/WritingSync.vue', import.meta.url), 'utf8')
const script = parse(source).descriptor.scriptSetup.content
const ast = ts.createSourceFile('WritingSync.ts', script, ts.ScriptTarget.Latest, true)
const code = ts.transpileModule(ast.statements
  .filter(node => !ts.isImportDeclaration(node))
  .map(node => node.getText(ast)).join('\n'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
}).outputText
const manuscript = (title = '云端原稿') => ({ title, chapters: [{ uid: 'chapter', title: '第一章', text: title, wordCount: 4 }] })
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function setup(t) {
  const calls = []
  const writer = reactive({
    novelId: 1,
    workspace: { uid: 'local-book', chapters: [{ uid: 'chapter', title: '第一章', wordCount: 2 }] },
    current: { uid: 'chapter', doc: plainDocument('本机正文') },
    flush: async () => { calls.push(['flush']); return true },
    refresh: async () => { calls.push(['refresh']) },
    load: async id => { calls.push(['writer.load', id]) },
    select: async uid => { calls.push(['select', uid]) },
  })
  const transport = {
    get: async () => [],
    post: async url => url.endsWith('/preview') ? manuscript() : {},
    chapter: async () => ({ doc: plainDocument('本机另一章') }),
  }
  let mount, unmount, interval, intervalCleared = false
  const runtime = {
    computed, ref, watch, documentText, defineEmits() {},
    useWritingStore: () => writer,
    useWritingDeskStore: () => ({ load: async id => { calls.push(['desk.load', id]) } }),
    request: {
      get: (...args) => { calls.push(['get', ...args]); return transport.get(...args) },
      post: (...args) => { calls.push(['post', ...args]); return transport.post(...args) },
    },
    writingApi: { chapter: (...args) => { calls.push(['chapter', ...args]); return transport.chapter(...args) } },
    onMounted: fn => { mount = fn },
    onBeforeUnmount: fn => { unmount = fn },
    setInterval: fn => { interval = fn; return 17 },
    clearInterval: id => { intervalCleared = id === 17 },
  }
  const scope = effectScope()
  const state = scope.run(() => new Function(...Object.keys(runtime), `${code}; return {
    remoteUid, busy, operation, error, versions, remote, confirmed, compareUid, localText,
    load, preview, inspect, act, cancelPreview
  }`)(...Object.values(runtime)))
  const dispose = () => { unmount(); scope.stop() }
  t.after(dispose)
  return { ...state, writer, transport, calls, mount: () => mount(), tick: () => interval(), dispose,
    cleared: () => intervalCleared,
    posts: action => calls.filter(call => call[0] === 'post' && call[1].endsWith(`/${action}`)),
  }
}

test('version reads deduplicate actions and obsolete results cannot unlock or overwrite a newer read', async t => {
  for (const fail of [false, true]) await t.test(fail ? 'late failure' : 'late success', async t => {
    const h = setup(t), old = deferred(), next = deferred()
    h.transport.get = (_url, config) => config.params.book === 'local-book' ? old.promise : next.promise
    const pending = h.load()
    await Promise.all([h.load(), h.preview('duplicate.json'), h.act('push')])
    assert.equal(h.calls.length, 1)
    h.remoteUid.value = 'another-book'
    const newest = h.load()
    fail ? old.reject(Error('obsolete')) : old.resolve([{ file: 'old.json' }])
    await pending
    assert.equal(h.busy.value, true)
    assert.deepEqual(h.versions.value, [])
    assert.equal(h.error.value, '')
    next.resolve([{ file: 'new.json' }])
    await newest
    assert.deepEqual(h.versions.value, [{ file: 'new.json' }])
    assert.equal(h.busy.value, false)
  })
})

test('cancelling a pending preview prevents late success or failure from reopening it', async t => {
  for (const fail of [false, true]) await t.test(fail ? 'late failure' : 'late success', async t => {
    const h = setup(t), gate = deferred()
    h.transport.post = () => gate.promise
    const pending = h.preview('old.json')
    await h.preview('duplicate.json')
    assert.equal(h.posts('preview').length, 1)
    h.cancelPreview()
    assert.equal(h.busy.value, false)
    fail ? gate.reject(Error('obsolete')) : gate.resolve(manuscript())
    await pending
    assert.equal(h.remote.value, null)
    assert.equal(h.confirmed.value, false)
    assert.equal(h.error.value, '')
  })
})

test('changing remote identity clears versions, consent and comparison before another restore can start', async t => {
  const h = setup(t)
  h.versions.value = [{ file: 'old.json' }]
  await h.preview('old.json')
  h.confirmed.value = true
  h.remoteUid.value = 'other-book'
  assert.equal(h.remote.value, null)
  assert.equal(h.confirmed.value, false)
  assert.deepEqual(h.versions.value, [])
  assert.equal(h.compareUid.value, '')
  assert.equal(h.localText.value, '')
  await h.act('pull')
  assert.equal(h.posts('pull').length, 0)
  assert.equal(h.calls.some(call => call[0] === 'flush'), false)
})

test('comparison responses are isolated by chapter selection, cancellation and replacement preview', async t => {
  for (const fail of [false, true]) await t.test(fail ? 'late failure' : 'late success', async t => {
    const h = setup(t), gate = deferred()
    h.writer.workspace.chapters.push({ uid: 'another', title: '第二章' })
    await h.preview('first.json')
    h.transport.chapter = () => gate.promise
    const pending = h.inspect('another')
    await h.inspect('another')
    assert.equal(h.calls.filter(call => call[0] === 'chapter').length, 1)
    await h.inspect('chapter')
    assert.equal(h.localText.value, '本机正文\n')
    h.cancelPreview()
    await h.preview('second.json')
    fail ? gate.reject(Error('obsolete')) : gate.resolve({ doc: plainDocument('迟到正文') })
    await pending
    assert.equal(h.localText.value, '本机正文\n')
    assert.equal(h.compareUid.value, 'chapter')
    assert.equal(h.error.value, '')
  })
})

test('unmount and an A to B to A switch invalidate pending version and preview responses', async t => {
  for (const mode of ['unmount', 'switch']) for (const action of ['load', 'preview']) {
    await t.test(`${mode}: ${action}`, async t => {
      const h = setup(t), gate = deferred()
      h.transport[action === 'load' ? 'get' : 'post'] = () => gate.promise
      const pending = h[action]('version.json')
      if (mode === 'unmount') h.dispose()
      else { h.writer.novelId = 2; h.writer.novelId = 1 }
      gate.resolve(action === 'load' ? [{ file: 'old.json' }] : manuscript())
      await pending
      assert.deepEqual(h.versions.value, [])
      assert.equal(h.remote.value, null)
      assert.equal(h.error.value, '')
      assert.equal(h.busy.value, false)
    })
  }
})

test('restore requires explicit preview consent and sends its exact book and file only once', async t => {
  const h = setup(t), flush = deferred(), response = deferred(), sent = deferred()
  await h.act('pull')
  assert.equal(h.calls.length, 0)
  h.remoteUid.value = 'linked-book'
  await h.preview('selected.json')
  await h.act('pull')
  assert.equal(h.posts('pull').length, 0)
  h.confirmed.value = true
  h.writer.flush = () => flush.promise
  h.transport.post = (url, body) => {
    assert.equal(url, '/novels/1/writing/cloud/pull')
    assert.deepEqual(body, { book: 'linked-book', file: 'selected.json' })
    sent.resolve()
    return response.promise
  }
  const pending = h.act('pull')
  await h.act('pull')
  flush.resolve(true)
  await sent.promise
  await Promise.all([h.act('pull'), h.act('push'), h.load()])
  assert.equal(h.posts('pull').length, 1)
  response.resolve({})
  await pending
  assert.deepEqual(h.calls.filter(call => ['writer.load', 'desk.load', 'select'].includes(call[0])), [
    ['writer.load', 1], ['desk.load', 1], ['select', 'chapter'],
  ])
  assert.equal(h.calls.find(call => call[0] === 'get')[2].params.book, 'linked-book')
  assert.equal(h.remote.value, null)
  assert.equal(h.confirmed.value, false)
  assert.equal(h.busy.value, false)
})

test('editing the remote identity during flush cancels the old consent without retargeting restore', async t => {
  const h = setup(t), gate = deferred()
  await h.preview('selected.json')
  h.confirmed.value = true
  h.writer.flush = () => gate.promise
  const pending = h.act('pull')
  h.remoteUid.value = 'new-input'
  await h.act('push')
  assert.equal(h.busy.value, true)
  gate.resolve(true)
  await pending
  assert.equal(h.posts('pull').length, 0)
  assert.equal(h.posts('push').length, 0)
  assert.equal(h.confirmed.value, false)
  assert.equal(h.busy.value, false)
})

test('mutation responses and save completions cannot refresh a different or remounted work', async t => {
  for (const mode of ['unmount', 'switch']) for (const phase of ['flush', 'post']) {
    await t.test(`${mode}: ${phase}`, async t => {
      const h = setup(t), gate = deferred(), started = deferred()
      await h.preview('selected.json')
      h.confirmed.value = true
      if (phase === 'flush') h.writer.flush = () => { started.resolve(); return gate.promise }
      else h.transport.post = () => { started.resolve(); return gate.promise }
      const pending = h.act('pull')
      await started.promise
      if (mode === 'unmount') h.dispose()
      else { h.writer.novelId = 2; h.writer.novelId = 1 }
      gate.resolve(true)
      await pending
      assert.equal(h.posts('pull').length, phase === 'flush' ? 0 : 1)
      assert.equal(h.calls.some(call => ['writer.load', 'desk.load', 'select', 'get'].includes(call[0])), false)
      assert.equal(h.writer.current.uid, 'chapter')
      assert.equal(h.error.value, '')
    })
  }
})

test('a failed restore retains its preview for retry and an unsaved manuscript never posts', async t => {
  const h = setup(t)
  await h.preview('selected.json')
  h.confirmed.value = true
  h.writer.flush = async () => false
  await h.act('pull')
  assert.equal(h.posts('pull').length, 0)
  assert.match(h.error.value, /请先保存正文/)
  h.writer.flush = async () => true
  h.transport.post = async () => { throw Error('connection failed') }
  await h.act('pull')
  assert.equal(h.posts('pull').length, 1)
  assert.ok(h.remote.value)
  assert.equal(h.confirmed.value, true)
  assert.equal(h.busy.value, false)
  assert.equal(h.error.value, 'connection failed')
  h.transport.post = async () => ({})
  await h.act('pull')
  assert.equal(h.posts('pull').length, 2)
  assert.equal(h.remote.value, null)
  assert.equal(h.error.value, '')
})

test('status refresh does not overlap itself, cloud operations, another work or unmount', async t => {
  const h = setup(t), gate = deferred()
  let refreshes = 0
  h.writer.refresh = () => { refreshes++; return gate.promise }
  h.mount()
  h.tick()
  assert.equal(refreshes, 1)
  gate.resolve()
  await gate.promise
  const reading = deferred()
  h.transport.get = () => reading.promise
  const pending = h.load()
  h.tick()
  assert.equal(refreshes, 1)
  reading.resolve([])
  await pending
  h.writer.novelId = 2
  h.tick()
  h.dispose()
  h.tick()
  assert.equal(refreshes, 1)
  assert.equal(h.cleared(), true)
})
