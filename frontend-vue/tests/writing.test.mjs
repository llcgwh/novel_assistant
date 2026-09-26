import test from 'node:test'
import assert from 'node:assert/strict'
import 'fake-indexeddb/auto'
import { createPinia, setActivePinia } from 'pinia'
import { compileSources } from './helpers/load-source.mjs'
const load = compileSources()
const {
  wordCount,
  plainDocument,
  documentText,
  WritingMeter,
  parseManuscript,
} = await load('utils/writing')
function storage() {
  const map = new Map()
  return {
    getItem: (k) => map.get(k) ?? null,
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: (k) => map.delete(k),
  }
}
globalThis.localStorage = storage()
globalThis.sessionStorage = storage()
const { request, setCurrentNovelId } = await load('api/request')
const { useWritingStore } = await load('stores/writing')
const { listDrafts } = await load('utils/writingDrafts')
const clone = (v) => JSON.parse(JSON.stringify(v))
function setup() {
  setActivePinia(createPinia())
  setCurrentNovelId(1)
  const uid = crypto.randomUUID(),
    book = crypto.randomUUID()
  let server = {
    uid,
    title: '第一章',
    doc: plainDocument('原稿'),
    links: [],
    notes: '',
    summary: '',
    volumeId: null,
    goal: 2000,
    numbered: true,
    deleted: false,
    status: 'draft',
    position: 0,
    revision: 0,
    wordCount: 2,
    updatedAt: '',
  }
  const writes = []
  const requests = []
  const workspace = () => ({
    uid: book,
    structureVersion: 0,
    volumes: [],
    chapters: [clone(server)],
    preferences: {},
    sessions: [],
    changeSequence: 0,
    syncedSequence: 0,
  })
  let intercept
  request.defaults.adapter = async (config) => {
    requests.push({ method: config.method, url: config.url })
    let data
    const url = config.url
    if (config.method === 'get') {
      if (url.endsWith('/writing')) data = workspace()
      else if (url.includes('/chapters/')) data = server
      else data = []
    } else {
      const body = JSON.parse(config.data)
      delete body.checkpoint
      writes.push(clone(body))
      if (intercept) {
        const result = await intercept(body, config)
        if (result)
          return {
            data: result,
            status: 200,
            statusText: 'OK',
            headers: {},
            config,
          }
      }
      if (body.mutationId === server.mutationId) data = server
      else {
        assert.equal(body.revision, server.revision)
        server = { ...body, revision: server.revision + 1 }
        data = server
      }
    }
    return {
      data: clone(data),
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    }
  }
  const store = useWritingStore()
  return {
    store,
    uid,
    book,
    writes,
    requests,
    workspace,
    get server() {
      return server
    },
    set server(v) {
      server = v
    },
    set intercept(v) {
      intercept = v
    },
  }
}
test('Chinese, mixed scripts, Latin words and punctuation use the same documented counting rules', () => {
  assert.equal(wordCount('沈雾推开门。Hello world 2026'), 8)
  assert.equal(wordCount('Hi沈雾 hello世界'), 6)
  assert.equal(wordCount(' don’t 12.3\n，。✦'), 3)
  assert.equal(documentText(plainDocument('一行\n另一行')), '一行\n另一行\n')
})
test('typing, paste, undo and idle time do not inflate writing speed', () => {
  const m = new WritingMeter('chapter', '', 100000)
  m.update('', '你', 'typed', 101000)
  m.update('你', '你好', 'typed', 102000)
  m.update('你好', '你好世界', 'paste', 103000)
  m.update('你好世界', '你好', 'history', 104000)
  assert.equal(m.record.typed, 2)
  assert.equal(m.record.pasted, 2)
  assert.equal(m.record.net, 2)
  assert.equal(m.rate(104000), 2)
  assert.equal(m.record.peak, 0)
  m.tick(160000)
  assert.equal(m.record.peak, 2)
  m.tick(200000)
  assert.equal(m.rate(200000), 0)
  assert.ok(m.record.activeSeconds < 60)
})
test('Latin letter continuation counts a word once and discarded IME candidates need no updates', () => {
  const m = new WritingMeter('chapter', '', 100000)
  let before = ''
  for (const next of ['H', 'He', 'Hel', 'Hell', 'Hello']) {
    m.update(before, next, 'typed', 101000)
    before = next
  }
  assert.equal(m.record.typed, 1)
  m.update(before, 'Hello 沈雾', 'typed', 102000)
  assert.equal(m.record.typed, 3)
})
test('restoring another version preserves session net and idle ticks stop creating sync changes', () => {
  const m = new WritingMeter('chapter', '原稿', 100000)
  m.update('原稿', '原稿加字', 'typed', 101000)
  m.update('更长的恢复文稿', '更长的恢复文稿', 'restore', 102000)
  m.update('更长的恢复文稿', '更长的恢复文稿续', 'typed', 103000)
  assert.equal(m.record.net, 3)
  assert.equal(m.record.typed, 3)
  m.tick(200000)
  const sequence = m.record.sequence
  m.tick(201000)
  assert.equal(m.record.sequence, sequence)
})
test('midnight creates a separate daily session without counting existing prose again', () => {
  const start = new Date(2026, 8, 26, 23, 59, 58).getTime(),
    m = new WritingMeter('chapter', '原稿', start)
  m.update('原稿', '原稿续', 'typed', start + 1000)
  const old = m.rollover(start + 3000)
  assert.equal(old.net, 1)
  assert.notEqual(old.uid, m.record.uid)
  assert.equal(m.record.date, '2026-09-27')
  m.update('原稿续', '原稿续写', 'typed', start + 4000)
  assert.equal(m.record.net, 1)
  assert.equal(m.record.typed, 1)
})
test('old manuscripts split into previewable chapters without deleting introductions', () => {
  const rows = parseManuscript(
    '开场白\r\n第一章 风\r\n风来了。\r\n第二章 雨\r\n雨停了。',
    '旧稿.txt',
  )
  assert.deepEqual(
    rows.map((c) => c.title),
    ['旧稿', '第一章 风', '第二章 雨'],
  )
  assert.match(documentText(rows[2].doc), /雨停了/)
  assert.equal(parseManuscript('# 开端\n故事\n# 后来\n后来', '稿.md').length, 2)
})
test('editing during an in-flight save sends the newer draft after the first acknowledgement', async () => {
  const h = setup()
  try {
    await h.store.load(1)
    await h.store.select(h.uid)
    let release
    const gate = new Promise((r) => (release = r))
    let started
    const begin = new Promise((r) => (started = r))
    let first = true
    h.intercept = async () => {
      if (first) {
        first = false
        started()
        await gate
      }
    }
    h.store.current.doc = plainDocument('第一份修改')
    h.store.changed()
    const save = h.store.flush()
    await begin
    h.store.current.doc = plainDocument('第一份修改之后继续写')
    h.store.changed()
    release()
    assert.equal(await save, true)
    assert.equal(h.writes.length, 2)
    assert.equal(h.server.revision, 2)
    assert.match(documentText(h.server.doc), /继续写/)
    assert.equal(h.store.dirty, false)
    assert.equal((await listDrafts(h.book)).length, 0)
  } finally {
    h.store.$reset()
  }
})
test('a lost acknowledgement retries the same mutation and preserves subsequent edits', async () => {
  const h = setup()
  try {
    await h.store.load(1)
    await h.store.select(h.uid)
    let first = true
    h.intercept = async (body) => {
      if (first) {
        first = false
        h.server = { ...body, revision: 1 }
        throw Error('simulated lost acknowledgement')
      }
    }
    h.store.current.doc = plainDocument('已写入服务器')
    h.store.changed()
    assert.equal(await h.store.flush(), false)
    const token = h.writes[0].mutationId
    h.store.current.doc = plainDocument('已写入服务器，继续写')
    h.store.changed()
    assert.equal(await h.store.flush(), true)
    assert.equal(h.writes[1].mutationId, token)
    assert.equal(h.server.revision, 2)
    assert.match(documentText(h.server.doc), /继续写/)
  } finally {
    h.store.$reset()
  }
})
test('unsaved drafts survive a store reset and are offered before any server overwrite', async () => {
  const h = setup()
  try {
    await h.store.load(1)
    await h.store.select(h.uid)
    h.store.current.doc = plainDocument('还未上传的草稿')
    h.store.changed()
    await h.store.persistLocal()
    h.store.$reset()
    await h.store.load(1)
    await h.store.select(h.uid)
    assert.ok(h.store.recovery)
    assert.match(documentText(h.store.recovery.chapter.doc), /未上传/)
    assert.equal(h.writes.length, 0)
    await h.store.recover(true)
    assert.equal(await h.store.flush(), true)
    assert.match(documentText(h.server.doc), /未上传/)
  } finally {
    h.store.$reset()
  }
})
test('manual checkpoint remains available after automatic saving', async () => {
  const h = setup()
  try {
    await h.store.load(1)
    await h.store.select(h.uid)
    assert.equal(h.store.dirty, false)
    assert.equal(await h.store.flush(true), true)
    assert.equal(h.writes.length, 1)
  } finally {
    h.store.$reset()
  }
})
test('local recovery never crosses a local work even if it shares a cloud identity', async () => {
  const h = setup()
  try {
    await h.store.load(1)
    await h.store.select(h.uid)
    h.store.current.doc = plainDocument('第一部作品的本机草稿')
    h.store.changed()
    await h.store.persistLocal()
    h.store.$reset()
    await h.store.load(2)
    await h.store.select(h.uid)
    assert.equal(h.store.recovery, null)
  } finally {
    h.store.$reset()
  }
})

test('persisting or checkpointing before a recovery decision preserves the original draft', async () => {
  const h = setup()
  try {
    await h.store.load(1)
    await h.store.select(h.uid)
    h.store.current.doc = plainDocument('尚未提交且必须保留的草稿')
    h.store.changed()
    await h.store.persistLocal()
    h.store.$reset()
    await h.store.load(1)
    await h.store.select(h.uid)
    const recovered = clone(h.store.recovery)
    assert.match(documentText(h.store.current.doc), /原稿/)
    await h.store.persistLocal() // beforeunload, including a cancelled departure
    assert.equal(await h.store.flush(), false)
    assert.equal(await h.store.flush(true), false)
    assert.deepEqual((await listDrafts(h.book, 1))[0], recovered)
    assert.equal(h.writes.length, 0)
    h.store.$reset()
    await h.store.load(1)
    await h.store.select(h.uid)
    assert.match(documentText(h.store.recovery.chapter.doc), /必须保留/)
  } finally {
    h.store.$reset()
  }
})

test('a structure response cannot attach a remote revision to stale local prose', async () => {
  const h = setup()
  try {
    await h.store.load(1)
    await h.store.select(h.uid)
    // Another tab saved prose without changing the book structure version.
    h.server = {
      ...h.server,
      doc: plainDocument('另一标签页的新稿'),
      revision: 1,
    }
    h.intercept = async (_body, config) => {
      if (config.url.endsWith('/structure'))
        return { ...h.workspace(), structureVersion: 1 }
    }
    await h.store.structure()
    assert.equal(h.store.current.revision, 0)
    assert.match(documentText(h.store.current.doc), /原稿/)
    assert.match(documentText(h.store.conflict.doc), /另一标签页/)
    h.store.current.doc = plainDocument('旧稿上的新输入')
    h.store.changed()
    assert.equal(await h.store.flush(), false)
    assert.equal(h.writes.length, 1)
    assert.match(documentText(h.server.doc), /另一标签页/)
  } finally {
    h.store.$reset()
  }
})

test('structure changes update revisions and retain prose typed while awaiting the response', async () => {
  const h = setup()
  try {
    await h.store.load(1)
    await h.store.select(h.uid)
    let release, started
    const gate = new Promise((resolve) => (release = resolve))
    const begin = new Promise((resolve) => (started = resolve))
    const volumeId = crypto.randomUUID()
    h.intercept = async (_body, config) => {
      if (config.url.endsWith('/structure')) {
        started()
        await gate
        h.server = { ...h.server, volumeId, revision: 1 }
        return { ...h.workspace(), structureVersion: 1 }
      }
    }
    const move = h.store.structure(
      [{ uid: volumeId, title: '第一卷' }],
      [{ ...h.store.workspace.chapters[0], volumeId }],
    )
    await begin
    h.store.current.doc = plainDocument('等待排序时继续写下的内容')
    h.store.changed()
    release()
    await move
    assert.equal(h.store.current.revision, 1)
    assert.equal(h.store.current.volumeId, volumeId)
    assert.equal(h.store.dirty, true)
    assert.equal(h.store.conflict, null)
    assert.equal(await h.store.flush(), true)
    assert.match(documentText(h.server.doc), /继续写下/)
  } finally {
    h.store.$reset()
  }
})

test('a context switch during local persistence prevents a chapter write to the old work', async () => {
  const h = setup()
  try {
    await h.store.load(1)
    await h.store.select(h.uid)
    h.store.current.doc = plainDocument('第一部作品的未提交稿')
    h.store.changed()
    const pending = h.store.flush()
    h.store.$reset()
    await h.store.load(2)
    await h.store.select(h.uid)
    assert.equal(await pending, false)
    assert.equal(h.writes.length, 0)
    assert.equal(h.store.novelId, 2)
    assert.match(documentText(h.store.current.doc), /原稿/)
    assert.match(
      documentText((await listDrafts(h.book, 1))[0].chapter.doc),
      /第一部作品/,
    )
  } finally {
    h.store.$reset()
  }
})

test('typing while a saved draft is being removed remains recoverable if the next upload fails', async () => {
  const h = setup()
  const originalDelete = IDBObjectStore.prototype.delete
  try {
    await h.store.load(1)
    await h.store.select(h.uid)
    let edited = false
    IDBObjectStore.prototype.delete = function (key) {
      const result = originalDelete.call(this, key)
      if (!edited && String(key).includes(h.uid)) {
        edited = true
        h.store.current.doc = plainDocument('删除已存草稿时的新输入')
        h.store.changed()
      }
      return result
    }
    h.intercept = async () => {
      if (edited) throw Error('simulated offline after first save')
    }
    h.store.current.doc = plainDocument('第一份已存正文')
    h.store.changed()
    assert.equal(await h.store.flush(), false)
    assert.equal(edited, true)
    assert.match(documentText(h.server.doc), /第一份已存/)
    assert.match(
      documentText((await listDrafts(h.book, 1))[0].chapter.doc),
      /新输入/,
    )
    assert.equal(h.store.dirty, true)
    assert.equal(h.store.localSafe, true)
  } finally {
    IDBObjectStore.prototype.delete = originalDelete
    h.store.$reset()
  }
})

test('a late conflict-copy response cannot replace the current chapter in another work', async () => {
  const h = setup()
  try {
    await h.store.load(1)
    await h.store.select(h.uid)
    h.store.conflict = { ...clone(h.server), revision: 1 }
    let release, started
    const gate = new Promise((resolve) => (release = resolve))
    const begin = new Promise((resolve) => (started = resolve))
    h.intercept = async (body) => {
      started()
      await gate
      return { ...h.server, ...body }
    }
    const pending = h.store.resolveConflict('copy')
    const rejected = assert.rejects(pending, /作品已切换/)
    await begin
    h.store.$reset()
    await h.store.load(2)
    await h.store.select(h.uid)
    const count = h.requests.length
    release()
    await rejected
    assert.equal(h.requests.length, count)
    assert.equal(h.store.novelId, 2)
    assert.equal(h.store.current.revision, 0)
    assert.equal(h.store.conflict, null)
  } finally {
    h.store.$reset()
  }
})

for (const operation of ['create', 'structure']) {
  test(`${operation} stops after its initial flush if the work changes`, async () => {
    const h = setup()
    try {
      await h.store.load(1)
      await h.store.select(h.uid)
      const pending =
        operation === 'create' ? h.store.create('新章节') : h.store.structure()
      const rejected = assert.rejects(pending, /作品已切换/)
      h.store.$reset()
      await h.store.load(2)
      await h.store.select(h.uid)
      await rejected
      assert.equal(h.writes.length, 0)
      assert.equal(h.store.novelId, 2)
    } finally {
      h.store.$reset()
    }
  })
}

for (const operation of ['create', 'structure', 'preferences']) {
  test(`a late ${operation} response cannot mutate or continue requests in another work`, async () => {
    const h = setup()
    try {
      await h.store.load(1)
      await h.store.select(h.uid)
      let release, started
      const gate = new Promise((resolve) => (release = resolve))
      const begin = new Promise((resolve) => (started = resolve))
      h.intercept = async (body) => {
        started()
        await gate
        return operation === 'create'
          ? { ...h.server, ...body }
          : { ...h.workspace(), preferences: { dailyGoal: 123 } }
      }
      const pending =
        operation === 'create'
          ? h.store.create('旧作品的新章节')
          : operation === 'structure'
            ? h.store.structure()
            : h.store.preferences({ dailyGoal: 123 })
      const rejected = assert.rejects(pending, /作品已切换/)
      await begin
      // Deliberately keep Axios context unchanged: the store must be safe by
      // itself, including reload/reset operations within the same route.
      h.store.$reset()
      await h.store.load(2)
      await h.store.select(h.uid)
      const nextWorkspace = clone(h.store.workspace)
      const count = h.requests.length
      release()
      await rejected
      assert.equal(h.requests.length, count)
      assert.equal(h.store.novelId, 2)
      assert.deepEqual(clone(h.store.workspace), nextWorkspace)
      assert.equal(h.store.current.uid, h.uid)
    } finally {
      h.store.$reset()
    }
  })
}
