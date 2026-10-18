import type { CharacterState, StoryDraft, StoryRecord, StoryState, StorySource } from '@/types/storyState'

export const emptyStoryState = (): StoryState => ({ schemaVersion: 1, states: [], secrets: [] })
export const emptyStorySource = (): StorySource => ({ chapterUid: '', blockId: '', excerpt: '', eventId: null, eventTitle: '', note: '' })
export const knowledgeLabels = { know: '知道', believe: '相信（可能错误）', suspect: '怀疑', unknown: '不知道' }
export const readerLabels = { hidden: '尚未揭示', hinted: '已有暗示', revealed: '已经揭示' }

export function storyOrder(value: string | number): number | null {
  if (String(value).trim() === '') return null
  const n = Number(value)
  if (!Number.isSafeInteger(n) || n < 0) throw Error('顺序须为非负整数；不确定时请留空。')
  return n
}

/** Ties stay visible. Unknown order is never silently promoted to the current state. */
export function characterStateAt(rows: CharacterState[], at: number) {
  return recordsAt(rows, 'storyOrder', at, row => `${row.characterId ?? row.uid}:${row.field}`)
}

export function recordConflicts<T extends StoryRecord>(rows: T[], axis: 'storyOrder' | 'narrativeOrder', subject: (row: T) => string, value: (row: T) => string): Set<string> {
  const groups = new Map<string, T[]>()
  for (const row of rows) {
    if (row.retracted || row[axis] === null) continue
    const key = `${subject(row)}:${row[axis]}`
    groups.set(key, [...(groups.get(key) || []), row])
  }
  return new Set([...groups.values()].filter(group => new Set(group.map(value)).size > 1).flat().map(row => row.uid))
}

export function recordsAt<T extends StoryRecord>(rows: T[], axis: 'storyOrder' | 'narrativeOrder', at: number, subject: (row: T) => string) {
  const eligible = rows.filter(row => !row.retracted && row[axis] !== null && row[axis]! <= at)
  const latest = new Map<string, number>()
  for (const row of eligible) latest.set(subject(row), Math.max(latest.get(subject(row)) ?? -1, row[axis]!))
  return {
    current: eligible.filter(row => row[axis] === latest.get(subject(row))),
    undated: rows.filter(row => !row.retracted && row[axis] === null),
  }
}

export function orderedHistory<T extends StoryRecord>(rows: T[], axis: 'storyOrder' | 'narrativeOrder'): T[] {
  return [...rows].sort((a, b) => {
    if (a[axis] === null && b[axis] !== null) return 1
    if (a[axis] !== null && b[axis] === null) return -1
    return (a[axis] ?? 0) - (b[axis] ?? 0) || a.recordedAt.localeCompare(b.recordedAt)
  })
}

export function appendOnce<T extends { uid: string }>(rows: T[], record: T) {
  const previous = rows.find(row => row.uid === record.uid)
  if (previous && JSON.stringify(previous) !== JSON.stringify(record)) throw Error('这条记录已有不同内容，请重新读取并比较。')
  if (!previous) rows.push(JSON.parse(JSON.stringify(record)))
}

export function readStoryDraft(raw: string, novelId: number): StoryDraft {
  const saved = JSON.parse(raw)
  const fields = ['uid', 'recordedAt', 'secretUid', 'characterId', 'field', 'value', 'title', 'truth', 'belief', 'detail']
  if (!saved || saved.version !== 1 || saved.novelId !== novelId || !['state', 'secret', 'knowledge', 'reader'].includes(saved.kind)
    || fields.some(key => typeof saved[key] !== 'string') || !saved.uid || !Number.isFinite(Date.parse(saved.recordedAt))
    || !Object.prototype.hasOwnProperty.call(knowledgeLabels, saved.knowledgeState) || !Object.prototype.hasOwnProperty.call(readerLabels, saved.readerState)
    || !['string', 'number'].includes(typeof saved.storyOrder) || !['string', 'number'].includes(typeof saved.narrativeOrder)
    || !saved.source || ['chapterUid', 'blockId', 'excerpt', 'eventTitle', 'note'].some(key => typeof saved.source[key] !== 'string')
    || (saved.source.eventId !== null && (!Number.isSafeInteger(saved.source.eventId) || saved.source.eventId <= 0))) throw Error('本机草稿格式无法识别')
  return saved
}
