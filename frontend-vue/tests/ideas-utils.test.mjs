import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources()
const {
  IDEA_DRAG_MIME,
  appendIdeaToNotes,
  createLegacyIdeas,
  ideaCategories,
  ideaCategoryLabel,
  ideaSignature,
  markdownIdeas,
  readIdeaDrag,
} = await load('utils/ideas')

const idea = (changes = {}) => ({
  uid: 'idea-a',
  title: '灯塔里的信',
  body: '  她推开门。\r\n\r\n灯还亮着。  \n',
  category: 'scene',
  chapterUids: ['chapter-a'],
  createdAt: '2026-10-04T01:00:00.000Z',
  updatedAt: '2026-10-04T01:00:00.000Z',
  ...changes,
})

test('idea signatures ignore refreshed update timestamps but detect every saved content field', () => {
  const original = idea({ sourceKey: 'source-a' })
  const signature = ideaSignature(original)
  assert.equal(signature, ideaSignature({ ...original, updatedAt: 'later' }))
  for (const changes of [
    { uid: 'idea-b' },
    { title: '另一封信' },
    { body: '改变原文' },
    { category: 'plot' },
    { chapterUids: ['chapter-a', 'chapter-b'] },
    { createdAt: 'another-created-time' },
    { sourceKey: 'source-b' },
  ]) {
    assert.notEqual(signature, ideaSignature({ ...original, ...changes }))
  }
  assert.equal(ideaSignature(null), '')
  assert.equal(ideaSignature(undefined), '')
})

test('Markdown export retains whole bodies and names each available or missing chapter', () => {
  const original = idea({ chapterUids: ['chapter-a', 'removed'] })
  const text = markdownIdeas(
    [original, idea({ uid: 'second', title: '', body: '第二张完整正文', category: 'other', chapterUids: [] })],
    [{ uid: 'chapter-a', title: '第一章 · 潮声' }],
    '作品的灵感',
  )
  assert.ok(text.startsWith('# 作品的灵感\n\n'))
  assert.ok(text.includes('## 灯塔里的信'))
  assert.ok(text.includes('分类：场景'))
  assert.ok(text.includes('第一章 · 潮声、不可用章节（removed）'))
  assert.ok(text.includes(original.body))
  assert.ok(text.includes('## 未命名灵感'))
  assert.ok(text.includes('关联章节：未关联章节'))
  assert.ok(text.includes('第二张完整正文'))
  assert.deepEqual(original.chapterUids, ['chapter-a', 'removed'])
  assert.equal(markdownIdeas([], []), '# 灵感卡片\n')
})

test('all supported categories have labels and unknown older values export as other', () => {
  assert.deepEqual(ideaCategories.map(({ value }) => value), [
    'other', 'plot', 'character', 'setting', 'dialogue', 'scene',
  ])
  for (const category of ideaCategories) {
    assert.equal(ideaCategoryLabel(category.value), category.label)
  }
  assert.equal(ideaCategoryLabel('unknown'), '其他')
})

test('legacy imports are deterministic across retries and scoped to the novel and full source text', () => {
  const original = '  旧便笺\r\n 保留换行与空格。\n'
  const first = createLegacyIdeas(original, 1, '2026-10-04T01:00:00.000Z')
  const retry = createLegacyIdeas(original, 1, '2026-10-04T02:00:00.000Z')
  assert.equal(first.length, 1)
  assert.equal(first[0].body, original)
  assert.equal(first[0].uid, retry[0].uid)
  assert.equal(first[0].sourceKey, retry[0].sourceKey)
  assert.notEqual(first[0].createdAt, retry[0].createdAt)
  assert.deepEqual(first[0].chapterUids, [])
  assert.equal(first[0].category, 'other')
  assert.equal(first[0].createdAt, first[0].updatedAt)
  assert.notEqual(first[0].sourceKey, createLegacyIdeas(original, 2)[0].sourceKey)
  assert.notEqual(first[0].uid, createLegacyIdeas(original, 2)[0].uid)
  assert.notEqual(first[0].sourceKey, createLegacyIdeas(original + ' ', 1)[0].sourceKey)
  const automaticDate = createLegacyIdeas(original, 1)[0].createdAt
  assert.equal(new Date(automaticDate).toISOString(), automaticDate)
  assert.deepEqual(createLegacyIdeas('', 1), [])
  assert.equal(createLegacyIdeas(' \t\n', 1)[0].body, ' \t\n')
})

test('oversized legacy notes split without losing code units, newlines, or emoji at boundaries', () => {
  const original = '甲'.repeat(19999) + '🌙' + '\r\n' + '乙'.repeat(20000) + '\n尾声  '
  const chunks = createLegacyIdeas(original, 7)
  assert.equal(chunks.length, 3)
  assert.equal(chunks.map(({ body }) => body).join(''), original)
  assert.equal(new Set(chunks.map(({ uid }) => uid)).size, chunks.length)
  assert.equal(new Set(chunks.map(({ sourceKey }) => sourceKey)).size, chunks.length)
  for (const chunk of chunks) {
    assert.ok(chunk.body.length <= 20000)
    assert.ok(chunk.body.length > 0)
    assert.ok(chunk.uid.length <= 100)
    assert.ok(chunk.sourceKey.length <= 200)
    assert.ok(!/[\ud800-\udbff]$/.test(chunk.body))
    assert.ok(!/^[\udc00-\udfff]/.test(chunk.body))
  }
  assert.equal(createLegacyIdeas('甲'.repeat(20000), 7).length, 1)
  assert.equal(createLegacyIdeas('甲'.repeat(20001), 7).length, 2)
  const changedLastCharacter = createLegacyIdeas(original + '！', 7)
  assert.notEqual(changedLastCharacter[0].sourceKey, chunks[0].sourceKey)
})

test('appending an idea uses a readable source heading, repeats no identical block, and retains edited versions', () => {
  const notes = '已保存的章节备注\r\n末尾空格  '
  const original = idea()
  const next = appendIdeaToNotes(notes, original)
  assert.ok(next.startsWith(notes + '\n\n'))
  assert.ok(next.includes('## 灵感：' + original.title))
  assert.ok(next.endsWith(original.body))
  assert.doesNotMatch(next, /<!-- novel-assistant:idea:/)
  assert.equal(appendIdeaToNotes(next, original), next)
  assert.equal(appendIdeaToNotes(next, { ...original, uid: 'idea-b' }), next)
  const revised = appendIdeaToNotes(next, { ...original, title: '修改标题', body: '修改内容' })
  assert.ok(revised.endsWith('## 灵感：修改标题\n\n修改内容'))
  assert.ok(revised.includes(original.body))
  assert.equal(appendIdeaToNotes(revised, original), revised)
  assert.equal(original.body, idea().body)
  assert.notEqual(appendIdeaToNotes(next, { ...original, body: original.body.slice(0, 2) }), next)
})

test('older machine-marked copies are recognized without creating any new marker', () => {
  const original = idea()
  const uid = [...original.uid].map(char => char.charCodeAt(0).toString(16).padStart(4, '0')).join('')
  const old = `<!-- novel-assistant:idea:${uid} -->\n## ${original.title}\n\n${original.body}`
  assert.equal(appendIdeaToNotes(old, original), old)
  const revised = appendIdeaToNotes(old, { ...original, body: '新版原文' })
  assert.ok(revised.endsWith('新版原文'))
  assert.equal((revised.match(/<!-- novel-assistant:idea:/g) || []).length, 1)
})

test('notes limits reject an entire insertion without truncating and accept exactly 50000 characters', () => {
  const original = idea({ body: '内容' })
  const addition = appendIdeaToNotes('', original)
  const notes = '甲'.repeat(50000 - addition.length - 2)
  const full = appendIdeaToNotes(notes, original)
  assert.equal(full.length, 50000)
  assert.equal(appendIdeaToNotes(full, original), full)
  assert.throws(() => appendIdeaToNotes(notes + '甲', original), /50000/)
  assert.throws(() => appendIdeaToNotes('甲'.repeat(50000), original), /50000/)
  assert.equal(notes.length, 50000 - addition.length - 2)
})

test('drag data contains only a validated reference in the current novel', () => {
  assert.match(IDEA_DRAG_MIME, /^application\//)
  assert.deepEqual(readIdeaDrag(JSON.stringify({ uid: 'idea-a', novelId: 1 }), 1), { uid: 'idea-a', novelId: 1 })
  for (const payload of [
    '', 'invalid JSON', 'null', '[]', '{}',
    JSON.stringify({ uid: 'idea-a', novelId: 2 }),
    JSON.stringify({ uid: 'idea-a', novelId: '1' }),
    JSON.stringify({ uid: '', novelId: 1 }),
    JSON.stringify({ uid: '  ', novelId: 1 }),
    JSON.stringify({ uid: ' idea-a', novelId: 1 }),
    JSON.stringify({ uid: 'idea\na', novelId: 1 }),
    JSON.stringify({ uid: 'a'.repeat(101), novelId: 1 }),
    JSON.stringify({ uid: 'idea-a', novelId: 1, body: '不能把卡片内容当成引用' }),
  ]) {
    assert.equal(readIdeaDrag(payload, 1), null)
  }
  for (const novelId of [0, -1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
    assert.equal(readIdeaDrag(JSON.stringify({ uid: 'idea-a', novelId }), novelId), null)
  }
})
