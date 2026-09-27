import type { DocNode } from '@/types/writing'
import { documentText } from '@/utils/writing'

export interface TextChange {
  kind: 'equal' | 'add' | 'remove'
  text: string
}
export interface HistoryChange {
  key: string
  currentIndex: number
  historicalIndex: number
  current?: DocNode
  historical?: DocNode
  currentFingerprint: string
  historicalFingerprint: string
  kind: 'same' | 'changed' | 'added' | 'removed'
  moved: boolean
}
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
function identities(node: DocNode): string[] {
  if (typeof node.attrs?.id === 'string' && node.attrs.id)
    return [`id:${node.attrs.id}`]
  // Lists have anchored paragraphs but normally no root ID of their own.
  const descendants = blockIds(node)
  return descendants.length
    ? descendants.map((id) => `child:${id}`)
    : [`text:${JSON.stringify(node)}`]
}

/** Compare stable top-level blocks; nested lists/quotes stay intact as one unit. */
export function compareHistory(
  current: DocNode,
  historical: DocNode,
): HistoryChange[] {
  const now = current.content || [],
    before = historical.content || []
  const used = new Set<number>()
  const byIdentity = new Map<string, number[]>()
  const cursors = new Map<string, number>()
  before.forEach((node, index) => {
    for (const key of identities(node)) {
      if (!byIdentity.has(key)) byIdentity.set(key, [])
      byIdentity.get(key)!.push(index)
    }
  })
  const pairs = now.map((node, index) => {
    let oldIndex = -1
    for (const key of identities(node)) {
      const candidates = byIdentity.get(key) || []
      let offset = cursors.get(key) || 0
      while (offset < candidates.length && used.has(candidates[offset]))
        offset++
      cursors.set(key, offset)
      if (offset < candidates.length) {
        oldIndex = candidates[offset]
        break
      }
    }
    if (oldIndex >= 0) used.add(oldIndex)
    return { index, oldIndex }
  })
  const common = pairs.filter((row) => row.oldIndex >= 0)
  const oldOrder = [...common].sort((a, b) => a.oldIndex - b.oldIndex)
  const ranks = new Map(oldOrder.map((row, i) => [row.index, i]))
  const currentRanks = new Map(common.map((row, i) => [row.index, i]))
  const rows: HistoryChange[] = pairs.map(({ index, oldIndex }) => ({
    key: `current-${index}`,
    currentIndex: index,
    historicalIndex: oldIndex,
    current: now[index],
    historical: oldIndex < 0 ? undefined : before[oldIndex],
    currentFingerprint: JSON.stringify(now[index]),
    historicalFingerprint: oldIndex < 0 ? '' : JSON.stringify(before[oldIndex]),
    kind:
      oldIndex < 0
        ? 'added'
        : JSON.stringify(now[index]) === JSON.stringify(before[oldIndex])
          ? 'same'
          : 'changed',
    moved: oldIndex >= 0 && ranks.get(index) !== currentRanks.get(index),
  }))
  before.forEach((node, index) => {
    if (!used.has(index))
      rows.push({
        key: `historical-${index}`,
        currentIndex: -1,
        historicalIndex: index,
        historical: node,
        currentFingerprint: '',
        historicalFingerprint: JSON.stringify(node),
        kind: 'removed',
        moved: false,
      })
  })
  return rows
}

/** Bounded character diff: long replacements use a clear coarse middle span. */
export function diffText(before: string, after: string): TextChange[] {
  const old = Array.from(before),
    now = Array.from(after)
  let head = 0,
    tail = 0
  while (head < old.length && head < now.length && old[head] === now[head])
    head++
  while (
    tail < old.length - head &&
    tail < now.length - head &&
    old[old.length - 1 - tail] === now[now.length - 1 - tail]
  )
    tail++
  const a = old.slice(head, old.length - tail),
    b = now.slice(head, now.length - tail)
  const result: TextChange[] = []
  const add = (kind: TextChange['kind'], text: string) => {
    if (!text) return
    const last = result.at(-1)
    if (last?.kind === kind) last.text += text
    else result.push({ kind, text })
  }
  add('equal', old.slice(0, head).join(''))
  if (!a.length || !b.length || a.length * b.length > 160_000) {
    add('remove', a.join(''))
    add('add', b.join(''))
  } else {
    const width = b.length + 1
    const lengths = new Uint32Array((a.length + 1) * width)
    for (let i = a.length - 1; i >= 0; i--)
      for (let j = b.length - 1; j >= 0; j--)
        lengths[i * width + j] =
          a[i] === b[j]
            ? lengths[(i + 1) * width + j + 1] + 1
            : Math.max(lengths[(i + 1) * width + j], lengths[i * width + j + 1])
    let i = 0,
      j = 0
    while (i < a.length || j < b.length) {
      if (i < a.length && j < b.length && a[i] === b[j]) {
        add('equal', a[i++])
        j++
      } else if (
        j < b.length &&
        (i === a.length ||
          lengths[i * width + j + 1] > lengths[(i + 1) * width + j])
      )
        add('add', b[j++])
      else add('remove', a[i++])
    }
  }
  add('equal', tail ? old.slice(old.length - tail).join('') : '')
  return result
}

export const blockText = (node?: DocNode) => (node ? documentText(node) : '')
export function blockIds(node: DocNode): string[] {
  return [
    typeof node.attrs?.id === 'string' ? node.attrs.id : '',
    ...(node.content || []).flatMap(blockIds),
  ].filter(Boolean)
}

export function adoptHistoricalBlock(
  current: DocNode,
  historical: DocNode,
  selected: HistoryChange,
): DocNode {
  const row = compareHistory(current, historical).find(
    (row) => row.key === selected.key,
  )
  if (!row || JSON.stringify(row) !== JSON.stringify(selected))
    throw Error('正文或历史选择已变化，请重新检查差异')
  const result = copy(current),
    nodes = (result.content ||= [])
  if (row.historical) {
    const kept = nodes.filter((_, index) => index !== row.currentIndex)
    const occupied = new Set(kept.flatMap(blockIds))
    if (blockIds(row.historical).some((id) => occupied.has(id)))
      throw Error(
        '该历史段落的部分内容已移动到别处，请通过整章比较处理结构变化',
      )
    const restored = copy(row.historical)
    if (row.currentIndex >= 0) nodes[row.currentIndex] = restored
    else {
      const old = historical.content || []
      const positions = new Map<string, number>()
      nodes.forEach((node, index) => {
        for (const key of identities(node))
          if (!positions.has(key)) positions.set(key, index)
      })
      const positionOf = (node: DocNode) => {
        for (const key of identities(node)) {
          const found = positions.get(key)
          if (found !== undefined) return found
        }
        return -1
      }
      let at = -1
      for (let i = row.historicalIndex + 1; i < old.length && at < 0; i++)
        at = positionOf(old[i])
      if (at < 0) {
        for (let i = row.historicalIndex - 1; i >= 0 && at < 0; i--) {
          const previous = positionOf(old[i])
          if (previous >= 0) at = previous + 1
        }
      }
      nodes.splice(at < 0 ? nodes.length : at, 0, restored)
    }
  } else nodes.splice(row.currentIndex, 1)
  if (!nodes.length)
    nodes.push({ type: 'paragraph', attrs: { id: crypto.randomUUID() } })
  return result
}
