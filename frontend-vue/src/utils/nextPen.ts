import type { Chapter } from '@/types/writing'
import type { NextPen } from '@/types/writingDesk'
import { blockList } from '@/utils/writing'

export function nextPenAnchor(chapter: Chapter, activeBlock: string) {
  const blocks = blockList(chapter.doc)
  const block =
    blocks.find((row) => row.id === activeBlock) ||
    blocks.filter((row) => row.text.trim()).at(-1) ||
    blocks.at(-1)
  return {
    chapterUid: chapter.uid,
    blockId: block?.id || '',
    excerpt: (block?.text.trim() || '').slice(-180),
  }
}

export function nextPenHasContent(
  note: Pick<NextPen, 'nextScene' | 'question' | 'opening'>,
) {
  return !!(
    note.nextScene.trim() ||
    note.question.trim() ||
    note.opening.trim()
  )
}

export function nextPenSignature(note: NextPen | null | undefined) {
  return note
    ? JSON.stringify([
        note.chapterUid,
        note.blockId,
        note.excerpt,
        note.nextScene,
        note.question,
        note.opening,
        note.updatedAt,
      ])
    : ''
}

export interface NextPenDraft {
  note: NextPen
  baseline: string
}

export function readNextPenDraft(raw: string | null): NextPenDraft | null {
  if (!raw) return null
  try {
    const draft = JSON.parse(raw)
    if (!draft || typeof draft.baseline !== 'string' || !draft.note) return null
    const fields = [
      'chapterUid',
      'blockId',
      'excerpt',
      'nextScene',
      'question',
      'opening',
      'updatedAt',
    ]
    if (!fields.every((key) => typeof draft.note[key] === 'string')) return null
    if (!draft.note.chapterUid || !nextPenHasContent(draft.note)) return null
    return { note: draft.note, baseline: draft.baseline }
  } catch {
    return null
  }
}
