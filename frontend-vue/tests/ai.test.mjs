import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'
const load = compileSources()
const { aiActions, aiCandidatePatch, aiDraftKey, aiDirectionNote, aiUndoPatch, aiCollectionMerge, aiFieldLabel } = await load('utils/ai')

test('every generation action applies exactly its declared field and review actions cannot apply fields', () => {
  for (const action of Object.values(aiActions).flat()) {
    if (action.field) assert.deepEqual(aiCandidatePatch(action.action, { fields: { [action.field]: '候选' } }), { field: action.field, value: '候选' })
    else assert.throws(() => aiCandidatePatch(action.action, { fields: { content: '绕过写入' } }))
  }
})
test('a name candidate cannot silently replace background, location, or manuscript', () => {
  for (const fields of [{ name: '云岑', background: '陌生经历' }, { doc: '正文' }, { name: 42 }, {}])
    assert.throws(() => aiCandidatePatch('character.name', { fields }))
})
test('candidate freshness includes entity and chapter identity even when displayed text is equal', () => {
  assert.notEqual(aiDraftKey({ name: '同名' }, 1), aiDraftKey({ name: '同名' }, 2))
  assert.notEqual(aiDraftKey({ notes: '' }, undefined, 'a'), aiDraftKey({ notes: '' }, undefined, 'b'))
  assert.notEqual(aiDraftKey({ name: '原名' }, 1), aiDraftKey({ name: '手动改名' }, 1))
})
test('saved inspiration preserves candidate status, assumptions, and provenance without manuscript fields', () => {
  const direction = { goal: '逼近一个决定', choice: '在承诺与身份之间选择', consequence: '其中一方承担代价', risk: '可能提前暴露秘密', question: '主角是否已经知情？', assumptions: ['信件尚未送达'] }
  const note = aiDirectionNote(direction, 'test-model', ['人物档案', '第一章'])
  assert.match(note, /尚未成为正式设定/)
  assert.match(note, /新增假设：信件尚未送达/)
  assert.match(note, /参考资料：人物档案、第一章/)
  assert.match(note, /模型：test-model/)
})

test('undo only reverses its own unchanged field and never overwrites later author work', () => {
  const change = { field: 'name', before: '林舟', applied: '沈望舒' }
  assert.deepEqual(aiUndoPatch(change, { name: '沈望舒' }), { field: 'name', value: '林舟' })
  assert.throws(() => aiUndoPatch(change, { name: '作者手改' }))
})

test('collection recovery merges only the intended suggestion and does not resurrect deleted ideas or old story state', () => {
  const intended = { uid: 'new', sourceKey: 'ai:r:0' }, deleted = { uid: 'deleted', sourceKey: 'ai:old:0' }
  const latest = { version: 5, ideas: [{ uid: 'author' }], storyState: { marker: 'new' } }
  const result = aiCollectionMerge(latest, { version: 3, ideas: [deleted, intended], storyState: { marker: 'old' } }, 'ai:r:0', 'mutation')
  assert.deepEqual(result.ideas.map(idea => idea.uid), ['author', 'new'])
  assert.equal(result.storyState.marker, 'new')
  assert.equal(result.version, 5)
  assert.equal(aiCollectionMerge(result, { ideas: [intended] }, 'ai:r:0', 'next').ideas.length, 2)
})

test('AI transport binds recipient revision, rejects cross-book late responses, and never retries generation', async () => {
  globalThis.localStorage = { setItem() {}, removeItem() {} }
  const { setCurrentNovelId } = await load('api/request')
  const { aiApi } = await load('api/ai')
  const original = globalThis.fetch
  const calls = []
  let release
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    if (url.endsWith('/session')) return new Response(JSON.stringify({ token: 'session-token' }), { status: 200 })
    await new Promise(resolve => { release = resolve })
    const body = JSON.parse(options.body)
    return new Response(JSON.stringify({ requestId: body.requestId, action: body.action }), { status: 200 })
  }
  try {
    setCurrentNovelId(1)
    const request = aiApi.suggest(1, 'character.name', { targetId: 5 }, 'request-id', new AbortController().signal, 'recipient-version')
    while (!release) await new Promise(resolve => setImmediate(resolve))
    setCurrentNovelId(2); setCurrentNovelId(1)
    release()
    await assert.rejects(request, error => error.name === 'AbortError')
    const sent = calls.filter(call => call.url.includes('/suggestions'))
    assert.equal(sent.length, 1)
    assert.equal(JSON.parse(sent[0].options.body).expectedConfigRevision, 'recipient-version')
    assert.equal(sent[0].options.headers['X-AI-Session'], 'session-token')
  } finally { globalThis.fetch = original }
})

test('a configuration GET renews an expired local session once; paid requests are never replayed', async () => {
  const { aiApi } = await load('api/ai')
  const original = globalThis.fetch
  let reads = 0, paid = 0, sessions = 0
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/session')) { sessions++; return Response.json({ token: `renewed-${sessions}` }) }
    if (url.endsWith('/config')) {
      reads++
      if (reads === 1) return Response.json({ message: '会话已失效' }, { status: 401 })
      assert.match(options.headers['X-AI-Session'], /^renewed-/)
      return Response.json({ configured: true, revision: 'new' })
    }
    paid++; return Response.json({ message: '会话已失效' }, { status: 401 })
  }
  try {
    assert.equal((await aiApi.config()).revision, 'new')
    assert.equal(reads, 2)
    await assert.rejects(aiApi.suggest(1, 'character.name', {}, 'one-request', new AbortController().signal, 'new'), /会话已失效/)
    assert.equal(paid, 1)
    await assert.rejects(aiApi.test('new'), /会话已失效/)
    assert.equal(paid, 2)
  } finally { globalThis.fetch = original }
})

test('configuration recovery is bounded even when fresh sessions are rejected', async () => {
  const { aiApi } = await load('api/ai')
  const original = globalThis.fetch
  let reads = 0
  globalThis.fetch = async url => url.endsWith('/session') ? Response.json({ token: 'still-invalid' }) : (reads++, Response.json({ message: '连接失效' }, { status: 401 }))
  try { await assert.rejects(aiApi.config(), /连接失效/); assert.equal(reads, 2) }
  finally { globalThis.fetch = original }
})

test('freshness checks reject non-boolean approval and late responses after a book switch', async () => {
  const { aiApi } = await load('api/ai'), { setCurrentNovelId } = await load('api/request')
  const original = globalThis.fetch
  let release
  globalThis.fetch = async url => url.endsWith('/session') ? Response.json({ token: 'valid' }) : Response.json({ current: 'false' })
  try {
    await assert.rejects(aiApi.contextCurrent(1, 'character.name', {}, 'snapshot'), /核对结果无效/)
    setCurrentNovelId(1)
    globalThis.fetch = async () => { await new Promise(resolve => { release = resolve }); return Response.json({ current: true }) }
    const result = aiApi.contextCurrent(1, 'character.name', {}, 'snapshot')
    while (!release) await new Promise(resolve => setImmediate(resolve))
    setCurrentNovelId(2); setCurrentNovelId(1); release()
    await assert.rejects(result, error => error.name === 'AbortError')
  } finally { globalThis.fetch = original }
})

test('adoption labels name the destination field rather than the brainstorming action', () => {
  assert.equal(aiFieldLabel('character.name'), '姓名')
  assert.equal(aiFieldLabel('character.personality'), '性格')
  assert.equal(aiFieldLabel('character.background'), '背景')
  assert.equal(aiFieldLabel('location.description'), '描述')
})

test('reference chapter metadata uses the AI session and safely renews one expired read without invoking the model', async () => {
  const { aiApi } = await load('api/ai'), original = globalThis.fetch
  const response = { maxChars: 10000, chapters: [{ uid: 'chapter-a', title: '已保存的第一章', wordCount: 3000 }] }
  let reads = 0, sessionReads = 0
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/session')) { sessionReads++; return Response.json({ token: 'chapter-session' }) }
    assert.equal(url.endsWith('/novels/1/ai/reference-chapters'), true)
    assert.equal(options.method, 'GET'); assert.equal(options.body, undefined); assert.equal(options.cache, 'no-store')
    assert.equal(typeof options.headers['X-AI-Session'], 'string')
    reads++
    if (reads === 1) return Response.json({ message: '会话已失效' }, { status: 401 })
    assert.equal(options.headers['X-AI-Session'], 'chapter-session')
    return Response.json(response)
  }
  try {
    assert.deepEqual(await aiApi.referenceChapters(1), response)
    assert.equal(reads, 2); assert.equal(sessionReads, 1)
  } finally { globalThis.fetch = original }
})

test('reference chapter metadata rejects malformed counts, duplicate chapters, and changed limits', async () => {
  const { aiApi } = await load('api/ai'), original = globalThis.fetch
  const chapter = { uid: 'chapter-a', title: '第一章', wordCount: 1 }
  try {
    for (const invalid of [null, {}, { maxChars: 20000, chapters: [chapter] }, { maxChars: 10000, chapters: [chapter, chapter] }, { maxChars: 10000, chapters: [{ ...chapter, wordCount: -1 }] }, { maxChars: 10000, chapters: [{ ...chapter, wordCount: '3000' }] }, { maxChars: 10000, chapters: [{ ...chapter, title: null }] }]) {
      globalThis.fetch = async url => url.endsWith('/session') ? Response.json({ token: 'metadata' }) : Response.json(invalid)
      await assert.rejects(aiApi.referenceChapters(1), /正文章节列表无效/)
    }
  } finally { globalThis.fetch = original }
})

test('reference metadata rejects late cross-book responses and aborted reads', async () => {
  const { aiApi } = await load('api/ai'), { setCurrentNovelId } = await load('api/request'), original = globalThis.fetch
  try {
    for (const abort of [false, true]) {
      let release
      const controller = new AbortController()
      setCurrentNovelId(1)
      globalThis.fetch = async () => { await new Promise(resolve => { release = resolve }); return Response.json({ maxChars: 10000, chapters: [] }) }
      const reading = aiApi.referenceChapters(1, controller.signal)
      while (!release) await new Promise(resolve => setImmediate(resolve))
      if (abort) controller.abort()
      else { setCurrentNovelId(2); setCurrentNovelId(1) }
      release(); await assert.rejects(reading, error => error.name === 'AbortError')
    }
  } finally { globalThis.fetch = original }
})
