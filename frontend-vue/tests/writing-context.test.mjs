import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'
const load = compileSources()
const {
  foreshadowPlans,
  setPlanDone,
  chapterTasks,
  addRevisionNote,
  timelineNeighbors,
  linkedResources,
  relatedResources,
} = await load('utils/writingContext')
const resource = (id, type, raw = {}) => ({
  id,
  type,
  name: `${type}-${id}`,
  detail: '',
  raw,
})

test('planned revelations do not become recorded revelations until the author completes the task', () => {
  const link = {
    uid: 'stable-link',
    type: 'foreshadows',
    targetId: 4,
    title: '蓝色火漆',
    role: 'reference',
    plannedRole: 'revealed',
  }
  assert.equal(foreshadowPlans([link])[0].done, false)
  let links = setPlanDone([link], link.uid, true)
  assert.equal(links[0].role, 'revealed')
  assert.equal(foreshadowPlans(links)[0].done, true)
  links = setPlanDone(links, link.uid, false)
  assert.equal(links[0].role, 'reference')
})

test('plan state stays with its link when resource and link identities are remapped by restore or merge', () => {
  const restored = [
    {
      uid: 'link-uuid',
      type: 'foreshadows',
      targetId: 9001,
      title: '潮汐',
      role: 'reference',
      plannedRole: 'developed',
    },
  ]
  assert.equal(setPlanDone(restored, 'link-uuid', true)[0].role, 'developed')
  const merged = [{ ...restored[0], uid: 'new-link-uuid' }]
  assert.equal(foreshadowPlans(merged)[0].uid, 'new-link-uuid')
  assert.equal(setPlanDone(merged, 'new-link-uuid', true)[0].role, 'developed')
})

test('revision notes preserve the selected source and stable paragraph identity without becoming manuscript text', () => {
  const notes = addRevisionNote(
    '已有笔记',
    '核对父亲台词',
    '不要出海。\n潮退了。',
    '雾中的信',
    'paragraph-id',
  )
  assert.match(notes, /已有笔记/)
  assert.match(notes, /来源：雾中的信 · 段落 paragraph-id/)
  assert.match(notes, /原文：不要出海。\n  潮退了。/)
  assert.equal(chapterTasks(notes)[0].text, '修订：核对父亲台词')
})

test('context follows explicit paragraph or chapter links and existing timeline relations', () => {
  const hero = resource(1, 'characters'),
    other = resource(2, 'characters'),
    scene = resource(1, 'scenes')
  const event = resource(5, 'timeline', {
    characters: [{ id: 2 }],
    scenes: [{ id: 1 }],
  })
  const all = [hero, other, scene, event]
  const links = [
    { type: 'characters', targetId: 1, blockId: 'p1' },
    { type: 'characters', targetId: 2, blockId: 'p2' },
    { type: 'timeline', targetId: 5, blockId: '' },
  ]
  assert.deepEqual(
    linkedResources(all, links, undefined, 'p1').map((r) => r.name),
    ['characters-1', 'timeline-5'],
  )
  assert.deepEqual(relatedResources(all, event, 'scenes', 'scenes'), [scene])
  assert.deepEqual(relatedResources(all, event, 'characters', 'characters'), [
    other,
  ])
})

test('adjacent events follow story order independently of resource loading or chapter narration order', () => {
  const all = [
    resource(30, 'timeline', { realOrder: 3 }),
    resource(10, 'timeline', { realOrder: 1 }),
    resource(20, 'timeline', { realOrder: 2 }),
    resource(1, 'characters'),
  ]
  const neighbors = timelineNeighbors(all, 20)
  assert.equal(neighbors.previous.id, 10)
  assert.equal(neighbors.next.id, 30)
  assert.equal(timelineNeighbors(all, 10).previous, undefined)
  assert.equal(timelineNeighbors(all, 999).next, undefined)
})
