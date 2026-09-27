import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'
const load = compileSources()
const { compareHistory, diffText, adoptHistoricalBlock, blockText } =
  await load('utils/historyDiff')
const { adoptHistorySelection, restoreHistorySnapshot } = await load(
  'utils/historyAdoption',
)
const p = (id, text, extra = {}) => ({
  type: 'paragraph',
  attrs: { id },
  content: [{ type: 'text', text }],
  ...extra,
})
const doc = (...content) => ({ type: 'doc', content })
const clone = (value) => JSON.parse(JSON.stringify(value))

test('paragraph comparison retains intentional leading and trailing whitespace', () => {
  assert.equal(blockText(p('a', '  空白保留  ')), '  空白保留  ')
  assert.deepEqual(
    diffText(blockText(p('a', '尾部')), blockText(p('a', '尾部 '))),
    [
      { kind: 'equal', text: '尾部' },
      { kind: 'add', text: ' ' },
    ],
  )
})

test('Chinese, English, emoji and line breaks reconstruct exactly from highlighted changes', () => {
  for (const [before, after] of [
    ['海潮慢慢退去。', '海潮悄悄退去。'],
    ['她说：\nhello 🌙', '他说：\nhello 🌊'],
    ['', '新增'],
    ['删除', ''],
    ['same', 'same'],
  ]) {
    const changes = diffText(before, after)
    assert.equal(
      changes
        .filter((x) => x.kind !== 'add')
        .map((x) => x.text)
        .join(''),
      before,
    )
    assert.equal(
      changes
        .filter((x) => x.kind !== 'remove')
        .map((x) => x.text)
        .join(''),
      after,
    )
    assert.ok(changes.every((x) => x.text.length > 0))
  }
})

test('very long replacements use bounded diff while retaining both complete texts', () => {
  const before = '共同开头' + '旧'.repeat(20000) + '共同结尾'
  const after = '共同开头' + '新'.repeat(20000) + '共同结尾'
  const parts = diffText(before, after)
  assert.equal(parts.length, 4)
  assert.equal(
    parts
      .filter((x) => x.kind !== 'add')
      .map((x) => x.text)
      .join(''),
    before,
  )
  assert.equal(
    parts
      .filter((x) => x.kind !== 'remove')
      .map((x) => x.text)
      .join(''),
    after,
  )
})

test('stable anchors distinguish modifications, additions, removals and formatting', () => {
  const before = doc(p('a', '旧句'), p('b', '删掉'), p('c', '加粗'))
  const now = doc(
    p('a', '新句'),
    p('new', '增加'),
    p('c', '加粗', {
      content: [{ type: 'text', text: '加粗', marks: [{ type: 'bold' }] }],
    }),
  )
  assert.deepEqual(
    compareHistory(now, before).map((x) => x.kind),
    ['changed', 'added', 'changed', 'removed'],
  )
  assert.ok(compareHistory(now, before).every((x) => !x.moved))
})

test('reordered anchors are flagged and adopting content preserves the current order', () => {
  const before = doc(p('a', '旧甲'), p('b', '乙'))
  const now = doc(p('b', '乙'), p('a', '新甲'))
  const rows = compareHistory(now, before)
  assert.ok(rows.every((x) => x.moved))
  const next = adoptHistoricalBlock(now, before, rows[1])
  assert.deepEqual(
    next.content.map((x) => x.attrs.id),
    ['b', 'a'],
  )
  assert.equal(next.content[1].content[0].text, '旧甲')
  assert.equal(now.content[1].content[0].text, '新甲')
})

test('restoring a deleted block uses surviving historical neighbours', () => {
  const before = doc(p('a', '甲'), p('b', '乙'), p('c', '丙'))
  let now = doc(p('a', '甲'), p('c', '丙'), p('new', '后记'))
  now = adoptHistoricalBlock(
    now,
    before,
    compareHistory(now, before).find((x) => x.kind === 'removed'),
  )
  assert.deepEqual(
    now.content.map((x) => x.attrs.id),
    ['a', 'b', 'c', 'new'],
  )
  const lastOld = doc(p('a', '甲'), p('b', '乙'))
  const withNew = doc(p('a', '甲'), p('new', '后记'))
  assert.deepEqual(
    adoptHistoricalBlock(
      withNew,
      lastOld,
      compareHistory(withNew, lastOld).find((x) => x.kind === 'removed'),
    ).content.map((x) => x.attrs.id),
    ['a', 'b', 'new'],
  )
})

test('nested formatting is retained and colliding moved child anchors cannot be duplicated', () => {
  const quote = {
    type: 'blockquote',
    attrs: { id: 'quote' },
    content: [p('nested', '旧引用')],
  }
  const before = doc(quote)
  const now = doc({ ...clone(quote), content: [p('nested', '新引用')] })
  assert.deepEqual(
    adoptHistoricalBlock(now, before, compareHistory(now, before)[0]),
    before,
  )
  const movedChild = doc(p('nested', '已经移到引用外面'))
  assert.throws(
    () =>
      adoptHistoricalBlock(
        movedChild,
        before,
        compareHistory(movedChild, before).find((x) => x.kind === 'removed'),
      ),
    /移动到别处/,
  )
})

test('a stale row cannot replace a newly edited paragraph', () => {
  const before = doc(p('a', '旧')),
    now = doc(p('a', '新'))
  const row = compareHistory(now, before)[0]
  now.content[0].content[0].text = '又改了一次'
  assert.throws(() => adoptHistoricalBlock(now, before, row), /已变化/)
})

test('ordinary lists match by surviving anchored paragraphs even without a list root ID', () => {
  const list = (...paragraphs) => ({
    type: 'bulletList',
    content: paragraphs.map((paragraph) => ({
      type: 'listItem',
      content: [paragraph],
    })),
  })
  const before = doc(list(p('a', '第一项'), p('b', '旧第二项')))
  const now = doc(list(p('b', '改过的第二项')))
  const rows = compareHistory(now, before)
  assert.equal(rows.length, 1)
  assert.equal(rows[0].kind, 'changed')
  assert.deepEqual(adoptHistoricalBlock(now, before, rows[0]), before)
})

test('adoption does not mutate the selected diff row or historical source', () => {
  const before = doc(p('a', '历史')),
    now = doc(p('a', '当前'))
  const row = clone(compareHistory(now, before)[0])
  const restored = adoptHistoricalBlock(now, before, row)
  restored.content[0].content[0].text = '之后的新输入'
  assert.equal(before.content[0].content[0].text, '历史')
  assert.equal(row.historical.content[0].text, '历史')
  assert.equal(now.content[0].content[0].text, '当前')
})

test('repeated legacy paragraphs without IDs remain one-to-one and large chapter alignment is supported', () => {
  const legacy = doc(
    { type: 'paragraph', content: [{ type: 'text', text: '重复' }] },
    { type: 'paragraph', content: [{ type: 'text', text: '重复' }] },
  )
  assert.deepEqual(
    compareHistory(legacy, legacy).map((x) => x.historicalIndex),
    [0, 1],
  )
  const large = doc(
    ...Array.from({ length: 12000 }, (_, i) => p(String(i), '第' + i + '段')),
  )
  const changed = clone(large)
  changed.content[6100].content[0].text = '已修改'
  assert.equal(
    compareHistory(changed, large).filter((x) => x.kind === 'changed').length,
    1,
  )
})

function writerFixture() {
  const history = doc(p('a', '旧稿')),
    current = {
      uid: 'chapter',
      doc: doc(p('a', '未保存新稿')),
      title: '保留新章名',
      links: [{ uid: 'link' }],
      notes: '保留创作卡',
    }
  const saves = []
  const writer = {
    current,
    changed() {},
    async flush(checkpoint) {
      saves.push({ checkpoint, current: clone(this.current) })
      return true
    },
  }
  return {
    writer,
    history,
    row: compareHistory(current.doc, history)[0],
    saves,
  }
}

test('selective adoption checkpoints unsaved prose before editing and retains current metadata', async () => {
  const { writer, history, row, saves } = writerFixture()
  await adoptHistorySelection(writer, history, row, () => {})
  assert.equal(saves.length, 2)
  assert.ok(saves.every((x) => x.checkpoint))
  assert.equal(saves[0].current.doc.content[0].content[0].text, '未保存新稿')
  assert.equal(saves[1].current.doc.content[0].content[0].text, '旧稿')
  assert.equal(writer.current.title, '保留新章名')
  assert.deepEqual(writer.current.links, [{ uid: 'link' }])
  assert.equal(writer.current.notes, '保留创作卡')
})

test('failed checkpoint or a work/chapter switch prevents historical replacement', async () => {
  for (const change of ['failed', 'novel', 'chapter', 'prose']) {
    const { writer, history, row } = writerFixture()
    let switched = false
    writer.flush = async () => {
      if (change === 'failed') return false
      if (change === 'novel') switched = true
      if (change === 'chapter')
        writer.current = { ...writer.current, uid: 'other' }
      if (change === 'prose') writer.current.doc = doc(p('a', '期间输入的新句'))
      return true
    }
    await assert.rejects(
      adoptHistorySelection(writer, history, row, () => {
        if (switched) throw Error('作品已切换')
      }),
    )
    assert.notEqual(writer.current.doc.content[0].content[0].text, '旧稿')
  }
})

test('a failed adopted save retains the adopted local document for ordinary save retry', async () => {
  const { writer, history, row } = writerFixture()
  let calls = 0
  writer.flush = async () => ++calls === 1
  await assert.rejects(
    adoptHistorySelection(writer, history, row, () => {}),
    /本机稿/,
  )
  assert.equal(writer.current.doc.content[0].content[0].text, '旧稿')
})

test('whole-history restore rejects another chapter before or during checkpoint', async () => {
  for (const when of ['before', 'during']) {
    const { writer, history } = writerFixture()
    const old = { ...clone(writer.current), doc: history }
    let saves = 0
    if (when === 'before') writer.current.uid = 'other'
    writer.flush = async () => {
      saves++
      writer.current.uid = 'other'
      return true
    }
    await assert.rejects(
      restoreHistorySnapshot(writer, old, 'chapter', () => {}),
      /章节已切换/,
    )
    assert.equal(saves, when === 'before' ? 0 : 1)
    assert.equal(writer.current.doc.content[0].content[0].text, '未保存新稿')
  }
})

test('whole-history restoration saves current draft first and never aliases the historical object', async () => {
  const { writer, history, saves } = writerFixture()
  const old = { ...clone(writer.current), doc: history, title: '旧标题' }
  await restoreHistorySnapshot(writer, old, 'chapter', () => {})
  assert.equal(saves[0].current.doc.content[0].content[0].text, '未保存新稿')
  assert.equal(writer.current.title, '旧标题')
  writer.current.doc.content[0].content[0].text = '继续输入'
  assert.equal(old.doc.content[0].content[0].text, '旧稿')
})

test('restoring among thousands of missing historical neighbours retains all current blocks', () => {
  const before = doc(
    ...Array.from({ length: 10000 }, (_, i) => p('old-' + i, '旧段落')),
  )
  const now = doc(
    ...Array.from({ length: 10000 }, (_, i) => p('new-' + i, '新段落')),
  )
  const row = compareHistory(now, before).find((row) => row.kind === 'removed')
  const result = adoptHistoricalBlock(now, before, row)
  assert.equal(result.content.length, 10001)
  assert.deepEqual(result.content.slice(0, 10000), now.content)
  assert.equal(result.content.at(-1).attrs.id, 'old-0')
})
