import test from 'node:test'
import assert from 'node:assert/strict'
import { effectScope } from 'vue'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources()
const { useSearch } = await load('composables/useSearch')
const { highlightParts, excerpt, emptySearchResults, searchGroups } = await load('utils/searchResults')
function setup(t) {
  const pending = []
  const scope = effectScope()
  const state = scope.run(() => useSearch((query, signal) => new Promise((resolve, reject) => pending.push({ query, signal, resolve, reject }))))
  t.after(() => scope.stop())
  return { state, pending, scope }
}
const results = name => ({ ...emptySearchResults(), characters: [{ id: 1, name }] })

test('the newest search wins even if the older transport ignores abort', async t => {
  const { state, pending } = setup(t)
  const first = state.search('old')
  const second = state.search('  new  ')
  assert.equal(pending[0].signal.aborted, true)
  assert.equal(pending[1].query, 'new')
  pending[1].resolve(results('new'))
  await second
  pending[0].resolve(results('old'))
  await first
  assert.equal(state.results.value.characters[0].name, 'new')
  assert.equal(state.query.value, 'new')
})

test('old failure cannot clear the current loading state or show an error', async t => {
  const { state, pending } = setup(t)
  const first = state.search('old')
  const second = state.search('new')
  pending[0].reject(new Error('late failure'))
  await first
  assert.equal(state.loading.value, true)
  assert.equal(state.error.value, '')
  pending[1].resolve(results('new'))
  await second
})

test('failure clears previous results and a retry can recover', async t => {
  const { state, pending } = setup(t)
  const first = state.search('old')
  pending[0].resolve(results('old')); await first
  const failed = state.search('new')
  pending[1].reject(new Error('offline')); await failed
  assert.deepEqual(state.results.value.characters, [])
  assert.ok(state.error.value)
  const retry = state.search(state.query.value)
  pending[2].resolve(results('new')); await retry
  assert.equal(state.error.value, '')
  assert.equal(state.results.value.characters[0].name, 'new')
})

test('clearing the query cancels pending search and does not request every record', async t => {
  const { state, pending } = setup(t)
  const old = state.search('old')
  await state.search('  ')
  assert.equal(pending.length, 1)
  assert.equal(pending[0].signal.aborted, true)
  assert.equal(state.loading.value, false)
  pending[0].resolve(results('old')); await old
  assert.equal(state.query.value, '')
  assert.deepEqual(state.results.value.characters, [])
})

test('leaving the search view cancels the request and prevents late updates', async t => {
  const { state, pending, scope } = setup(t)
  const search = state.search('old')
  scope.stop()
  assert.equal(pending[0].signal.aborted, true)
  pending[0].resolve(results('old')); await search
  assert.deepEqual(state.results.value.characters, [])
})

test('highlighting treats markup and regex symbols as plain text', () => {
  const text = '<img src=x> [.*] [.*]'
  const parts = highlightParts(text, '[.*]')
  assert.equal(parts.filter(part => part.match).length, 2)
  assert.equal(parts.map(part => part.text).join(''), text)
  assert.equal(highlightParts('Moon moon', 'MOON').filter(part => part.match).length, 2)
})

test('snippets show a match in long content without adding ellipses to short text', () => {
  assert.equal(excerpt('短描述', '描述'), '短描述')
  const snippet = excerpt('旧事'.repeat(200) + '月门' + '结尾'.repeat(100), '月门')
  assert.ok(snippet.includes('月门'))
  assert.ok(snippet.startsWith('…'))
  assert.ok(snippet.length <= 132)
})

test('manuscripts and seven reference categories preserve targets and tolerate older API responses', () => {
  const data = { ...emptySearchResults(), worldviewEntries: [{ id: 7, name: '月门', content: '月门的历史' }] }
  const group = searchGroups(data, '月门').find(group => group.key === 'worldviewEntries')
  assert.equal(group.path, 'worldview')
  assert.equal(group.items[0].id, 7)
  assert.equal(group.items[0].excerpt, '月门的历史')
  delete data.worldviewEntries
  assert.equal(searchGroups(data, '月门').length, 8)
  data.manuscripts = [{id:'chapter-uid',title:'月门来信',excerpt:'正文命中月门'}]
  const manuscript = searchGroups(data,'月门').find(group=>group.key==='manuscripts')
  assert.equal(manuscript.path,'writing')
  assert.equal(manuscript.items[0].id,'chapter-uid')
})
