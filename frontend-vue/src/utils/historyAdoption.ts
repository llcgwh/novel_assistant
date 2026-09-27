import type { Chapter, DocNode } from '@/types/writing'
import { adoptHistoricalBlock, type HistoryChange } from '@/utils/historyDiff'

interface HistoryWriter {
  current: Chapter | null
  flush: (checkpoint?: boolean) => Promise<boolean>
  changed: () => void
}

/** Checkpoint the actual current manuscript before any selective replacement. */
export async function adoptHistorySelection(
  writer: HistoryWriter,
  historical: DocNode,
  row: HistoryChange,
  ensureActive: () => void,
) {
  ensureActive()
  if (!writer.current) throw Error('请先打开章节')
  const uid = writer.current.uid,
    fingerprint = JSON.stringify(writer.current.doc)
  const next = adoptHistoricalBlock(writer.current.doc, historical, row)
  if (!(await writer.flush(true)))
    throw Error('请先保存当前修改或处理冲突，再采用历史段落')
  ensureActive()
  if (
    !writer.current ||
    writer.current.uid !== uid ||
    JSON.stringify(writer.current.doc) !== fingerprint
  )
    throw Error('当前正文已变化，请重新检查差异')
  writer.current.doc = next
  writer.changed()
  if (!(await writer.flush(true)))
    throw Error('采用的段落仍保留在本机稿中，请处理保存状态后重试保存')
  ensureActive()
}

export async function restoreHistorySnapshot(
  writer: HistoryWriter,
  historical: Chapter,
  originalChapterUid: string,
  ensureActive: () => void,
) {
  ensureActive()
  const checkChapter = () => {
    if (
      !originalChapterUid ||
      historical.uid !== originalChapterUid ||
      writer.current?.uid !== originalChapterUid
    )
      throw Error('章节已切换，请重新打开历史')
  }
  checkChapter()
  const old: Chapter = JSON.parse(JSON.stringify(historical))
  if (!(await writer.flush(true)))
    throw Error('请先保存当前修改或处理冲突，再恢复历史版本')
  ensureActive()
  checkChapter()
  Object.assign(writer.current!, {
    doc: old.doc,
    links: old.links,
    notes: old.notes,
    summary: old.summary,
    title: old.title,
  })
  writer.changed()
  if (!(await writer.flush(true)))
    throw Error('恢复内容尚未保存，请处理保存状态')
  ensureActive()
}
