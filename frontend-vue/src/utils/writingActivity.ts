import type { SessionRecord, WritingDate } from '@/types/writing'
import { localDate } from '@/utils/writing'

export function writingDate(now = new Date()): WritingDate {
  return { date: localDate(now), timezoneOffsetMinutes: now.getTimezoneOffset() }
}

/** A late acknowledgement cannot erase a newer cumulative local snapshot. */
export function clearUploadedSession(storage: Pick<Storage, 'getItem' | 'removeItem'>, key: string, uploaded: SessionRecord) {
  const saved = JSON.parse(storage.getItem(key) || 'null') as SessionRecord | null
  if (saved?.uid === uploaded.uid && saved.sequence <= uploaded.sequence)
    storage.removeItem(key)
}
