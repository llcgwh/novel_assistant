import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'
const load = compileSources()
const { renderManuscriptNode } = await load(
  'components/writing/ReadonlyManuscript',
)
const { findDocumentBlock, readingOrder, readingLeafBlockIds } =
  await load('utils/revisionDesk')
const { readerDraftKey, decodeReaderDraft } = await load('utils/readerDraft')

test('reader renders imported text and supported marks without accepting HTML or attributes', () => {
  const node = renderManuscriptNode({
    type: 'paragraph',
    attrs: { id: 'source-1', onclick: 'alert(1)', style: 'display:none' },
    content: [
      {
        type: 'text',
        text: '<img src=x onerror=alert(1)>',
        marks: [
          { type: 'bold' },
          { type: 'link', attrs: { href: 'javascript:alert(1)' } },
        ],
      },
    ],
  })
  assert.equal(node.type, 'p')
  assert.deepEqual(node.props, { 'data-reader-block': 'source-1' })
  assert.equal(node.children[0].type, 'strong')
  assert.equal(node.children[0].children[0], '<img src=x onerror=alert(1)>')
  assert.equal(node.children[0].props, null)
})

test('unsupported or prototype-named nodes and marks remain harmless text', () => {
  for (const type of ['script', 'iframe', 'constructor', '__proto__']) {
    const node = renderManuscriptNode({
      type,
      attrs: { src: 'https://external.invalid/' },
      content: [{ type: 'text', text: 'kept', marks: [{ type }] }],
    })
    assert.deepEqual(node, ['kept'])
  }
  assert.equal(
    renderManuscriptNode({
      type: 'heading',
      attrs: { level: 2.9 },
      content: [],
    }).type,
    'h2',
  )
})

test('reader follows volume tree order, ignores deleted chapters, and retains orphaned volumes', () => {
  const rows = [
    { uid: 'b', volumeId: 'vol-b' },
    { uid: 'a2', volumeId: 'vol-a' },
    { uid: 'free', volumeId: null },
    { uid: 'a1', volumeId: 'vol-a' },
    { uid: 'deleted', volumeId: null, deleted: true },
    { uid: 'orphan', volumeId: 'legacy' },
  ]
  assert.deepEqual(
    readingOrder(rows, [{ uid: 'vol-a' }, { uid: 'vol-b' }]).map(
      (row) => row.uid,
    ),
    ['free', 'a2', 'a1', 'b', 'orphan'],
  )
  assert.equal(rows.length, 6)
})

test('revision anchors resolve nested paragraphs and report missing anchors without fallback', () => {
  const paragraph = {
    type: 'paragraph',
    attrs: { id: 'nested' },
    content: [{ type: 'text', text: '原文' }],
  }
  const doc = {
    type: 'doc',
    content: [
      { type: 'blockquote', attrs: { id: 'quote' }, content: [paragraph] },
    ],
  }
  assert.equal(findDocumentBlock(doc, 'nested'), paragraph)
  assert.equal(findDocumentBlock(doc, 'deleted'), undefined)
  assert.equal(findDocumentBlock(doc, ''), undefined)
})

test('local reader drafts are separated by novel, book identity, and chapter', () => {
  const key = readerDraftKey(1, 'book-a', 'chapter')
  assert.notEqual(key, readerDraftKey(2, 'book-a', 'chapter'))
  assert.notEqual(key, readerDraftKey(1, 'book-b', 'chapter'))
  assert.notEqual(key, readerDraftKey(1, 'book-a', 'chapter-2'))
  assert.notEqual(readerDraftKey(1, 'a:b', 'c'), readerDraftKey(1, 'a', 'b:c'))
})

test('reader draft recovery preserves retry identity and rejects malformed or oversized input', () => {
  const draft = {
    uid: 'note-id',
    createdAt: '2026-09-27T10:00:00Z',
    body: 'keep this dialogue',
    category: 'dialogue',
    anchor: { blockId: 'p1', excerpt: 'original text' },
  }
  assert.deepEqual(decodeReaderDraft(JSON.stringify(draft)), draft)
  assert.equal(decodeReaderDraft('{}'), undefined)
  assert.equal(
    decodeReaderDraft(JSON.stringify({ ...draft, category: '__proto__' })),
    undefined,
  )
  assert.equal(
    decodeReaderDraft(JSON.stringify({ ...draft, body: 'a'.repeat(4001) })),
    undefined,
  )
})

test('nested quote containers cannot mask the visible paragraph when remembering reading position', () => {
  const doc = {
    type: 'doc',
    content: [
      {
        type: 'blockquote',
        attrs: { id: 'outer-quote' },
        content: [
          { type: 'paragraph', attrs: { id: 'above-screen' } },
          {
            type: 'blockquote',
            attrs: { id: 'inner-quote' },
            content: [
              {
                type: 'bulletList',
                content: [
                  {
                    type: 'listItem',
                    content: [
                      { type: 'paragraph', attrs: { id: 'visible-paragraph' } },
                      { type: 'paragraph', attrs: { id: 'later-paragraph' } },
                    ],
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  }
  const leaves = readingLeafBlockIds(doc)
  assert.deepEqual(
    [...leaves],
    ['above-screen', 'visible-paragraph', 'later-paragraph'],
  )
  const layout = [
    { id: 'outer-quote', bottom: 900 },
    { id: 'above-screen', bottom: 80 },
    { id: 'inner-quote', bottom: 850 },
    { id: 'visible-paragraph', bottom: 300 },
    { id: 'later-paragraph', bottom: 800 },
  ]
  const viewportTop = 100
  const visible = layout.find(
    (block) => leaves.has(block.id) && block.bottom > viewportTop,
  )
  assert.equal(visible.id, 'visible-paragraph')
})

test('pagination accounts for actual column width and gap without adding an empty page', async () => {
  const { pageCountForWidth, pageForOffset } = await load('utils/readerLayout')
  assert.equal(pageCountForWidth(600, 600, 40), 1)
  assert.equal(pageCountForWidth(1240, 600, 40), 2)
  assert.equal(pageCountForWidth(1880, 600, 40), 3)
  assert.equal(pageForOffset(640, 600, 40, 3), 1)
  assert.equal(pageForOffset(1280, 600, 40, 3), 2)
  assert.equal(pageForOffset(-20, 600, 40, 3), 0)
  assert.equal(pageForOffset(5000, 600, 40, 3), 2)
})

test('continuous reading keeps a bounded window even in a thousand-chapter book', async () => {
  const { boundedReadingWindow } = await load('utils/readerLayout')
  assert.deepEqual(boundedReadingWindow(0, 1000), { start: 0, end: 3 })
  assert.deepEqual(boundedReadingWindow(500, 1000), { start: 498, end: 503 })
  assert.deepEqual(boundedReadingWindow(999, 1000), { start: 997, end: 1000 })
})
