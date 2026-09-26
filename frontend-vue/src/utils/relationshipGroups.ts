import type { RelationshipGroup } from '@/types/relationship'

/** Flat display rows preserve hierarchy without mutating Pinia records or recursing forever on old cycles. */
export function flattenGroupTree(groups: RelationshipGroup[]) {
  const ids = new Set(groups.map(group => group.id))
  const visited = new Set<number>()
  const result: (RelationshipGroup & { _depth: number; children: RelationshipGroup[] })[] = []
  function visit(group: RelationshipGroup, depth: number) {
    if (visited.has(group.id)) return
    visited.add(group.id)
    const children = groups.filter(child => child.parentGroupId === group.id)
    result.push({ ...group, _depth: depth, children })
    children.forEach(child => visit(child, depth + 1))
  }
  groups.filter(group => !group.parentGroupId || !ids.has(group.parentGroupId)).forEach(group => visit(group, 0))
  groups.forEach(group => visit(group, 0))
  return result
}
