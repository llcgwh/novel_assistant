import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'
const { moveSelectIndex, matchSelectOption, selectMenuBox } =
  await compileSources()('utils/select')
const options = [
  { value: '', label: '请选择', disabled: true },
  { value: 7, label: 'Alpha' },
  { value: 8, label: 'Alpine', disabled: true },
  { value: 9, label: 'Amber' },
  { value: 10, label: 'Beta' },
]
test('keyboard navigation skips disabled rows and wraps in either direction', () => {
  assert.equal(moveSelectIndex(options, -1, 1), 1)
  assert.equal(moveSelectIndex(options, 1, 1), 3)
  assert.equal(moveSelectIndex(options, 4, 1), 1)
  assert.equal(moveSelectIndex(options, 1, -1), 4)
  assert.equal(moveSelectIndex(options, -1, -1), 4)
})
test('empty or entirely disabled options cannot be selected', () => {
  assert.equal(moveSelectIndex([], -1, 1), -1)
  assert.equal(moveSelectIndex([options[0]], -1, -1), -1)
  assert.equal(matchSelectOption([], 'a', -1), -1)
  assert.equal(matchSelectOption(options, 'Alpine', 1), -1)
})
test('typing prefixes and repeated letters cycle enabled candidates', () => {
  assert.equal(matchSelectOption(options, 'a', 1), 3)
  assert.equal(matchSelectOption(options, 'aa', 3), 1)
  assert.equal(matchSelectOption(options, 'am', 3), 3)
  assert.equal(matchSelectOption(options, 'be', 1), 4)
})
test('a dropdown near the mobile right edge stays within the viewport', () => {
  const box = selectMenuBox(
    { left: 220, top: 50, bottom: 90, width: 150 },
    390,
    844,
    5,
  )
  assert.equal(box.left + box.width, 378)
  assert.equal(box.top, 96)
  assert.ok(box.height >= 200)
})
test('a dropdown near the bottom opens above and bounds a long list', () => {
  const box = selectMenuBox(
    { left: 24, top: 610, bottom: 650, width: 280 },
    390,
    700,
    100,
  )
  assert.equal(box.top + box.height, 604)
  assert.ok(box.height <= 334)
  assert.equal(box.width, 280)
})
test('small viewports constrain width and available height', () => {
  const box = selectMenuBox(
    { left: 4, top: 80, bottom: 120, width: 340 },
    320,
    240,
    9,
  )
  assert.equal(box.width, 296)
  assert.equal(box.left, 12)
  assert.ok(box.top + box.height <= 228)
  assert.ok(box.height > 0)
})
