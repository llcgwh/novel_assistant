export type ReaderMode = 'chapter' | 'continuous' | 'paged'
export interface ReaderLocation {
  chapterUid: string
  blockId?: string
}

export function pageCountForWidth(
  scrollWidth: number,
  width: number,
  gap: number,
) {
  return Math.max(
    1,
    Math.ceil((scrollWidth + gap - 1) / Math.max(1, width + gap)),
  )
}
export function pageForOffset(
  offset: number,
  width: number,
  gap: number,
  count: number,
) {
  return Math.max(
    0,
    Math.min(count - 1, Math.floor((offset + 1) / Math.max(1, width + gap))),
  )
}
export function boundedReadingWindow(
  center: number,
  length: number,
  radius = 2,
) {
  return {
    start: Math.max(0, center - radius),
    end: Math.min(length, center + radius + 1),
  }
}
export function ignoresPageKey(target: EventTarget | null) {
  return (
    target instanceof Element &&
    Boolean(
      target.closest(
        'input,textarea,select,button,[contenteditable="true"],[role="combobox"],[role="dialog"]',
      ),
    )
  )
}
