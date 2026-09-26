import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'
const { parseAppearance, parseAppearanceBackup } = await compileSources()('utils/appearance')
test('appearance migration accepts only known fields and validates ranges and URLs', () => {
  assert.deepEqual(parseAppearance({ backgroundImage: '', backgroundOpacity: .4, extra: 'discard' }), { backgroundImage: '', backgroundOpacity: .4 })
  for (const backgroundImage of ['javascript:alert(1)', 'data:image/svg+xml,<svg/>', 'file:///secret']) assert.throws(() => parseAppearance({ backgroundImage, backgroundOpacity: .4 }))
  for (const backgroundOpacity of [-1, 2, '0.4', NaN]) assert.throws(() => parseAppearance({ backgroundImage: '', backgroundOpacity }))
})
test('appearance migration validates file format and portable map selection', () => {
  const settings = { backgroundImage: '', backgroundOpacity: .55 }
  assert.equal(parseAppearanceBackup({ format: 'novel-appearance-v1', settings, mapBackground: 'none' }).mapBackground, 'none')
  assert.throws(() => parseAppearanceBackup({ format: 'unknown', settings }))
  assert.throws(() => parseAppearanceBackup({ format: 'novel-appearance-v1', settings, mapBackground: '123' }))
})
