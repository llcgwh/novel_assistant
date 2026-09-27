import test from 'node:test'
import assert from 'node:assert/strict'
import { createPinia, setActivePinia } from 'pinia'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources()
const { writingDeskApi: api } = await load('api/writingDesk')
const { useWritingDeskStore } = await load('stores/writingDesk')
const clone = (value) => JSON.parse(JSON.stringify(value))
const empty = () => ({ version: 0, nextPen: null, tasks: [], bookmarks: [] })
const nextPen = (scene = '去灯塔找那封信') => ({
  chapterUid: 'chapter-a',
  blockId: 'p2',
  excerpt: '她推开门',
  nextScene: scene,
  question: '',
  opening: '',
  updatedAt: '2026-09-27T01:00:00Z',
})
const task = () => ({
  uid: 'task-a',
  chapterUid: 'chapter-a',
  blockId: 'p1',
  excerpt: '原句',
  body: '缩短这段对白',
  category: 'dialogue',
  priority: 'normal',
  status: 'open',
  createdAt: 'now',
  updatedAt: 'now',
})
const bookmark = () => ({
  uid: 'bookmark-a',
  chapterUid: 'chapter-a',
  blockId: 'p3',
  excerpt: '信封',
  label: '下次读这里',
  createdAt: 'now',
})
function setup() {
  setActivePinia(createPinia())
  let server = empty()
  const writes = []
  api.get = async () => clone(server)
  api.save = async (id, payload) => {
    writes.push(clone(payload))
    assert.equal(payload.version, server.version)
    server = { ...clone(payload), version: server.version + 1 }
    return clone(server)
  }
  return {
    store: useWritingDeskStore(),
    writes,
    get server() {
      return server
    },
    set server(value) {
      server = value
    },
  }
}

test('independent desk edits preserve notes, tasks, bookmarks and the accepted revision', async () => {
  const ctx = setup(),
    store = ctx.store
  await store.load(1)
  await store.saveTask(task())
  await store.saveBookmark(bookmark())
  await store.saveNextPen(nextPen())
  assert.equal(store.data.version, 3)
  assert.equal(store.data.tasks[0].body, '缩短这段对白')
  assert.equal(store.data.bookmarks[0].blockId, 'p3')
  await store.saveTask({ ...store.data.tasks[0], status: 'done' })
  assert.equal(store.data.tasks.length, 1)
  await store.deleteBookmark('bookmark-a')
  await store.deleteTask('task-a')
  assert.deepEqual(store.data.tasks, [])
  assert.deepEqual(store.data.bookmarks, [])
  assert.equal(store.data.nextPen.nextScene, '去灯塔找那封信')
})

test('lost save response retries the exact mutation even when a form refreshes its timestamp', async () => {
  const { store } = setup()
  await store.load(1)
  const requests = []
  api.save = async (_, payload) => {
    requests.push(clone(payload))
    if (requests.length === 1) throw Error('response lost')
    return { ...clone(payload), version: 1 }
  }
  await assert.rejects(store.saveNextPen(nextPen()), /response lost/)
  assert.equal(store.data.version, 0)
  await store.saveNextPen({ ...nextPen(), updatedAt: 'later' })
  assert.deepEqual(requests[0], requests[1])
  assert.equal(store.data.version, 1)
})

test('an uncertain save cannot be replaced by another edit until the server is reloaded', async () => {
  const { store } = setup()
  await store.load(1)
  let calls = 0
  api.save = async () => {
    calls++
    throw Error('offline')
  }
  await assert.rejects(store.saveNextPen(nextPen()), /offline/)
  await assert.rejects(store.saveNextPen(nextPen('不同的下一幕')), /重新载入/)
  assert.equal(calls, 1)
  assert.equal(store.data.nextPen, null)
  await store.reload()
  await assert.rejects(store.saveNextPen(nextPen('不同的下一幕')), /offline/)
  assert.equal(calls, 2)
})

test('conflicts retain the local baseline until an explicit reload and never overwrite automatically', async () => {
  const ctx = setup(),
    store = ctx.store
  await store.load(1)
  api.save = async () => {
    throw { response: { status: 409 } }
  }
  await assert.rejects(store.saveNextPen(nextPen()))
  assert.match(store.error, /另一处更新/)
  assert.equal(store.data.nextPen, null)
  ctx.server = { ...empty(), version: 4, nextPen: nextPen('另一台设备的计划') }
  await store.reload()
  assert.equal(store.data.version, 4)
  assert.equal(store.data.nextPen.nextScene, '另一台设备的计划')
})

test('late reads cannot replace another novel or end its loading state', async () => {
  const { store } = setup()
  let finishOld
  api.get = (id) =>
    id === 1
      ? new Promise((resolve) => {
          finishOld = resolve
        })
      : Promise.resolve({ ...empty(), version: 9 })
  const old = store.load(1)
  const rejected = assert.rejects(old, /作品已切换/)
  await store.load(2)
  finishOld({ ...empty(), nextPen: nextPen() })
  await rejected
  assert.equal(store.novelId, 2)
  assert.equal(store.data.version, 9)
  assert.equal(store.data.nextPen, null)
  assert.equal(store.loading, false)
  assert.equal(store.error, '')
})

test('late saves cannot populate a switched novel and cannot clear its pending save', async () => {
  const { store } = setup()
  await store.load(1)
  let finishOld, finishNew
  api.save = (id, payload) =>
    new Promise((resolve) => {
      const finish = () => resolve({ ...clone(payload), version: 1 })
      if (id === 1) finishOld = finish
      else finishNew = finish
    })
  const old = store.saveNextPen(nextPen('第一本'))
  const rejected = assert.rejects(old, /作品已切换/)
  await store.load(2)
  const fresh = store.saveNextPen(nextPen('第二本'))
  finishOld()
  await rejected
  assert.equal(store.saving, true)
  assert.equal(store.data.nextPen, null)
  finishNew()
  await fresh
  assert.equal(store.data.nextPen.nextScene, '第二本')
  assert.equal(store.saving, false)
})

test('read and save operations cannot race within the same workspace', async () => {
  const { store } = setup()
  await store.load(1)
  let finish
  api.save = (_, payload) =>
    new Promise((resolve) => {
      finish = () => resolve({ ...clone(payload), version: 1 })
    })
  const saving = store.saveNextPen(nextPen())
  await assert.rejects(store.reload(), /等待当前/)
  await assert.rejects(store.saveBookmark(bookmark()), /等待/)
  finish()
  await saving
  assert.equal(store.data.version, 1)
  assert.deepEqual(store.data.bookmarks, [])
})
