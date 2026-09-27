import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'
const { nextPenAnchor, nextPenHasContent, nextPenSignature, readNextPenDraft } =
  await compileSources()('utils/nextPen')
const paragraph = (id, text) => ({
  type: 'paragraph',
  attrs: { id },
  content: [{ type: 'text', text }],
})
const chapter = {
  uid: 'chapter-a',
  doc: {
    type: 'doc',
    content: [
      paragraph('first', '第一幕'),
      paragraph('last', '结尾一句'),
      paragraph('empty', ''),
    ],
  },
}
const note = {
  chapterUid: 'chapter-a',
  blockId: 'first',
  excerpt: '第一幕',
  nextScene: '明早启航',
  question: '',
  opening: '',
  updatedAt: '2026-09-27',
}

test('next pen captures the active paragraph and falls back to last nonempty paragraph', () => {
  assert.deepEqual(nextPenAnchor(chapter, 'first'), {
    chapterUid: 'chapter-a',
    blockId: 'first',
    excerpt: '第一幕',
  })
  assert.equal(nextPenAnchor(chapter, '').blockId, 'last')
  assert.equal(nextPenAnchor(chapter, 'removed-block').blockId, 'last')
  assert.equal(nextPenAnchor(chapter, 'empty').blockId, 'empty')
})
test('next pen keeps an empty chapter anchor and bounds very long excerpts', () => {
  assert.deepEqual(
    nextPenAnchor({ uid: 'empty', doc: { type: 'doc', content: [] } }, ''),
    { chapterUid: 'empty', blockId: '', excerpt: '' },
  )
  assert.equal(
    nextPenAnchor(
      {
        uid: 'long',
        doc: {
          type: 'doc',
          content: [paragraph('p', '字'.repeat(500) + '尾')],
        },
      },
      '',
    ).excerpt,
    '字'.repeat(179) + '尾',
  )
})
test('at least one meaningful field is required and damaged local drafts are ignored', () => {
  assert.equal(
    nextPenHasContent({ nextScene: '  ', question: '\n', opening: '' }),
    false,
  )
  assert.equal(
    nextPenHasContent({ nextScene: '', question: '为何？', opening: '' }),
    true,
  )
  assert.equal(readNextPenDraft('{'), null)
  assert.equal(readNextPenDraft(JSON.stringify({ note, baseline: 4 })), null)
  assert.equal(
    readNextPenDraft(
      JSON.stringify({ note: { ...note, chapterUid: '' }, baseline: '' }),
    ),
    null,
  )
  assert.equal(
    readNextPenDraft(
      JSON.stringify({ note: { ...note, nextScene: 5 }, baseline: '' }),
    ),
    null,
  )
})
test('local drafts preserve original chapter and baseline across unrelated chapter changes', () => {
  const draft = { note, baseline: nextPenSignature(note) }
  assert.deepEqual(readNextPenDraft(JSON.stringify(draft)), draft)
  assert.notEqual(
    nextPenSignature({ ...note, updatedAt: '2026-09-28' }),
    draft.baseline,
  )
  assert.equal(nextPenSignature(null), '')
})
