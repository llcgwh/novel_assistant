import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'
const { flattenGroupTree } = await compileSources()('utils/relationshipGroups')
test('nested groups remain visible, with stable depths and without mutating store records', () => {
  const groups = [{ id: 3, parentGroupId: 2 }, { id: 1 }, { id: 2, parentGroupId: 1 }, { id: 4, parentGroupId: 99 }]
  const before = structuredClone(groups)
  assert.deepEqual(flattenGroupTree(groups).map(g => [g.id, g._depth]), [[1,0],[2,1],[3,2],[4,0]])
  assert.deepEqual(groups, before)
})
test('historical cycles are displayed once and do not overflow the stack', () => {
  assert.deepEqual(flattenGroupTree([{ id: 1, parentGroupId: 2 }, { id: 2, parentGroupId: 1 }]).map(g => g.id), [1,2])
})
