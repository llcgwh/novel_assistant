import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'
const load = compileSources()
const {
  ALL_ROUNDS,
  createRevisionRound,
  roundOptions,
  roundProgress,
  roundTasks,
} = await load('utils/revisionRounds')

test('old tasks and explicit ungrouped tasks share a scope without leaking assigned tasks', () => {
  const tasks = [
    { uid: 'legacy', status: 'open' },
    { uid: 'explicit', status: 'done', roundUid: '' },
    { uid: 'one', status: 'done', roundUid: 'round-a' },
    { uid: 'two', status: 'open', roundUid: 'round-b' },
  ]
  assert.deepEqual(
    roundTasks(tasks, '').map((task) => task.uid),
    ['legacy', 'explicit'],
  )
  assert.deepEqual(roundProgress(tasks, ''), {
    total: 2,
    completed: 1,
    percent: 50,
  })
  assert.deepEqual(roundProgress(tasks, 'round-a'), {
    total: 1,
    completed: 1,
    percent: 100,
  })
  assert.deepEqual(roundProgress(tasks, ALL_ROUNDS), {
    total: 4,
    completed: 2,
    percent: 50,
  })
  assert.deepEqual(roundProgress(tasks, 'empty'), {
    total: 0,
    completed: 0,
    percent: 0,
  })
})

test('round options retain archived scopes and stable relative order without changing saved data', () => {
  const rounds = [
    { uid: 'old', title: '旧轮次', status: 'archived' },
    { uid: 'first', title: '对白', status: 'active' },
    { uid: 'second', title: '视角', status: 'active' },
  ]
  assert.deepEqual(roundOptions(rounds), [
    { value: 'first', label: '对白' },
    { value: 'second', label: '视角' },
    { value: 'old', label: '旧轮次 · 已归档' },
  ])
  assert.equal(rounds[0].uid, 'old')
})

test('each new round gets its own identity before the first save attempt', () => {
  const first = createRevisionRound('same-time')
  const second = createRevisionRound('same-time')
  assert.notEqual(first.uid, second.uid)
  assert.equal(first.createdAt, 'same-time')
  assert.equal(first.updatedAt, 'same-time')
  assert.equal(first.status, 'active')
})
