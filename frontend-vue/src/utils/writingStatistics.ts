import type { Chapter, ChapterMeta } from '@/types/writing'

/** Supplementary character count: punctuation counts, whitespace does not. */
export function characterCountWithPunctuation(text: string) {
  return Array.from(text.replace(/\s/gu, '')).length
}

export function currentVolumeWords(
  chapters: ChapterMeta[],
  current: Chapter | null,
) {
  if (!current) return null
  return chapters
    .filter(
      (chapter) => !chapter.deleted && chapter.volumeId === current.volumeId,
    )
    .reduce(
      (total, chapter) =>
        total +
        (chapter.uid === current.uid ? current.wordCount : chapter.wordCount),
      0,
    )
}
