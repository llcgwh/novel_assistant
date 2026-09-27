import type { ChapterAnchor, RevisionCategory } from '@/types/writingDesk'
import { revisionCategories } from '@/utils/revisionDesk'

export interface ReaderNoteDraft {
  uid: string
  createdAt: string
  body: string
  category: RevisionCategory
  anchor: Pick<ChapterAnchor, 'blockId' | 'excerpt'> | null
}
export function readerDraftKey(
  novelId: number,
  bookUid: string,
  chapterUid: string,
) {
  return `ink-reader-note:${novelId}:${encodeURIComponent(bookUid)}:${encodeURIComponent(chapterUid)}`
}
export function decodeReaderDraft(raw: string): ReaderNoteDraft | undefined {
  const value = JSON.parse(raw)
  if (
    !value ||
    typeof value.body !== 'string' ||
    !value.body.trim() ||
    value.body.length > 4000 ||
    typeof value.uid !== 'string' ||
    value.uid.length > 100 ||
    typeof value.createdAt !== 'string' ||
    !Number.isFinite(Date.parse(value.createdAt)) ||
    !revisionCategories.some((category) => category.value === value.category)
  )
    return undefined
  return {
    uid: value.uid,
    createdAt: value.createdAt,
    body: value.body,
    category: value.category,
    anchor:
      value.anchor &&
      typeof value.anchor.blockId === 'string' &&
      typeof value.anchor.excerpt === 'string'
        ? {
            blockId: value.anchor.blockId.slice(0, 100),
            excerpt: value.anchor.excerpt.slice(0, 1000),
          }
        : null,
  }
}
