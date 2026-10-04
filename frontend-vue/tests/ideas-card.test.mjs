import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { ref } from 'vue'
import { compileSources } from './helpers/load-source.mjs'
const { appendIdeaToNotes, IDEA_DRAG_MIME, readIdeaDrag } = await compileSources()('utils/ideas')
const idea = { uid: 'idea-a', title: '留住对白', body: '“潮水会回来。”\n😀', category: 'dialogue', chapterUids: [], createdAt: '', updatedAt: '' }
function harness() {
  const source = readFileSync(new URL('../src/components/writing/WritingContext.vue', import.meta.url), 'utf8')
  const start = source.indexOf('const inbox = useIdeasStore()')
  const code = ts.transpileModule(source.slice(start, source.indexOf('const kind = ref(', start)), { compilerOptions: { module: ts.ModuleKind.None, target: ts.ScriptTarget.ES2022 } }).outputText
  let changed = 0, saves = 0
  const writer = { novelId: 1, current: { uid: 'chapter-a', notes: '已有便笺', doc: { type: 'doc', content: ['正式稿不变'] } }, localSafe: true, changed() { changed++ }, async flush() { saves++; return true } }
  const inbox = { novelId: 1, ideas: [idea] }
  const runtime = { ref, useIdeasStore: () => inbox, writer, appendIdeaToNotes, IDEA_DRAG_MIME, readIdeaDrag }
  const actions = new Function(...Object.keys(runtime), code + ';return {insertIdea,dropIdea,dragOverIdea,ideaMessage,insertingIdea,ideaDragging}')(...Object.values(runtime))
  return { ...actions, writer, inbox, changed: () => changed, saves: () => saves }
}
test('keyboard insert writes actual chapter notes, preserves manuscript/source and prevents duplicate copies', async () => {
  const h = harness(), prose = JSON.stringify(h.writer.current.doc), source = JSON.stringify(idea)
  await h.insertIdea(idea)
  assert.ok(h.writer.current.notes.startsWith('已有便笺\n\n'))
  assert.ok(h.writer.current.notes.includes(idea.body))
  assert.equal(JSON.stringify(h.writer.current.doc), prose)
  assert.equal(JSON.stringify(idea), source)
  assert.match(h.ideaMessage.value, /并保存/)
  assert.equal(h.saves(), 1)
  const saved = h.writer.current.notes
  await h.insertIdea(idea)
  assert.equal(h.writer.current.notes, saved)
  assert.equal(h.saves(), 1)
  assert.match(h.ideaMessage.value, /未重复/)
})
test('failed chapter save reports only local draft and never server success', async () => {
  const h = harness()
  h.writer.flush = async () => false
  await h.insertIdea(idea)
  assert.match(h.ideaMessage.value, /尚未保存到作品/)
  assert.doesNotMatch(h.ideaMessage.value, /并保存/)
})
test('full notes reject insertion without truncation, mutation, or save', async () => {
  const h = harness()
  h.writer.current.notes = '字'.repeat(50000)
  await h.insertIdea(idea)
  assert.equal(h.writer.current.notes.length, 50000)
  assert.equal(h.changed(), 0)
  assert.equal(h.saves(), 0)
  assert.match(h.ideaMessage.value, /50000/)
})
test('cross-novel and missing drag references are rejected before any chapter mutation', () => {
  const h = harness()
  for (const payload of [{ novelId: 2, uid: idea.uid }, { novelId: 1, uid: 'missing' }]) {
    let prevented = false
    h.dropIdea({ preventDefault() { prevented = true }, dataTransfer: { types: [IDEA_DRAG_MIME], getData: () => JSON.stringify(payload) } })
    assert.equal(prevented, true)
    assert.equal(h.changed(), 0)
    assert.match(h.ideaMessage.value, /当前作品/)
  }
})
test('valid drag uses same insertion path and ignores normal text drags', async () => {
  const h = harness()
  h.dropIdea({ dataTransfer: { types: ['text/plain'] } })
  assert.equal(h.changed(), 0)
  h.dropIdea({ preventDefault() {}, dataTransfer: { types: [IDEA_DRAG_MIME], getData: () => JSON.stringify({ novelId: 1, uid: idea.uid }) } })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(h.changed(), 1)
  assert.equal(h.saves(), 1)
  assert.ok(h.writer.current.notes.includes(idea.body))
})
test('late insertion acknowledgements cannot claim success in a switched chapter', async () => {
  const h = harness()
  let finish
  h.writer.flush = () => new Promise(resolve => finish = resolve)
  const pending = h.insertIdea(idea)
  h.writer.current = { uid: 'chapter-b', notes: '第二章' }
  finish(true); await pending
  assert.equal(h.writer.current.notes, '第二章')
  assert.equal(h.ideaMessage.value, '')
})
