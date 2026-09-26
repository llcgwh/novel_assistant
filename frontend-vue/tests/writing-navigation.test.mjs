import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'

const { latestChapter } = await compileSources()('utils/writing')

test('overview tolerates legacy date arrays and keeps the latest active chapter', () => {
  const chapters = [
    { uid: 'iso', updatedAt: '2026-09-02T23:59:00' },
    { uid: 'legacy', updatedAt: [2026, 9, 10, 1, 2, 3, 123000000] },
    { uid: 'trash', updatedAt: [2026, 10, 1], deleted: true },
  ]
  assert.equal(latestChapter(chapters).uid, 'legacy')
  assert.deepEqual(
    chapters.map((c) => c.uid),
    ['iso', 'legacy', 'trash'],
  )
})

test('empty or malformed chapter dates cannot break the overview render', () => {
  assert.equal(latestChapter([]), undefined)
  assert.equal(latestChapter([{ deleted: true }]), undefined)
  const chapters = [
    null,
    {},
    ['bad'],
    [2026],
    123,
    'bad',
    '2026-09-10T01:02:03',
  ].map((updatedAt, uid) => ({ uid, updatedAt }))
  assert.equal(latestChapter(chapters).uid, 6)
})
