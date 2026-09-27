import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources()
const { ReaderChapterCache, ReaderContextExpiredError } = await load(
  'utils/readerChapterCache',
)

function chapter(uid, text = `正文：${uid}`) {
  return {
    uid,
    title: `章名：${uid}`,
    volumeId: null,
    position: 0,
    summary: '',
    status: 'draft',
    goal: 2000,
    numbered: true,
    deleted: false,
    revision: 0,
    wordCount: text.length,
    updatedAt: '2026-09-27T00:00:00Z',
    links: [],
    notes: '',
    doc: {
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          attrs: { id: `${uid}-paragraph` },
          content: [{ type: 'text', text }],
        },
      ],
    },
  }
}
function deferred() {
  let resolve, reject
  const promise = new Promise((done, fail) => {
    resolve = done
    reject = fail
  })
  return { promise, resolve, reject }
}
const prose = (value) => value.doc.content[0].content[0].text

test('concurrent loads are deduplicated per chapter and out-of-order responses retain their own prose', async () => {
  const a = deferred(),
    b = deferred(),
    calls = []
  const cache = new ReaderChapterCache({
    novelId: 7,
    bookUid: 'book-a',
    load: (novelId, uid) => {
      calls.push([novelId, uid])
      return uid === 'a' ? a.promise : b.promise
    },
  })
  const firstA = cache.get('a'),
    secondA = cache.get('a'),
    firstB = cache.get('b')
  assert.deepEqual(calls, [
    [7, 'a'],
    [7, 'b'],
  ])
  b.resolve(chapter('b', '第二章先抵达。'))
  assert.equal(prose(await firstB), '第二章先抵达。')
  a.resolve(chapter('a', '第一章仍在灯塔。'))
  assert.strictEqual(await firstA, await secondA)
  assert.equal(prose(await cache.get('a')), '第一章仍在灯塔。')
  assert.equal(prose(await cache.get('b')), '第二章先抵达。')
  assert.equal(calls.length, 2)
})

test('a changed novel or book identity rejects a late response before it enters the cache', async () => {
  let activeNovel = 7,
    activeBook = 'book-a'
  const response = deferred()
  let calls = 0
  const cache = new ReaderChapterCache({
    novelId: 7,
    bookUid: 'book-a',
    isActive: () => activeNovel === 7 && activeBook === 'book-a',
    load: () => {
      calls++
      return response.promise
    },
  })
  const pending = assert.rejects(
    cache.get('shared-uid'),
    ReaderContextExpiredError,
  )
  activeNovel = 8
  activeBook = 'book-b'
  response.resolve(chapter('shared-uid', '旧作品正文，不能流入新作品。'))
  await pending
  assert.equal(cache.chapters.size, 0)
  await assert.rejects(cache.get('new-chapter'), ReaderContextExpiredError)
  assert.equal(calls, 1)
})

test('disposal blocks late writes while another work can independently use the same chapter UID', async () => {
  const oldResponse = deferred()
  const old = new ReaderChapterCache({
    novelId: 7,
    bookUid: 'old-book',
    load: () => oldResponse.promise,
  })
  const fresh = new ReaderChapterCache({
    novelId: 8,
    bookUid: 'new-book',
    load: async () => chapter('same', '新作品的正文。'),
  })
  const expired = assert.rejects(old.get('same'), ReaderContextExpiredError)
  old.dispose()
  await fresh.get('same')
  oldResponse.resolve(chapter('same', '旧作品的正文。'))
  await expired
  assert.equal(old.chapters.size, 0)
  assert.equal(prose(fresh.chapters.get('same')), '新作品的正文。')
  await assert.rejects(old.get('same'), ReaderContextExpiredError)
})

test('a failed load can retry, but mismatched or deleted chapters are never cached', async () => {
  const answers = [
    Error('offline'),
    chapter('wrong'),
    { ...chapter('target'), deleted: true },
    chapter('target', '重试后正文完整。'),
  ]
  const cache = new ReaderChapterCache({
    novelId: 7,
    bookUid: 'book-a',
    load: async () => {
      const answer = answers.shift()
      if (answer instanceof Error) throw answer
      return answer
    },
  })
  await assert.rejects(cache.get('target'), /offline/)
  assert.equal(cache.chapters.size, 0)
  await assert.rejects(cache.get('target'), /请求不符/)
  assert.equal(cache.chapters.size, 0)
  await assert.rejects(cache.get('target'), /章节已删除/)
  assert.equal(cache.chapters.size, 0)
  assert.equal(prose(await cache.get('target')), '重试后正文完整。')
  assert.equal(answers.length, 0)
})

test('window trimming preserves visible chapters, retains recent access, and refetches evicted prose', async () => {
  const calls = []
  const cache = new ReaderChapterCache({
    novelId: 7,
    bookUid: 'book-a',
    limit: 3,
    load: async (_, uid) => {
      calls.push(uid)
      return chapter(uid)
    },
  })
  for (const uid of ['a', 'b', 'c', 'd']) await cache.get(uid)
  await cache.get('a')
  cache.trim(['b'])
  assert.deepEqual([...cache.chapters.keys()], ['b', 'd', 'a'])
  await cache.get('e')
  cache.trim(['b', 'd'])
  assert.deepEqual([...cache.chapters.keys()], ['b', 'd', 'e'])
  assert.equal(prose(await cache.get('c')), '正文：c')
  assert.equal(calls.filter((uid) => uid === 'c').length, 2)
})

test('cached chapters are also inaccessible once the current book context expires', async () => {
  let active = true
  const cache = new ReaderChapterCache({
    novelId: 7,
    bookUid: 'book-a',
    isActive: () => active,
    load: async (_, uid) => chapter(uid),
  })
  await cache.get('a')
  active = false
  await assert.rejects(cache.get('a'), ReaderContextExpiredError)
})
