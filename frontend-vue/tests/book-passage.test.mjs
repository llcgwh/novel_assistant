import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'
const { bookPassage, visibleBookBox } =
  await compileSources()('utils/bookPassage')
const home = { path: '/', params: {} }
const work = (path) => ({ path, params: { novelId: '7' } })
test('a selected book opens and returns with the same identity', () => {
  assert.deepEqual(bookPassage(work('/novel/7/writing'), home), {
    novelId: 7,
    direction: 'open',
  })
  assert.deepEqual(bookPassage(home, work('/novel/7/timeline')), {
    novelId: 7,
    direction: 'close',
  })
})
test('module changes, settings and invalid novels do not trigger a book flight', () => {
  assert.equal(
    bookPassage(work('/novel/7/writing'), work('/novel/7/overview')),
    null,
  )
  assert.equal(bookPassage({ path: '/settings', params: {} }, home), null)
  assert.equal(
    bookPassage({ path: '/novel/no', params: { novelId: 'no' } }, home),
    null,
  )
})
test('offscreen and empty covers use the fallback rather than flying outside the screen', () => {
  assert.equal(
    visibleBookBox({ left: 0, top: 800, width: 120, height: 180 }, 390, 700),
    false,
  )
  assert.equal(
    visibleBookBox({ left: -50, top: 10, width: 120, height: 180 }, 390, 700),
    true,
  )
  assert.equal(
    visibleBookBox({ left: 20, top: 20, width: 0, height: 0 }, 390, 700),
    false,
  )
})
