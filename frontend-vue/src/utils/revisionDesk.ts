import type { ChapterMeta, DocNode, Volume } from '@/types/writing'

export const revisionCategories = [
  { value: 'dialogue', label: '对白' },
  { value: 'pov', label: '视角' },
  { value: 'wording', label: '措辞' },
  { value: 'setting', label: '设定' },
  { value: 'plot', label: '情节与伏笔' },
  { value: 'other', label: '其他' },
] as const

export const revisionPriorities = [
  { value: 'normal', label: '普通' },
  { value: 'high', label: '优先处理' },
] as const

export function readingOrder(chapters: ChapterMeta[], volumes: Volume[]) {
  const groups = ['', ...volumes.map((volume) => volume.uid)]
  const active = chapters.filter((chapter) => !chapter.deleted)
  // Preserve the same per-volume order as the chapter tree, including older
  // backups whose position values are absent or duplicated.
  return [
    ...groups.flatMap((uid) =>
      active.filter((chapter) => (chapter.volumeId || '') === uid),
    ),
    ...active.filter((chapter) => !groups.includes(chapter.volumeId || '')),
  ]
}

export function findDocumentBlock(
  doc: DocNode,
  id: string,
): DocNode | undefined {
  if (!id) return undefined
  if (doc.attrs?.id === id) return doc
  for (const child of doc.content || []) {
    const found = findDocumentBlock(child, id)
    if (found) return found
  }
}

export function readingLeafBlockIds(doc: DocNode): Set<string> {
  const ids = new Set<string>()
  function visit(node: DocNode): boolean {
    let hasAnchoredDescendant = false
    for (const child of node.content || []) {
      if (visit(child)) hasAnchoredDescendant = true
    }
    const id = typeof node.attrs?.id === 'string' ? node.attrs.id : ''
    if (id && !hasAnchoredDescendant) ids.add(id)
    return Boolean(id) || hasAnchoredDescendant
  }
  visit(doc)
  return ids
}

export function revisionCategoryLabel(value: string) {
  return (
    revisionCategories.find((category) => category.value === value)?.label ||
    '其他'
  )
}
