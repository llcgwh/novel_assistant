import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'
const load = compileSources()
const { characterCountWithPunctuation, currentVolumeWords } = await load(
  'utils/writingStatistics',
)

test('supplementary character count includes punctuation and Unicode code points without whitespace', () => {
  assert.equal(characterCountWithPunctuation('我，来了！\n A B\t😀𠮷'), 9)
  assert.equal(characterCountWithPunctuation(' \n\t\u3000'), 0)
})

test('current volume totals live edits, isolates other volumes, and excludes trash', () => {
  const chapters = [
    { uid: 'a', volumeId: 'one', wordCount: 100, deleted: false },
    { uid: 'b', volumeId: 'one', wordCount: 200, deleted: false },
    { uid: 'c', volumeId: 'one', wordCount: 900, deleted: true },
    { uid: 'd', volumeId: 'two', wordCount: 500, deleted: false },
    { uid: 'e', volumeId: null, wordCount: 30, deleted: false },
  ]
  assert.equal(
    currentVolumeWords(chapters, { ...chapters[0], wordCount: 125 }),
    325,
  )
  assert.equal(currentVolumeWords(chapters, chapters[4]), 30)
  assert.equal(currentVolumeWords(chapters, null), null)
})
