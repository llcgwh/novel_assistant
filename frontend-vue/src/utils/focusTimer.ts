import type { FocusReceipt } from '@/types/writing'
import { localDate } from '@/utils/writing'

export interface FocusState {
  schemaVersion: 1
  duration: number
  seconds: number
  remainingMs: number
  deadline: number
  accountedAt: number
  uid: string | null
  legacy: boolean
  millisecondsByDate: Record<string, number>
  pending: FocusReceipt[]
}
const durations = [900, 1500, 2700]
const datePattern = /^\d{4}-\d{2}-\d{2}$/
const uuidPattern = /^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i
const validNumber = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value) && value >= 0
const validBuckets = (value: unknown, integers = false) => !!value && typeof value === 'object' && !Array.isArray(value) && Object.entries(value).every(([date, seconds]) => datePattern.test(date) && validNumber(seconds) && (!integers || Number.isInteger(seconds)))

export function focusReceiptKey(receipt: FocusReceipt) {
  return JSON.stringify([receipt.uid, receipt.endedOn, receipt.completed, Object.entries(receipt.secondsByDate).sort(([a], [b]) => a.localeCompare(b))])
}

export function createFocusState(duration = 1500): FocusState {
  return { schemaVersion: 1, duration, seconds: duration, remainingMs: duration * 1000, deadline: 0, accountedAt: 0, uid: null, legacy: false, millisecondsByDate: {}, pending: [] }
}

/** Keep old countdowns working; only newly started identified periods create statistics. */
export function readFocusState(raw: string | null): FocusState {
  if (!raw) return createFocusState()
  const saved = JSON.parse(raw)
  if (!saved || !durations.includes(saved.duration) || !validNumber(saved.seconds) || saved.seconds > saved.duration || !validNumber(saved.deadline)) throw Error('专注计时记录无法读取，原记录已保留。')
  if (saved.schemaVersion === undefined) {
    return { ...createFocusState(saved.duration), seconds: saved.seconds, remainingMs: saved.seconds * 1000, deadline: saved.deadline, legacy: saved.deadline > 0 || (saved.seconds > 0 && saved.seconds < saved.duration) }
  }
  if (saved.schemaVersion !== 1 || !validNumber(saved.remainingMs) || saved.remainingMs > saved.duration * 1000 || !validNumber(saved.accountedAt) || typeof saved.legacy !== 'boolean' || (saved.uid !== null && (typeof saved.uid !== 'string' || !uuidPattern.test(saved.uid))) || !validBuckets(saved.millisecondsByDate) || !Array.isArray(saved.pending) || !saved.pending.every((receipt: FocusReceipt) => receipt && uuidPattern.test(receipt.uid) && datePattern.test(receipt.endedOn) && typeof receipt.completed === 'boolean' && validBuckets(receipt.secondsByDate, true) && Number.isInteger(receipt.timezoneOffsetMinutes) && Math.abs(receipt.timezoneOffsetMinutes) <= 840)) throw Error('专注计时记录无法读取，原记录已保留。')
  return saved
}

function accumulate(state: FocusState, from: number, until: number) {
  for (let cursor = from; cursor < until;) {
    const date = new Date(cursor)
    const midnight = new Date(date.getFullYear(), date.getMonth(), date.getDate() + 1).getTime()
    const end = Math.min(until, midnight)
    const key = localDate(date)
    state.millisecondsByDate[key] = (state.millisecondsByDate[key] || 0) + end - cursor
    cursor = end
  }
}

function finish(state: FocusState, completed: boolean, endedAt: number) {
  if (state.uid && !state.legacy) {
    const secondsByDate: Record<string, number> = {}
    let remainder = 0
    for (const [date, milliseconds] of Object.entries(state.millisecondsByDate).sort(([a], [b]) => a.localeCompare(b))) {
      const total = milliseconds + remainder
      const seconds = Math.floor(total / 1000)
      remainder = total % 1000
      if (seconds) secondsByDate[date] = seconds
    }
    if (Object.keys(secondsByDate).length || completed) {
      const date = new Date(endedAt)
      // This payload is immutable until acknowledgement, including its local date.
      state.pending.push({ uid: state.uid, endedOn: localDate(date), completed, secondsByDate, timezoneOffsetMinutes: date.getTimezoneOffset() })
    }
  }
  state.uid = null
  state.legacy = false
  state.deadline = 0
  state.accountedAt = 0
  state.millisecondsByDate = {}
}

export function tickFocus(state: FocusState, now = Date.now()) {
  if (!state.deadline) return
  const end = Math.min(now, state.deadline)
  if (state.uid && end > state.accountedAt) {
    accumulate(state, state.accountedAt, end)
    state.accountedAt = end
  }
  // Moving the wall clock backwards must not increase the remaining duration.
  state.remainingMs = Math.max(0, Math.min(state.remainingMs, state.deadline - now))
  state.seconds = Math.ceil(state.remainingMs / 1000)
  if (!state.remainingMs) finish(state, true, state.deadline)
}

export function startFocus(state: FocusState, now = Date.now(), uid = crypto.randomUUID()) {
  if (state.deadline) return
  if (!state.remainingMs) {
    state.legacy = false
    state.remainingMs = state.duration * 1000
    state.seconds = state.duration
  }
  if (!state.uid && !state.legacy) state.uid = uid
  state.accountedAt = now
  state.deadline = now + state.remainingMs
}

export function pauseFocus(state: FocusState, now = Date.now()) {
  tickFocus(state, now)
  state.deadline = 0
  state.accountedAt = 0
}

export function resetFocus(state: FocusState, now = Date.now(), duration = state.duration) {
  tickFocus(state, now)
  finish(state, false, now)
  state.duration = duration
  state.seconds = duration
  state.remainingMs = duration * 1000
}
