import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'
import { compileSources } from './helpers/load-source.mjs'
const load = compileSources()
const heatmap = await load('utils/writingHeatmap')
const { writingHeatmap, heatmapScale, heatmapLevel, heatmapFocusIndex } = heatmap
const writing = await load('utils/writing')
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
const daily = (overrides = {}) => ({
  date: '2026-09-27',
  goal: 2000,
  revisionSaves: 0,
  finalTransitions: 0,
  completedChapterUids: [],
  focusSeconds: 0,
  focusCompleted: 0,
  ...overrides,
})
const stats = (...days) => ({ schemaVersion: 1, version: 1, days })

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
    goal: null,
    revisionSaves: 0,
    finalTransitions: 0,
    completedChapters: 0,
    focusSeconds: 0,
    focusCompleted: 0,
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
  assert.equal(heatmapScale(null).target, 2000)
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

test('historical goals remain per day and missing goals are never backfilled', () => {
  const calendar = writingHeatmap(
    [record({ date: '2026-09-26', net: 1000 }), record({ uid: 'today', net: 1000 })],
    new Date(2026, 8, 27),
    stats(daily({ date: '2026-09-26', goal: 1000 }), daily({ goal: 4000 })),
  )
  assert.equal(calendar.days[362].goal, null)
  assert.equal(calendar.days[363].goal, 1000)
  assert.equal(calendar.days[364].goal, 4000)
  assert.equal(heatmapLevel(calendar.days[363].net, calendar.days[363].goal), 4)
  assert.equal(heatmapLevel(calendar.days[364].net, calendar.days[364].goal), 2)
  const cleared = writingHeatmap([], new Date(2026, 8, 27), stats(daily({ goal: 0 })))
  assert.equal(cleared.days[364].goal, 0)
  assert.equal(cleared.days[363].goal, null)
})

test('revision and focus activity appear without sessions and completed chapters are unique', () => {
  const { days } = writingHeatmap(
    [],
    new Date(2026, 8, 27),
    stats(daily({
      goal: null,
      revisionSaves: 7,
      finalTransitions: 4,
      completedChapterUids: ['one', 'one', 'two', 'two'],
      focusSeconds: 3100,
      focusCompleted: 2,
    })),
  )
  assert.equal(days[364].net, 0)
  assert.equal(days[364].sessions, 0)
  assert.equal(days[364].goal, null)
  assert.equal(days[364].revisionSaves, 7)
  assert.equal(days[364].finalTransitions, 4)
  assert.equal(days[364].completedChapters, 2)
  assert.equal(days[364].focusSeconds, 3100)
  assert.equal(days[364].focusCompleted, 2)
  assert.equal(days[364].activeSeconds, 0)
})

test('daily snapshots do not add repeated rows and malformed numeric values remain displayable', () => {
  const { days } = writingHeatmap(
    [], new Date(2026, 8, 27),
    stats(daily({ revisionSaves: 8 }), daily({
      goal: NaN, revisionSaves: 2, finalTransitions: -1,
      completedChapterUids: ['one', '', 'one'], focusSeconds: Infinity, focusCompleted: -3,
    })),
  )
  assert.equal(days[364].revisionSaves, 2)
  assert.equal(days[364].goal, null)
  assert.equal(days[364].completedChapters, 1)
  assert.equal(days[364].finalTransitions, 0)
  assert.equal(days[364].focusSeconds, 0)
  assert.equal(days[364].focusCompleted, 0)
})

// Mount the actual SFC with Vue's renderer so props, keyboard handlers and timers
// run together without replacing the component's implementation with a fixture.
const source = readFileSync(new URL('../src/components/writing/WritingHeatmap.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
const script = compileScript(descriptor, { id: 'heatmap-test', inlineTemplate: true })
const compiled = ts.transpileModule(script.content, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText
const module = { exports: {} }
new Function('require', 'module', 'exports', compiled)((name) => {
  if (name === 'vue') return Vue
  if (name === '@/utils/writingHeatmap') return heatmap
  if (name === '@/utils/writing') return writing
  throw Error(`Unexpected component dependency: ${name}`)
}, module, module.exports)
const Component = module.exports.default

function mountHeatmap(t, props) {
  const listeners = new Map()
  const previousDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
  Object.defineProperty(globalThis, 'document', { configurable: true, value: {
    addEventListener: (event, listener) => listeners.set(event, listener),
    removeEventListener: (event, listener) => {
      if (listeners.get(event) === listener) listeners.delete(event)
    },
  } })
  const node = (type, text = '') => ({
    type, text, children: [], props: {}, parent: null, scrollWidth: 900, scrollLeft: 0,
    querySelector(selector) {
      const index = Number(selector.match(/data-day-index="(\d+)"/)[1])
      return find(this, (candidate) => candidate.props['data-day-index'] === index)
    },
    focus() { this.focused = true; this.props.onFocus?.() },
    scrollIntoView() { this.scrolledIntoView = true },
  })
  const renderer = Vue.createRenderer({
    createElement: node,
    createText: (text) => node('#text', text),
    createComment: (text) => node('#comment', text),
    setText: (element, text) => { element.text = text },
    setElementText: (element, text) => { element.text = text; element.children = [] },
    patchProp: (element, key, _previous, value) => { element.props[key] = value },
    parentNode: (element) => element.parent,
    nextSibling: (element) => element.parent?.children[element.parent.children.indexOf(element) + 1] ?? null,
    insert(element, parent, anchor = null) {
      if (element.parent) element.parent.children.splice(element.parent.children.indexOf(element), 1)
      element.parent = parent
      const index = anchor ? parent.children.indexOf(anchor) : -1
      if (index < 0) parent.children.push(element)
      else parent.children.splice(index, 0, element)
    },
    remove(element) {
      if (element.parent) element.parent.children.splice(element.parent.children.indexOf(element), 1)
      element.parent = null
    },
  })
  const root = node('root')
  const state = Vue.reactive(props)
  const app = renderer.createApp({ render: () => Vue.h(Component, state) })
  app.mount(root)
  t.after(() => {
    app.unmount()
    assert.equal(listeners.size, 0)
    if (previousDocument) Object.defineProperty(globalThis, 'document', previousDocument)
    else delete globalThis.document
  })
  return { root, state, listeners, app }
}
function find(root, predicate) {
  if (predicate(root)) return root
  for (const child of root.children) {
    const match = find(child, predicate)
    if (match) return match
  }
}
const textOf = (root) => root.text + root.children.map(textOf).join('')
const cell = (root, index) => find(root, (node) => node.props['data-day-index'] === index)
const detail = (root) => find(root, (node) => node.props.role === 'status')

test('component uses daily goals, updates accepted stats, and keeps revision/focus distinct from net/active', async (t) => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval'], now: new Date(2026, 8, 27, 12).getTime() })
  const { root, state } = mountHeatmap(t, {
    sessions: [record({ net: 1000 })], dailyGoal: 1000,
    stats: stats(daily({ goal: 4000, revisionSaves: 5, finalTransitions: 2, completedChapterUids: ['one', 'one'], focusSeconds: 1500, focusCompleted: 1 })),
  })
  assert.equal(cell(root, 364).props['data-level'], 2)
  assert.match(textOf(detail(root)), /每日目标 · 4,000 字/)
  assert.match(textOf(detail(root)), /修订保存5 次/)
  assert.match(textOf(detail(root)), /定稿章数1 章/)
  assert.match(textOf(detail(root)), /完成专注1 段/)
  assert.match(textOf(detail(root)), /专注时长25 分 0 秒/)
  assert.match(textOf(detail(root)), /活跃时长1 分 0 秒/)
  assert.match(textOf(root), /净增是正文字数变化，不等于修改字数/)
  state.dailyGoal = 8000
  await Vue.nextTick()
  assert.equal(cell(root, 364).props['data-level'], 2)
  cell(root, 363).props.onClick()
  await Vue.nextTick()
  assert.match(textOf(detail(root)), /未记录目标/)
  state.stats = stats(daily({ goal: 1000, revisionSaves: 6 }))
  cell(root, 364).props.onClick()
  await Vue.nextTick()
  assert.equal(cell(root, 364).props['data-level'], 4)
  assert.match(textOf(detail(root)), /修订保存6 次/)
})

test('component keyboard selection and calendar update across midnight and tab restoration', async (t) => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval'], now: new Date(2026, 8, 27, 23, 59, 45).getTime() })
  const { root, listeners } = mountHeatmap(t, { sessions: [], stats: null })
  let prevented = false
  cell(root, 364).props.onKeydown({ key: 'ArrowLeft', preventDefault: () => { prevented = true } })
  await Vue.nextTick()
  await Vue.nextTick()
  assert.equal(prevented, true)
  assert.equal(cell(root, 357).props.tabindex, 0)
  assert.equal(cell(root, 357).focused, true)
  assert.match(textOf(detail(root)), /2026\.09\.20/)
  t.mock.timers.tick(30_000)
  await Vue.nextTick()
  assert.match(cell(root, 364).props['aria-label'], /^2026-09-28，今天/)
  assert.equal(cell(root, 356).props.tabindex, 0)
  assert.match(textOf(detail(root)), /2026\.09\.20/)
  find(root, (node) => node.props.class === 'heatmap-today').props.onClick()
  await Vue.nextTick()
  await Vue.nextTick()
  assert.match(textOf(detail(root)), /2026\.09\.28/)
  t.mock.timers.setTime(new Date(2026, 8, 29, 9).getTime())
  listeners.get('visibilitychange')()
  await Vue.nextTick()
  assert.match(textOf(detail(root)), /2026\.09\.29/)
  assert.equal(cell(root, 364).props.tabindex, 0)
})
