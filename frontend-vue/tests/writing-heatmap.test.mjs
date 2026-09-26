import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'
const load = compileSources()
const { writingHeatmap, heatmapScale, heatmapLevel, heatmapFocusIndex } =
  await load('utils/writingHeatmap')
const record = (overrides = {}) => ({
  uid: 'session-a',
  sequence: 1,
  date: '2026-09-27',
  chapterUid: 'chapter-a',
  typed: 100,
  pasted: 20,
  net: 90,
  activeSeconds: 60,
  peak: 20,
  ...overrides,
})

test('year calendar has 365 contiguous local dates, fills missing days, and aligns weekdays', () => {
  const calendar = writingHeatmap([], new Date(2026, 8, 27, 0, 5))
  assert.equal(calendar.days.length, 365)
  assert.equal(calendar.days[0].date, '2025-09-28')
  assert.equal(calendar.days[364].date, '2026-09-27')
  assert.equal(calendar.days[364].row, 7)
  assert.equal(new Set(calendar.days.map((day) => day.date)).size, 365)
  assert(calendar.days.every((day) => day.net === 0 && day.sessions === 0))
  assert(
    calendar.months.every(
      (month) => month.column >= 1 && month.column <= calendar.columns,
    ),
  )
})

test('daily aggregation uses newest session snapshot, sums independent sessions, and preserves negative edits', () => {
  const sessions = [
    record(),
    record({ sequence: 2, net: -25, typed: 150 }),
    record({ sequence: 0, net: 999 }),
    record({ uid: 'session-b', chapterUid: 'chapter-b', net: 10 }),
    record({ uid: 'old', date: '2025-01-01', net: 10000 }),
  ]
  const { days } = writingHeatmap(sessions, new Date(2026, 8, 27))
  assert.deepEqual(days[364], {
    date: '2026-09-27',
    net: -15,
    typed: 250,
    pasted: 40,
    activeSeconds: 120,
    sessions: 2,
    chapters: 2,
    index: 364,
    column: 53,
    row: 7,
  })
  assert.equal(
    days.reduce((n, day) => n + day.net, 0),
    -15,
  )
})

test('color bins separate reductions and zero from positive progress and honor the goal', () => {
  assert.deepEqual(
    [-3, 0, 1, 499, 500, 999, 1000, 1999, 2000, 10000].map((n) =>
      heatmapLevel(n, 2000),
    ),
    [-1, 0, 1, 1, 2, 2, 3, 3, 4, 4],
  )
  assert.equal(heatmapScale(0).target, 2000)
  assert.equal(heatmapScale(NaN).target, 2000)
  assert.equal(heatmapLevel(1, 1), 4)
})

test('calendar spans leap day and local DST boundaries without skipping or duplicating dates', () => {
  const { days } = writingHeatmap([], new Date(2024, 10, 15, 23, 59))
  assert(days.some((day) => day.date === '2024-02-29'))
  assert.equal(new Set(days.map((day) => day.date)).size, 365)
  for (let i = 1; i < days.length; i++) {
    assert.equal(
      Date.parse(days[i].date) - Date.parse(days[i - 1].date),
      86400000,
    )
  }
})

test('keyboard movement follows the visual week grid and remains inside the visible date range', () => {
  const { days } = writingHeatmap([], new Date(2026, 8, 27))
  assert.equal(heatmapFocusIndex(364, 'ArrowLeft', days), 357)
  assert.equal(heatmapFocusIndex(364, 'ArrowRight', days), 364)
  assert.equal(heatmapFocusIndex(0, 'ArrowUp', days), 0)
  assert.equal(heatmapFocusIndex(364, 'Home', days), 358)
  assert.equal(heatmapFocusIndex(361, 'End', days), 364)
  assert.equal(heatmapFocusIndex(361, 'Tab', days), 361)
})
