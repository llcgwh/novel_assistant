import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'
const load = compileSources()
const { request, setCurrentNovelId } = await load('api/request')
const { useFormSave } = await load('composables/useFormSave')

test('a lost response retains the same submission token; success upserts the local cache', async () => {
  globalThis.localStorage = { setItem() {}, removeItem() {} }
  setCurrentNovelId(3)
  const calls = [], list = []
  request.defaults.adapter = async config => {
    calls.push(config)
    if (calls.length === 1) throw new Error('Response lost')
    return { data: { id: 7, name: 'saved' }, status: 200, headers: {}, config }
  }
  const form = useFormSave('characters', () => list)
  await assert.rejects(form.save(undefined, { name: 'first' }, { tagIds: [5] }))
  await form.save(undefined, { name: 'corrected' }, { tagIds: [5] })
  assert.equal(calls[0].headers['Idempotency-Key'], calls[1].headers['Idempotency-Key'])
  assert.deepEqual(JSON.parse(calls[1].data), { data: { name: 'corrected' }, tagIds: [5] })
  assert.equal(list.length, 1)
  await form.save(7, { name: 'saved' })
  assert.equal(list.length, 1)
  assert.equal(calls[2].method, 'put')
  form.reset()
  await form.save(undefined, { name: 'another' })
  assert.notEqual(calls[0].headers['Idempotency-Key'], calls[3].headers['Idempotency-Key'])
})
