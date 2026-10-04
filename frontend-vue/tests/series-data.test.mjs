import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources()
const {
  SERIES_KINDS, seriesFields, emptySeriesPayload, validateSeriesPayload,
  seriesKindLabel, seriesFieldLabel, cloneSeries, revisionLabel,
} = await load('utils/series')
const { seriesApi } = await load('api/series')
const transport = await load('api/request')

const templateUid = '11111111-1111-4111-8111-111111111111'
const revisionUid = '22222222-2222-4222-8222-222222222222'
const copyUid = '33333333-3333-4333-8333-333333333333'
const universeUid = '44444444-4444-4444-8444-444444444444'
const mutation = {
  mutationId: '55555555-5555-4555-8555-555555555555',
  epoch: '66666666-6666-4666-8666-666666666666',
  expectedVersion: 7,
}
const schemas = {
  calendar: ['name', 'description', 'notes', 'epoch', 'units', 'dateFormat', 'rules'],
  location: ['name', 'description', 'notes', 'geography', 'environment', 'culture'],
  race: ['name', 'description', 'notes', 'traits', 'origins', 'culture'],
  organization: ['name', 'description', 'notes', 'purpose', 'structure', 'rules'],
  character: ['name', 'description', 'notes', 'role', 'personality', 'appearance', 'background'],
}
const payload = kind => ({ ...emptySeriesPayload(kind), name: '海潮纪', description: '  第一行中文\n第二行 🌙\r\n尾部空格  ' })

test('all five payloads round-trip Chinese, whitespace, multiline and intentional empty values', () => {
  assert.deepEqual(SERIES_KINDS.map(item => item.value), Object.keys(schemas))
  for (const [kind, keys] of Object.entries(schemas)) {
    const blank = emptySeriesPayload(kind)
    assert.deepEqual(Object.keys(blank), keys)
    assert.ok(Object.values(blank).every(value => value === ''))
    const draft = payload(kind)
    const before = JSON.stringify(draft)
    assert.equal(validateSeriesPayload(kind, draft), '')
    assert.equal(JSON.stringify(draft), before)
    assert.deepEqual(cloneSeries(draft), draft)
    assert.equal(cloneSeries(draft).notes, '')
    assert.deepEqual(seriesFields(kind).map(field => field.key), keys)
  }
})

test('validation rejects absent, null, non-text and foreign-kind fields without repairing the draft', () => {
  for (const kind of Object.keys(schemas)) {
    for (const key of schemas[kind]) {
      const missing = payload(kind)
      delete missing[key]
      assert.match(validateSeriesPayload(kind, missing), /缺少/)
      assert.equal(Object.hasOwn(missing, key), false)
      for (const value of [null, undefined, 4, false, [], {}]) {
        const draft = { ...payload(kind), [key]: value }
        assert.match(validateSeriesPayload(kind, draft), /文本/)
        assert.equal(draft[key], value)
      }
    }
    const extra = { ...payload(kind), unknown: '' }
    assert.match(validateSeriesPayload(kind, extra), /不支持/)
    assert.equal(extra.unknown, '')
  }
  for (const invalid of [null, [], 'content', 5]) assert.notEqual(validateSeriesPayload('race', invalid), '')
  assert.match(validateSeriesPayload('race', { ...payload('race'), geography: '不猜成文化' }), /不支持/)
  assert.match(validateSeriesPayload('unsupported', {}), /未知/)
  assert.throws(() => emptySeriesPayload('unsupported'), /未知/)
})

test('required names and length limits are checked without trimming or truncating local text', () => {
  const draft = payload('location')
  draft.name = ' \n\t '
  assert.match(validateSeriesPayload('location', draft), /名称/)
  draft.name = '  合法名称  '
  assert.equal(validateSeriesPayload('location', draft), '')
  assert.equal(draft.name, '  合法名称  ')
  draft.name = '名'.repeat(200)
  draft.notes = '字'.repeat(20000)
  assert.equal(validateSeriesPayload('location', draft), '')
  draft.name += '名'
  assert.match(validateSeriesPayload('location', draft), /200 字/)
  assert.equal(draft.name.length, 201)
  draft.name = '名称'
  draft.notes += '\n'
  assert.match(validateSeriesPayload('location', draft), /20000 字/)
  assert.equal(draft.notes.length, 20001)
})

test('payload capacity is measured as serialized UTF-8 bytes, not character count', () => {
  const ascii = payload('calendar')
  for (const key of schemas.calendar.filter(key => key !== 'name')) ascii[key] = 'a'.repeat(20000)
  assert.equal(validateSeriesPayload('calendar', ascii), '')
  const chinese = { ...ascii }
  for (const key of schemas.calendar.filter(key => key !== 'name')) chinese[key] = '中'.repeat(20000)
  const before = JSON.stringify(chinese)
  assert.match(validateSeriesPayload('calendar', chinese), /256 KiB/)
  assert.equal(JSON.stringify(chinese), before)
})

test('field labels distinguish location culture and calendar rules, and field metadata cannot corrupt later drafts', () => {
  assert.equal(seriesFieldLabel('location', 'culture'), '地方文化')
  assert.equal(seriesFieldLabel('race', 'culture'), '文化')
  assert.equal(seriesFieldLabel('calendar', 'rules'), '历法规则')
  assert.equal(seriesFieldLabel('organization', 'rules'), '规则')
  assert.equal(seriesFieldLabel('race', 'unrecognized'), 'unrecognized')
  assert.equal(seriesKindLabel('character'), '人物模板')
  const fields = seriesFields('race')
  fields[0].maxLength = 1
  fields[0].key = 'wrong'
  assert.equal(seriesFields('race')[0].maxLength, 200)
  assert.ok(Object.hasOwn(emptySeriesPayload('race'), 'name'))
})

test('cloning independently editable copies preserves source and nested history across novels and universes', () => {
  const source = { uid: revisionUid, payload: payload('race') }
  const snapshot = { universeUid, content: source.payload, fieldOrigins: { name: { kind: 'source', revisionUid } } }
  const original = { uid: copyUid, ...snapshot, history: [{ uid: 'history', before: snapshot }] }
  const novelA = cloneSeries({ copies: [original], sourceRevisions: [source] })
  const novelB = cloneSeries(novelA)
  const parallel = cloneSeries(novelA.copies[0])
  parallel.universeUid = '77777777-7777-4777-8777-777777777777'
  novelA.copies.push(parallel)
  novelA.copies[0].content.description = '本作甲修改'
  novelA.copies[0].history[0].before.content.notes = '单独的历史快照'
  parallel.content.description = '平行世界修改'
  assert.equal(novelB.copies[0].content.description, source.payload.description)
  assert.equal(novelA.sourceRevisions[0].payload.description, source.payload.description)
  assert.equal(novelB.copies[0].history[0].before.content.notes, '')
  assert.equal(parallel.history[0].before.content.notes, '')
  assert.equal(original.content.description, source.payload.description)
})

test('revision labels include immutable identity to distinguish equal numeric branch versions', () => {
  const a = revisionLabel({ number: 2, uid: revisionUid })
  const b = revisionLabel({ number: 2, uid: templateUid })
  assert.match(a, /v2/)
  assert.match(a, /22222222/)
  assert.notEqual(a, b)
})

function mockTransport(response = {}) {
  const calls = []
  globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} }
  transport.setCurrentNovelId(90)
  transport.request.defaults.adapter = async config => {
    calls.push({ method: config.method, url: config.url, params: config.params, body: config.data === undefined ? undefined : JSON.parse(config.data) })
    return { data: response, status: 200, statusText: 'OK', headers: {}, config }
  }
  return calls
}

test('global catalog requests do not inherit the current novel and keep explicit archive and head decisions', async () => {
  const calls = mockTransport({ template: { uid: templateUid }, replayed: false })
  const content = { seriesName: '海潮系列', payload: payload('race'), authorStatus: 'draft', changeNote: '' }
  const create = { mutationId: mutation.mutationId, templateUid, kind: 'race', ...content }
  const cas = { mutationId: mutation.mutationId, expectedLockVersion: 3, expectedHeadRevisionUid: revisionUid }
  await seriesApi.listTemplates()
  await seriesApi.listTemplates(false)
  await seriesApi.template(templateUid)
  await seriesApi.createTemplate(create)
  await seriesApi.publishTemplate(templateUid, { ...cas, ...content })
  await seriesApi.selectHead(templateUid, { ...cas, revisionUid })
  await seriesApi.archiveTemplate(templateUid, { mutationId: mutation.mutationId, expectedLockVersion: 3, archived: false })
  assert.deepEqual(calls.map(call => call.method), ['get', 'get', 'get', 'post', 'post', 'post', 'post'])
  assert.ok(calls.every(call => call.url.startsWith('/series/') && !call.url.includes('/novels/')))
  assert.deepEqual(calls[0].params, { includeArchived: true })
  assert.deepEqual(calls[1].params, { includeArchived: false })
  assert.deepEqual(calls[3].body, create)
  assert.equal(calls[4].url, `/series/templates/${templateUid}/revisions`)
  assert.equal(calls[5].url, `/series/templates/${templateUid}/head`)
  assert.equal(calls[5].body.revisionUid, revisionUid)
  assert.equal(calls[6].body.archived, false)
})

test('all novel mutations retain explicit target, epoch, expected version and complete semantic body', async () => {
  const calls = mockTransport()
  const operations = [
    ['createWorld', ['worlds'], { worldUid: universeUid, name: '本作世界', description: '' }],
    ['updateWorld', [`worlds/${universeUid}`, universeUid], { name: '世界甲', description: '\n' }],
    ['removeWorld', [`worlds/${universeUid}/remove`, universeUid], {}],
    ['copy', ['copies'], { copyUid, templateUid, revisionUid, universeUid, planet: '' }],
    ['duplicateCopy', [`copies/${copyUid}/duplicate`, copyUid], { copyUid: templateUid, universeUid, planet: '第二星球' }],
    ['updateCopy', [`copies/${copyUid}`, copyUid], { content: payload('race'), authorStatus: 'confirmed', planet: '' }],
    ['archiveCopy', [`copies/${copyUid}/archive`, copyUid], { archived: true }],
    ['restoreCopy', [`copies/${copyUid}/restore`, copyUid], { historyUid: templateUid }],
    ['adopt', [`copies/${copyUid}/adopt`, copyUid], { copyHash: 'frozen', baselineRevisionUid: templateUid, revisionUid, selectedFields: ['notes'] }],
    ['review', [`copies/${copyUid}/review`, copyUid], { copyHash: 'frozen', baselineRevisionUid: templateUid, revisionUid }],
  ]
  for (const [method, [path, uid], fields] of operations) {
    const body = { ...mutation, ...fields }
    await seriesApi[method](21, ...(uid ? [uid] : []), body)
    const call = calls.at(-1)
    assert.equal(call.url, `/novels/21/series/${path}`)
    assert.deepEqual(call.body, body)
    assert.equal(call.method, method.startsWith('update') ? 'put' : 'post')
    assert.equal(Object.hasOwn(call.body, 'novelId'), false)
  }
  await seriesApi.state(22)
  assert.equal(calls.at(-1).url, '/novels/22/series')
  assert.equal(calls.at(-1).method, 'get')
})

test('comparison pins the selected source and does not silently adopt or review empty selections', async () => {
  const calls = mockTransport()
  await seriesApi.compare(21, copyUid, revisionUid)
  assert.deepEqual(calls, [{ method: 'post', url: `/novels/21/series/copies/${copyUid}/compare`, params: undefined, body: { revisionUid } }])
  await seriesApi.adopt(21, copyUid, { ...mutation, copyHash: 'frozen', baselineRevisionUid: templateUid, revisionUid, selectedFields: [] })
  assert.equal(calls.length, 2)
  assert.equal(calls[1].url, `/novels/21/series/copies/${copyUid}/adopt`)
  assert.deepEqual(calls[1].body.selectedFields, [])
})

test('exact retries preserve mutation identity and expose both replay result version and newer current state', async () => {
  const result = { state: { version: 12, epoch: mutation.epoch }, replayed: true, resultVersion: 8 }
  const calls = mockTransport(result)
  const body = { ...mutation, content: payload('race'), authorStatus: 'draft', planet: '' }
  await seriesApi.updateCopy(21, copyUid, body)
  const received = await seriesApi.updateCopy(21, copyUid, body)
  assert.deepEqual(calls[0], calls[1])
  assert.equal(received, result)
  assert.equal(received.resultVersion, 8)
  assert.equal(received.state.version, 12)
  assert.equal(received.replayed, true)
})

test('library transport preserves complete packages, omitted export scope, branches and frozen import plans', async () => {
  const calls = mockTransport()
  const pack = {
    format: 'novel-assistant-series-v1', schemaVersion: 1, exportedAt: '2026-10-04T10:00:00Z',
    templates: [{ uid: templateUid, kind: 'race', headRevisionUid: revisionUid, archived: true }],
    revisions: [{ uid: revisionUid, number: 2, payload: payload('race') }, { uid: copyUid, number: 2, payload: payload('race') }],
  }
  const body = { mutationId: mutation.mutationId, planToken: 'frozen-preview', package: pack }
  await seriesApi.exportLibrary()
  await seriesApi.exportLibrary([])
  await seriesApi.exportLibrary([templateUid])
  await seriesApi.previewImport(pack)
  await seriesApi.importLibrary(body)
  assert.deepEqual(calls.map(call => call.url), ['/series/export', '/series/export', '/series/export', '/series/import/preview', '/series/import'])
  assert.deepEqual(calls[0].body, {})
  assert.deepEqual(calls[1].body, { templateUids: [] })
  assert.deepEqual(calls[2].body, { templateUids: [templateUid] })
  assert.deepEqual(calls[3].body, { package: pack })
  assert.deepEqual(calls[4].body, body)
})
